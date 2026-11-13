// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

use crate::model::{API_VERSION, Authentication, Command, CoreEvent, StartRequest};
use crate::sftp::run_sftp;
use crate::terminal::TerminalState;
use base64::Engine;
use russh::client;
use russh::keys::{PrivateKeyWithHashAlg, decode_secret_key, ssh_key};
use russh::{ChannelMsg, Disconnect};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::os::fd::OwnedFd;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock, mpsc as std_mpsc};
use std::time::Duration;
use thiserror::Error;
use tokio::net::UnixStream;
use tokio::runtime::{Builder, Runtime};
use tokio::sync::mpsc;
use tokio::time::timeout;
use tokio_util::sync::CancellationToken;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
const AUTH_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_INPUT_BYTES: usize = 256 * 1024;

#[derive(Debug, Clone, Copy)]
pub enum SessionKind {
    Shell,
    Sftp,
    Herdr,
}

#[derive(Debug, Error)]
pub enum StartError {
    #[error("invalid request")]
    InvalidRequest,
    #[error("native runtime unavailable")]
    RuntimeUnavailable,
    #[error("Herdr temporary resource limit reached")]
    HerdrResourceLimit,
    #[error("unable to initialize Herdr terminal")]
    HerdrTerminalUnavailable,
}

#[derive(Debug, Error)]
pub enum SessionError {
    #[error("invalid stream")]
    InvalidStream,
    #[error("connection timed out")]
    ConnectionTimeout,
    #[error("connection failed")]
    Connection,
    #[error("host key was not accepted")]
    HostKeyRejected,
    #[error("private key is invalid or needs a different passphrase")]
    InvalidPrivateKey,
    #[error("authentication timed out")]
    AuthenticationTimeout,
    #[error("authentication failed")]
    Authentication,
    #[error("Herdr discovery failed")]
    HerdrDiscovery,
    #[error("Herdr endpoint incompatible")]
    HerdrCompatibility,
    #[error("invalid Herdr protocol")]
    HerdrProtocol,
    #[error("Herdr protocol failure: {0}")]
    HerdrProtocolDetail(&'static str),
    #[error("session channel failed")]
    Channel,
}

// Herdr surfaces can arrive far faster than a paused Android poller can drain
// events. Keep only the latest display updates and bound both count and bytes.
// Ordinary SSH/SFTP continue using their existing event channel.
const HERDR_EVENT_LIMIT: usize = 32;
const HERDR_EVENT_BYTES: usize = 4 * 1024 * 1024;
const HERDR_CONTROL_RESERVE: usize = 8;
const HERDR_ERROR_RESERVE_BYTES: usize = 1024;
// One producer is allowed to convert/parse a Herdr command at a time, and
// 16 validated commands can be queued without blocking a JNI caller. The
// Java-side dispatcher must also bound its own pending input.
const HERDR_COMMAND_QUEUE_LIMIT: usize = 16;
pub(crate) const HERDR_COMMAND_JSON_LIMIT: usize = 352 * 1024;
pub(crate) const HERDR_START_JSON_LIMIT: usize = 1024 * 1024;

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
enum HerdrCommand {
    HerdrResume,
    HerdrProbe {
        nonce: String,
    },
    HerdrDisplay {
        improve_text_contrast: bool,
        focused: bool,
    },
    HerdrAction {
        request_id: u64,
        boot_id: String,
        action: crate::herdr::NativeAction,
    },
    Input {
        base64: String,
        herdr_target_epoch: u64,
    },
    Resize {
        columns: u32,
        rows: u32,
        cell_width: u32,
        cell_height: u32,
    },
    TerminalTheme {
        foreground: u32,
        background: u32,
        cursor: u32,
        ansi: Vec<u32>,
    },
    Close,
    HerdrAttach {
        session_name: String,
    },
}

fn parse_herdr_command(json: &str) -> Option<Command> {
    if json.len() > HERDR_COMMAND_JSON_LIMIT {
        return None;
    }
    match serde_json::from_str::<HerdrCommand>(json).ok()? {
        HerdrCommand::HerdrResume => Some(Command::HerdrResume),
        HerdrCommand::HerdrProbe { nonce } if !nonce.is_empty() && nonce.len() <= 128 => {
            Some(Command::HerdrProbe { nonce })
        }
        HerdrCommand::HerdrDisplay {
            improve_text_contrast,
            focused,
        } => Some(Command::HerdrDisplay {
            improve_text_contrast,
            focused,
        }),
        HerdrCommand::HerdrAction {
            request_id,
            boot_id,
            action,
        } if request_id > 0 && !boot_id.is_empty() && boot_id.len() <= 256 && action.valid() => {
            Some(Command::HerdrAction {
                request_id,
                boot_id,
                action,
            })
        }
        HerdrCommand::Input {
            base64,
            herdr_target_epoch,
        } if base64.len() <= (256usize * 1024).div_ceil(3) * 4 && herdr_target_epoch != 0 => {
            Some(Command::Input {
                base64,
                herdr_target_epoch: Some(herdr_target_epoch),
            })
        }
        HerdrCommand::Resize {
            columns,
            rows,
            cell_width,
            cell_height,
        } if (20..=500).contains(&columns)
            && (5..=300).contains(&rows)
            && (1..=512).contains(&cell_width)
            && (1..=512).contains(&cell_height) =>
        {
            Some(Command::Resize {
                columns,
                rows,
                cell_width,
                cell_height,
            })
        }
        HerdrCommand::TerminalTheme {
            foreground,
            background,
            cursor,
            ansi,
        } if ansi.len() == 16
            && [foreground, background, cursor]
                .into_iter()
                .chain(ansi.iter().copied())
                .all(|c| c <= 0xffffff) =>
        {
            Some(Command::TerminalTheme {
                foreground,
                background,
                cursor,
                ansi,
            })
        }
        HerdrCommand::HerdrAttach { session_name }
            if !session_name.is_empty()
                && session_name.len() <= 64
                && session_name != "."
                && session_name != ".."
                && session_name
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b)) =>
        {
            Some(Command::HerdrAttach { session_name })
        }
        HerdrCommand::Close => Some(Command::Close),
        _ => None,
    }
}

pub(crate) struct HerdrCommandGuard<'a>(&'a AtomicBool);
impl Drop for HerdrCommandGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

pub(crate) struct HerdrSnapshotGuard<'a>(&'a AtomicBool);
impl Drop for HerdrSnapshotGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

struct QueuedEvent {
    kind: String,
    json: String,
}
#[derive(Default)]
struct HerdrEventState {
    queue: VecDeque<QueuedEvent>,
    bytes: usize,
    overflowed: bool,
}
pub(crate) struct HerdrEventQueue {
    state: Mutex<HerdrEventState>,
    wake: Condvar,
    cancel: CancellationToken,
    senders: AtomicUsize,
}
pub(crate) enum EventSender {
    Ordinary(std_mpsc::Sender<String>),
    Herdr(Arc<HerdrEventQueue>),
}
enum EventReceiver {
    Ordinary(Mutex<std_mpsc::Receiver<String>>),
    Herdr(Arc<HerdrEventQueue>),
}

fn is_transient(kind: &str) -> bool {
    matches!(
        kind,
        "snapshot_changed" | "herdr_projection" | "clipboard_write"
    )
}
impl HerdrEventQueue {
    fn push(&self, kind: &str, json: String) {
        let Ok(mut state) = self.state.lock() else {
            self.cancel.cancel();
            return;
        };
        if state.overflowed && (is_transient(kind) || kind == "error") {
            return;
        }
        // These two events represent replaceable state, not an ordered stream.
        if matches!(kind, "snapshot_changed" | "herdr_projection") {
            if let Some(index) = state.queue.iter().position(|event| event.kind == kind) {
                let old = state.queue.remove(index).expect("event exists");
                state.bytes -= old.json.capacity();
            }
        }
        let is_over_limit = state.bytes.saturating_add(json.capacity())
            > HERDR_EVENT_BYTES - HERDR_ERROR_RESERVE_BYTES
            || (state.queue.len() >= HERDR_EVENT_LIMIT - HERDR_CONTROL_RESERVE
                && is_transient(kind))
            || state.queue.len() >= HERDR_EVENT_LIMIT;
        if is_over_limit && !state.overflowed {
            state.overflowed = true;
            state.queue.retain(|event| !is_transient(&event.kind));
            state.bytes = state.queue.iter().map(|event| event.json.capacity()).sum();
            let error = event_json({
                let mut event = CoreEvent::named("error");
                event.category = Some("protocol");
                event.message = Some("Herdr event buffer overflow; connection closed");
                event
            });
            state.bytes += error.capacity();
            state.queue.push_back(QueuedEvent {
                kind: "error".into(),
                json: error,
            });
            self.cancel.cancel();
        }
        // Preserve the bounded synthesized overflow error if the incoming
        // error itself was too large to fit.
        if is_over_limit && kind == "error" {
            self.wake.notify_all();
            return;
        }
        // Control events remain observable even if transient traffic overflowed.
        // There are at most eight distinct control events in one attachment;
        // reserve exceeds that finite set. A repeated control is replaced.
        if !is_transient(kind) {
            if let Some(index) = state.queue.iter().position(|event| event.kind == kind) {
                let old = state.queue.remove(index).expect("event exists");
                state.bytes -= old.json.capacity();
            }
        }
        if (!state.overflowed || !is_transient(kind))
            && state.queue.len() < HERDR_EVENT_LIMIT
            && state.bytes.saturating_add(json.capacity())
                <= HERDR_EVENT_BYTES - HERDR_ERROR_RESERVE_BYTES
        {
            state.bytes += json.capacity();
            state.queue.push_back(QueuedEvent {
                kind: kind.into(),
                json,
            });
        } else if !state.overflowed {
            // An oversized control event cannot be delivered safely.
            state.overflowed = true;
            self.cancel.cancel();
        }
        self.wake.notify_all();
    }
    fn poll(&self, timeout_ms: u64) -> String {
        let Ok(mut state) = self.state.lock() else {
            return event_json(CoreEvent::named("event_stream_closed"));
        };
        if state.queue.is_empty()
            && self.senders.load(Ordering::Acquire) > 0
            && !self.cancel.is_cancelled()
        {
            let Ok((next, _)) = self
                .wake
                .wait_timeout(state, Duration::from_millis(timeout_ms.min(30_000)))
            else {
                return event_json(CoreEvent::named("event_stream_closed"));
            };
            state = next;
        }
        if let Some(event) = state.queue.pop_front() {
            state.bytes -= event.json.capacity();
            event.json
        } else if self.senders.load(Ordering::Acquire) == 0 || self.cancel.is_cancelled() {
            event_json(CoreEvent::named("event_stream_closed"))
        } else {
            String::new()
        }
    }
}
impl Clone for EventSender {
    fn clone(&self) -> Self {
        match self {
            Self::Ordinary(sender) => Self::Ordinary(sender.clone()),
            Self::Herdr(queue) => {
                queue.senders.fetch_add(1, Ordering::Relaxed);
                Self::Herdr(queue.clone())
            }
        }
    }
}
impl Drop for EventSender {
    fn drop(&mut self) {
        if let Self::Herdr(queue) = self {
            // Update under the same mutex used by poll so the last-sender
            // notification cannot race a poller entering wait_timeout.
            if let Ok(_state) = queue.state.lock() {
                queue.senders.fetch_sub(1, Ordering::Release);
                queue.wake.notify_all();
            }
        }
    }
}

pub struct SessionEntry {
    events: EventReceiver,
    commands: mpsc::Sender<Command>,
    terminal: Arc<Mutex<TerminalState>>,
    displayed_target_epoch: Arc<AtomicU64>,
    cancel: CancellationToken,
    // Shared with the native task. A session close cannot free its logical
    // admission while an in-flight task or snapshot still owns resources.
    _herdr_permit: Option<Arc<crate::herdr_budget::Permit>>,
    herdr_command_active: AtomicBool,
    herdr_snapshot_active: AtomicBool,
}

impl SessionEntry {
    pub fn poll_event(&self, timeout_ms: u64) -> String {
        let wait = Duration::from_millis(timeout_ms.min(30_000));
        match &self.events {
            EventReceiver::Ordinary(receiver) => match receiver
                .lock()
                .expect("event receiver poisoned")
                .recv_timeout(wait)
            {
                Ok(event) => event,
                Err(std_mpsc::RecvTimeoutError::Timeout) => String::new(),
                Err(std_mpsc::RecvTimeoutError::Disconnected) => {
                    event_json(CoreEvent::named("event_stream_closed"))
                }
            },
            EventReceiver::Herdr(queue) => queue.poll(timeout_ms),
        }
    }

    pub fn send_command(&self, command: Command) -> bool {
        // Herdr's JNI path uses its own bounded parser and nonblocking queue.
        !self.is_herdr() && self.commands.blocking_send(command).is_ok()
    }

    pub(crate) fn is_herdr(&self) -> bool {
        self._herdr_permit.is_some()
    }

    pub(crate) fn begin_herdr_command(&self) -> Option<HerdrCommandGuard<'_>> {
        if !self.is_herdr() {
            return None;
        }
        self.herdr_command_active
            .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
            .ok()
            .map(|_| HerdrCommandGuard(&self.herdr_command_active))
    }

    pub(crate) fn send_herdr_json(&self, json: &str) -> bool {
        parse_herdr_command(json).is_some_and(|command| self.commands.try_send(command).is_ok())
    }

    pub(crate) fn begin_herdr_snapshot(&self) -> Option<HerdrSnapshotGuard<'_>> {
        if !self.is_herdr() {
            return None;
        }
        self.herdr_snapshot_active
            .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
            .ok()
            .map(|_| HerdrSnapshotGuard(&self.herdr_snapshot_active))
    }

    pub fn snapshot_bytes(&self, image_revision: u64) -> Vec<u8> {
        self.frame_bytes(0, image_revision)
    }

    pub fn frame_bytes(&self, frame_revision: u64, image_revision: u64) -> Vec<u8> {
        // Herdr snapshots carry a target epoch and must use the guarded JNI path.
        if self.is_herdr() {
            return Vec::new();
        }
        self.terminal
            .lock()
            .expect("terminal state poisoned")
            .frame_bytes_since(frame_revision, image_revision)
    }

    pub fn herdr_snapshot_bytes(&self, image_revision: u64) -> Vec<u8> {
        self.herdr_frame_bytes(0, image_revision)
    }

    pub fn herdr_frame_bytes(&self, frame_revision: u64, image_revision: u64) -> Vec<u8> {
        let mut terminal = self.terminal.lock().expect("terminal state poisoned");
        let epoch = self.displayed_target_epoch.load(Ordering::Relaxed);
        let snapshot = terminal.frame_bytes_since(frame_revision, image_revision);
        let mut result = Vec::with_capacity(8 + snapshot.len());
        result.extend_from_slice(&epoch.to_le_bytes());
        result.extend_from_slice(&snapshot);
        result
    }

    pub fn close(&self) {
        if let Some(permit) = &self._herdr_permit {
            permit.mark_closing();
        }
        self.cancel.cancel();
        if let EventReceiver::Herdr(queue) = &self.events {
            // Pollers release their in-flight entry promptly. Holding the
            // queue lock here pairs with the cancellation check before wait.
            if let Ok(_state) = queue.state.lock() {
                queue.wake.notify_all();
            }
        }
    }
}

pub struct SessionManager {
    next_id: AtomicU64,
    sessions: Mutex<HashMap<u64, Arc<SessionEntry>>>,
}

impl SessionManager {
    fn new() -> Self {
        Self {
            next_id: AtomicU64::new(1),
            sessions: Mutex::new(HashMap::new()),
        }
    }

    pub fn start(
        &self,
        fd: OwnedFd,
        request_json: &str,
        kind: SessionKind,
    ) -> Result<u64, StartError> {
        let request: StartRequest =
            serde_json::from_str(request_json).map_err(|_| StartError::InvalidRequest)?;
        request.validate().map_err(|_| StartError::InvalidRequest)?;

        let native_runtime = runtime().map_err(|_| StartError::RuntimeUnavailable)?;
        let herdr_permit = if matches!(kind, SessionKind::Herdr) {
            Some(crate::herdr_budget::admit().ok_or(StartError::HerdrResourceLimit)?)
        } else {
            None
        };
        let task_permit = herdr_permit.clone();

        let cancel = CancellationToken::new();
        let (event_sender, event_receiver) = if matches!(kind, SessionKind::Herdr) {
            let queue = Arc::new(HerdrEventQueue {
                state: Mutex::new(HerdrEventState::default()),
                wake: Condvar::new(),
                cancel: cancel.clone(),
                senders: AtomicUsize::new(1),
            });
            (
                EventSender::Herdr(queue.clone()),
                EventReceiver::Herdr(queue),
            )
        } else {
            let (sender, receiver) = std_mpsc::channel();
            (
                EventSender::Ordinary(sender),
                EventReceiver::Ordinary(Mutex::new(receiver)),
            )
        };
        let (command_sender, command_receiver) =
            mpsc::channel(if matches!(kind, SessionKind::Herdr) {
                HERDR_COMMAND_QUEUE_LIMIT
            } else {
                64
            });
        let terminal = Arc::new(Mutex::new(if matches!(kind, SessionKind::Herdr) {
            TerminalState::try_new_herdr(
                request.columns,
                request.rows,
                request.terminal_theme.as_ref(),
            )
            .ok_or(StartError::HerdrTerminalUnavailable)?
        } else {
            TerminalState::new(
                request.columns,
                request.rows,
                request.terminal_theme.as_ref(),
            )
        }));
        let displayed_target_epoch = Arc::new(AtomicU64::new(0));
        let entry = Arc::new(SessionEntry {
            displayed_target_epoch: displayed_target_epoch.clone(),
            events: event_receiver,
            commands: command_sender,
            terminal: terminal.clone(),
            cancel: cancel.clone(),
            _herdr_permit: herdr_permit,
            herdr_command_active: AtomicBool::new(false),
            herdr_snapshot_active: AtomicBool::new(false),
        });
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.sessions
            .lock()
            .expect("session map poisoned")
            .insert(id, entry);

        native_runtime.spawn(async move {
            // The task owns terminal state and protocol allocations even
            // when the application releases its session entry first.
            let _herdr_permit = task_permit;
            let result = match kind {
                SessionKind::Shell => {
                    run_shell(
                        fd,
                        request,
                        event_sender.clone(),
                        command_receiver,
                        terminal,
                        cancel,
                    )
                    .await
                }
                SessionKind::Herdr => {
                    crate::herdr_transport::run_herdr(
                        fd,
                        request,
                        event_sender.clone(),
                        command_receiver,
                        terminal,
                        displayed_target_epoch,
                        cancel,
                    )
                    .await
                }
                SessionKind::Sftp => {
                    run_sftp(fd, request, event_sender.clone(), command_receiver, cancel).await
                }
            };
            if let Err(error) = result {
                let mut event = CoreEvent::named("error");
                event.category = Some(error.category());
                event.message = Some(error.user_message());
                emit(&event_sender, event);
            }
        });
        Ok(id)
    }

    pub fn get(&self, id: u64) -> Option<Arc<SessionEntry>> {
        self.sessions.lock().ok()?.get(&id).cloned()
    }

    pub fn release(&self, id: u64) {
        if let Some(entry) = self
            .sessions
            .lock()
            .ok()
            .and_then(|mut map| map.remove(&id))
        {
            entry.close();
        }
    }
}

pub fn manager() -> &'static SessionManager {
    static MANAGER: OnceLock<SessionManager> = OnceLock::new();
    MANAGER.get_or_init(SessionManager::new)
}

fn runtime() -> Result<&'static Runtime, ()> {
    static RUNTIME: OnceLock<Result<Runtime, ()>> = OnceLock::new();
    RUNTIME
        .get_or_init(|| {
            Builder::new_multi_thread()
                .worker_threads(2)
                .thread_name("novascale-rust-core")
                .enable_all()
                .build()
                .map_err(|_| ())
        })
        .as_ref()
        .map_err(|_| ())
}

pub(crate) struct HostKeyHandler {
    expected: Option<String>,
    rejected: Arc<AtomicBool>,
    events: EventSender,
}

impl client::Handler for HostKeyHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        server_public_key: &ssh_key::PublicKey,
    ) -> Result<bool, Self::Error> {
        let fingerprint = format!(
            "{}",
            server_public_key.fingerprint(ssh_key::HashAlg::Sha256)
        );
        let algorithm = server_public_key.algorithm().to_string();
        let accepted = self.expected.as_deref() == Some(fingerprint.as_str());
        if !accepted {
            self.rejected.store(true, Ordering::Release);
            let mut event = CoreEvent::named(if self.expected.is_some() {
                "host_key_changed"
            } else {
                "host_key_unknown"
            });
            event.fingerprint = Some(&fingerprint);
            event.algorithm = Some(&algorithm);
            event.expected_fingerprint = self.expected.as_deref();
            emit(&self.events, event);
        }
        Ok(accepted)
    }
}

pub async fn connect_authenticated(
    fd: OwnedFd,
    request: &mut StartRequest,
    events: &EventSender,
) -> Result<client::Handle<HostKeyHandler>, SessionError> {
    let std_stream = std::os::unix::net::UnixStream::from(fd);
    std_stream
        .set_nonblocking(true)
        .map_err(|_| SessionError::InvalidStream)?;
    let stream = UnixStream::from_std(std_stream).map_err(|_| SessionError::InvalidStream)?;

    let rejected = Arc::new(AtomicBool::new(false));
    let handler = HostKeyHandler {
        expected: request.expected_host_key.clone(),
        rejected: rejected.clone(),
        events: events.clone(),
    };
    let config = Arc::new(client::Config {
        keepalive_interval: Some(Duration::from_secs(30)),
        keepalive_max: 3,
        ..Default::default()
    });
    let connection = timeout(
        CONNECT_TIMEOUT,
        client::connect_stream(config, stream, handler),
    )
    .await
    .map_err(|_| SessionError::ConnectionTimeout)?;
    let mut session = match connection {
        Ok(session) => session,
        Err(_) if rejected.load(Ordering::Acquire) => return Err(SessionError::HostKeyRejected),
        Err(_) => return Err(SessionError::Connection),
    };
    emit(events, CoreEvent::named("connected"));

    let mut authentication = std::mem::replace(&mut request.authentication, Authentication::None);
    let result = timeout(AUTH_TIMEOUT, async {
        match &mut authentication {
            Authentication::None => session.authenticate_none(request.username.clone()).await,
            Authentication::Password { password } => {
                session
                    .authenticate_password(request.username.clone(), password.as_str())
                    .await
            }
            Authentication::PrivateKey {
                private_key,
                passphrase,
            } => {
                let key = decode_secret_key(private_key, passphrase.as_deref())
                    .map_err(|_| russh::Error::CouldNotReadKey)?;
                let hash_algorithm = session.best_supported_rsa_hash().await?.flatten();
                session
                    .authenticate_publickey(
                        request.username.clone(),
                        PrivateKeyWithHashAlg::new(Arc::new(key), hash_algorithm),
                    )
                    .await
            }
        }
    })
    .await
    .map_err(|_| SessionError::AuthenticationTimeout)?
    .map_err(|error| {
        if matches!(error, russh::Error::CouldNotReadKey) {
            SessionError::InvalidPrivateKey
        } else {
            SessionError::Authentication
        }
    })?;
    if !result.success() {
        return Err(SessionError::Authentication);
    }
    emit(events, CoreEvent::named("authenticated"));
    Ok(session)
}

async fn run_shell(
    fd: OwnedFd,
    mut request: StartRequest,
    events: EventSender,
    mut commands: mpsc::Receiver<Command>,
    terminal: Arc<Mutex<TerminalState>>,
    cancel: CancellationToken,
) -> Result<(), SessionError> {
    let session = connect_authenticated(fd, &mut request, &events).await?;
    let mut channel = session
        .channel_open_session()
        .await
        .map_err(|_| SessionError::Channel)?;
    channel
        .request_pty(
            true,
            &request.terminal,
            request.columns,
            request.rows,
            0,
            0,
            &[],
        )
        .await
        .map_err(|_| SessionError::Channel)?;
    channel
        .request_shell(true)
        .await
        .map_err(|_| SessionError::Channel)?;
    emit(&events, CoreEvent::named("ready"));

    let mut exit_status = None;
    loop {
        let hold = terminal
            .lock()
            .expect("terminal state poisoned")
            .render_hold_remaining_ms();
        tokio::select! {
            _ = cancel.cancelled() => break,
            _ = crate::terminal::wait_for_render_hold(hold) => {
                if let Some(revision) = terminal.lock().expect("terminal state poisoned").tick_render_hold() {
                    let mut event = CoreEvent::named("snapshot_changed");
                    event.revision = Some(revision);
                    emit(&events, event);
                }
            },
            Some(command) = commands.recv() => match command {
                Command::Input { base64, .. } => {
                    let Ok(data) = base64::engine::general_purpose::STANDARD.decode(base64) else {
                        continue;
                    };
                    if !data.is_empty() && data.len() <= MAX_INPUT_BYTES {
                        let bottom_revision = terminal
                            .lock()
                            .expect("terminal state poisoned")
                            .scroll_to_bottom();
                        if let Some(revision) = bottom_revision {
                            let mut event = CoreEvent::named("snapshot_changed");
                            event.revision = Some(revision);
                            emit(&events, event);
                        }
                        channel.data(&data[..]).await.map_err(|_| SessionError::Channel)?;
                    }
                }
                Command::Resize { columns, rows, cell_width, cell_height }
                    if (20..=500).contains(&columns) && (5..=300).contains(&rows) => {
                        let (revision, replies) = {
                            let mut terminal = terminal.lock().expect("terminal state poisoned");
                            let revision = terminal.resize_with_metrics(columns, rows, cell_width.clamp(1, 512), cell_height.clamp(1, 512));
                            (revision, terminal.drain_pty_writes())
                        };
                        // Keyboard/reattachment geometry must reach the renderer even
                        // when the remote program is idle or slow to repaint its PTY.
                        let mut event = CoreEvent::named("snapshot_changed");
                        event.revision = Some(revision);
                        emit(&events, event);
                        channel.window_change(columns, rows, columns * cell_width.clamp(1, 512), rows * cell_height.clamp(1, 512)).await
                            .map_err(|_| SessionError::Channel)?;
                        if !replies.is_empty() {
                            channel.data(&replies[..]).await.map_err(|_| SessionError::Channel)?;
                        }
                    }
                Command::TerminalScroll { rows } if rows != 0 => {
                    let revision = terminal
                        .lock()
                        .expect("terminal state poisoned")
                        .scroll(rows.clamp(-300, 300));
                    let mut event = CoreEvent::named("snapshot_changed");
                    event.revision = Some(revision);
                    emit(&events, event);
                }
                Command::TerminalTheme { foreground, background, cursor, ansi } => {
                    let theme = crate::model::TerminalThemeConfig {
                        foreground,
                        background,
                        cursor,
                        ansi,
                    };
                    if theme.is_valid() {
                        let revision = terminal
                            .lock()
                            .expect("terminal state poisoned")
                            .set_theme(&theme);
                        let mut event = CoreEvent::named("snapshot_changed");
                        event.revision = Some(revision);
                        emit(&events, event);
                    }
                }
                Command::Close => break,
                _ => {}
            },
            message = channel.wait() => match message {
                Some(ChannelMsg::Data { data }) | Some(ChannelMsg::ExtendedData { data, .. }) => {
                    let (revision, replies, clipboard) = {
                        let mut terminal = terminal.lock().expect("terminal state poisoned");
                        let revision = terminal.feed(&data);
                        let replies = terminal.drain_pty_writes();
                        (revision, replies, terminal.take_clipboard())
                    };
                    if !replies.is_empty() {
                        channel.data(&replies[..]).await.map_err(|_| SessionError::Channel)?;
                    }
                    if let Some(text) = clipboard {
                        let payload = serde_json::json!({ "text": text });
                        let mut event = CoreEvent::named("clipboard_write");
                        event.payload = Some(&payload);
                        emit(&events, event);
                    }
                    let mut event = CoreEvent::named("snapshot_changed");
                    event.revision = Some(revision);
                    emit(&events, event);
                }
                Some(ChannelMsg::ExitStatus { exit_status: status }) => {
                    exit_status = Some(status);
                    break;
                }
                None => break,
                _ => {}
            }
        }
    }

    let _ = channel.eof().await;
    let _ = channel.close().await;
    let _ = session
        .disconnect(Disconnect::ByApplication, "", "en")
        .await;
    let mut event = CoreEvent::named("closed");
    event.exit_status = exit_status;
    emit(&events, event);
    Ok(())
}

impl SessionError {
    fn category(&self) -> &'static str {
        match self {
            Self::HostKeyRejected => "host_key",
            Self::InvalidPrivateKey | Self::Authentication | Self::AuthenticationTimeout => {
                "authentication"
            }
            Self::Connection | Self::ConnectionTimeout | Self::InvalidStream => "network",
            Self::Channel
            | Self::HerdrDiscovery
            | Self::HerdrCompatibility
            | Self::HerdrProtocol
            | Self::HerdrProtocolDetail(_) => "protocol",
        }
    }

    fn user_message(&self) -> &'static str {
        match self {
            Self::InvalidStream => "The tailnet stream could not be adopted",
            Self::ConnectionTimeout => "The SSH connection timed out",
            Self::Connection => "The SSH connection failed",
            Self::HostKeyRejected => "The server host key requires confirmation",
            Self::InvalidPrivateKey => "The private key or passphrase is invalid",
            Self::AuthenticationTimeout => "SSH authentication timed out",
            Self::Authentication => "SSH authentication failed",
            Self::Channel => "The SSH session channel failed",
            Self::HerdrDiscovery => "Herdr could not be discovered or listed on this host",
            Self::HerdrCompatibility => {
                "Herdr endpoint is incompatible; check the installed and running server versions"
            }
            Self::HerdrProtocol => "The Herdr connection received invalid protocol data",
            Self::HerdrProtocolDetail(message) => message,
        }
    }
}

pub fn emit(sender: &EventSender, event: CoreEvent<'_>) {
    let kind = event.event_type;
    let json = event_json(event);
    match sender {
        EventSender::Ordinary(sender) => {
            let _ = sender.send(json);
        }
        EventSender::Herdr(queue) => queue.push(kind, json),
    }
}

/// Wrap an already serialized local projection without reparsing it into an
/// unbounded second JSON object graph. The bytes are produced by the bounded
/// Herdr projection serializer, not passed through directly from the peer.
pub(crate) fn emit_herdr_projection(sender: &EventSender, payload: &[u8]) -> bool {
    let prefix = format!("{{\"version\":{API_VERSION},\"type\":\"herdr_projection\",\"payload\":");
    const SUFFIX: &str = "}";
    let Some(total) = prefix
        .len()
        .checked_add(payload.len())
        .and_then(|n| n.checked_add(SUFFIX.len()))
    else {
        return false;
    };
    if total > HERDR_EVENT_BYTES - HERDR_ERROR_RESERVE_BYTES {
        return false;
    }
    let Ok(payload) = std::str::from_utf8(payload) else {
        return false;
    };
    let mut json = String::with_capacity(total);
    json.push_str(&prefix);
    json.push_str(payload);
    json.push_str(SUFFIX);
    match sender {
        EventSender::Ordinary(sender) => sender.send(json).is_ok(),
        EventSender::Herdr(queue) => {
            queue.push("herdr_projection", json);
            !queue.cancel.is_cancelled()
        }
    }
}

fn event_json(event: CoreEvent<'_>) -> String {
    serde_json::to_string(&event).unwrap_or_else(|_| {
        format!(
            r#"{{"version":{},"type":"error","category":"internal","message":"Unable to encode native event"}}"#,
            API_VERSION
        )
    })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StartResponse<'a> {
    pub version: u32,
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_id: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<&'a str>,
}

pub fn start_response(result: Result<u64, StartError>) -> String {
    let response = match result {
        Ok(id) => StartResponse {
            version: API_VERSION,
            ok: true,
            session_id: Some(id),
            error: None,
        },
        Err(StartError::HerdrResourceLimit) => StartResponse {
            version: API_VERSION,
            ok: false,
            session_id: None,
            error: Some("Herdr temporary resource limit reached"),
        },
        Err(_) => StartResponse {
            version: API_VERSION,
            ok: false,
            session_id: None,
            error: Some("Unable to start the native session"),
        },
    };
    serde_json::to_string(&response).unwrap_or_else(|_| "{\"ok\":false}".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;
    use std::os::fd::{FromRawFd, IntoRawFd};

    fn herdr_test_queue() -> (Arc<HerdrEventQueue>, EventSender) {
        let queue = Arc::new(HerdrEventQueue {
            state: Mutex::new(HerdrEventState::default()),
            wake: Condvar::new(),
            cancel: CancellationToken::new(),
            senders: AtomicUsize::new(1),
        });
        (queue.clone(), EventSender::Herdr(queue))
    }

    #[test]
    fn herdr_slow_poller_gets_latest_display_and_all_control_events() {
        let (queue, sender) = herdr_test_queue();
        emit(&sender, CoreEvent::named("host_key_unknown"));
        for index in 0..10_000 {
            let value = serde_json::json!({"value":index});
            let mut projection = CoreEvent::named("herdr_projection");
            projection.payload = Some(&value);
            emit(&sender, projection);
            let mut snapshot = CoreEvent::named("snapshot_changed");
            snapshot.revision = Some(index);
            emit(&sender, snapshot);
        }
        emit(&sender, CoreEvent::named("error"));
        emit(&sender, CoreEvent::named("closed"));
        let mut received = Vec::new();
        while !queue.state.lock().unwrap().queue.is_empty() {
            received.push(queue.poll(0));
        }
        assert_eq!(received.len(), 5);
        assert!(received.iter().any(|v| v.contains("host_key_unknown")));
        assert!(received.iter().any(|v| v.contains("error")));
        assert!(received.iter().any(|v| v.contains("closed")));
        assert!(
            received
                .iter()
                .find(|v| v.contains("herdr_projection"))
                .unwrap()
                .contains("9999")
        );
        assert!(
            received
                .iter()
                .find(|v| v.contains("snapshot_changed"))
                .unwrap()
                .contains("9999")
        );
        assert!(!queue.cancel.is_cancelled());
    }

    #[test]
    fn herdr_stopped_poller_overflow_cancels_and_preserves_controls() {
        let (queue, sender) = herdr_test_queue();
        emit(&sender, CoreEvent::named("host_key_changed"));
        for _ in 0..1000 {
            emit(&sender, CoreEvent::named("clipboard_write"));
        }
        assert!(queue.cancel.is_cancelled());
        emit(&sender, CoreEvent::named("closed"));
        let state = queue.state.lock().unwrap();
        assert!(state.queue.len() <= HERDR_EVENT_LIMIT);
        assert!(state.bytes <= HERDR_EVENT_BYTES);
        assert!(state.queue.iter().any(|v| v.kind == "host_key_changed"));
        assert!(
            state
                .queue
                .iter()
                .any(|v| v.kind == "error" && v.json.contains("overflow"))
        );
        assert!(state.queue.iter().any(|v| v.kind == "closed"));
    }

    #[test]
    fn herdr_event_budget_counts_reserved_string_capacity() {
        for kind in ["herdr_projection", "error"] {
            let (queue, _sender) = herdr_test_queue();
            let mut oversized = String::with_capacity(HERDR_EVENT_BYTES + 1);
            oversized.push_str("{}");
            queue.push(kind, oversized);
            let state = queue.state.lock().unwrap();
            assert!(queue.cancel.is_cancelled());
            assert!(state.bytes <= HERDR_EVENT_BYTES);
            assert!(
                state
                    .queue
                    .iter()
                    .any(|event| event.kind == "error" && event.json.contains("overflow"))
            );
            assert!(
                !state
                    .queue
                    .iter()
                    .any(|event| event.kind == "herdr_projection")
            );
        }
    }

    #[test]
    fn herdr_projection_wraps_bounded_serialized_payload_without_reparse() {
        let (sender, receiver) = std_mpsc::channel();
        let sender = EventSender::Ordinary(sender);
        assert!(emit_herdr_projection(
            &sender,
            br#"{"ready":true,"target_epoch":7}"#
        ));
        let json: serde_json::Value = serde_json::from_str(&receiver.try_recv().unwrap()).unwrap();
        assert_eq!(json["version"], API_VERSION);
        assert_eq!(json["type"], "herdr_projection");
        assert_eq!(json["payload"]["target_epoch"], 7);
        assert!(!emit_herdr_projection(&sender, &[0xff]));
        assert!(!emit_herdr_projection(
            &sender,
            &vec![b'x'; HERDR_EVENT_BYTES]
        ));
        assert!(receiver.try_recv().is_err());
    }

    #[test]
    fn herdr_event_stream_closes_after_last_sender_is_dropped() {
        let (queue, sender) = herdr_test_queue();
        let other = sender.clone();
        drop(sender);
        assert!(queue.poll(0).is_empty());
        drop(other);
        assert!(queue.poll(0).contains("event_stream_closed"));
    }

    #[test]
    fn adopted_tcp_socket_works_through_existing_async_stream_wrapper() {
        use std::io::{Read, Write};
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut peer, _) = listener.accept().unwrap();
            let mut buf = [0; 4];
            peer.read_exact(&mut buf).unwrap();
            assert_eq!(&buf, b"ping");
            peer.write_all(b"pong").unwrap();
        });
        let socket = std::net::TcpStream::connect(address).unwrap();
        let fd: OwnedFd = socket.into();
        runtime().unwrap().block_on(async {
            let stream = std::os::unix::net::UnixStream::from(fd);
            stream.set_nonblocking(true).unwrap();
            let mut stream = UnixStream::from_std(stream).unwrap();
            stream.write_all(b"ping").await.unwrap();
            let mut buf = [0; 4];
            stream.read_exact(&mut buf).await.unwrap();
            assert_eq!(&buf, b"pong");
        });
        server.join().unwrap();
    }

    #[test]
    fn invalid_request_consumes_the_owned_descriptor() {
        let (left, mut right) = std::os::unix::net::UnixStream::pair().unwrap();
        let raw = left.into_raw_fd();
        let owned = unsafe { OwnedFd::from_raw_fd(raw) };
        assert!(manager().start(owned, "{}", SessionKind::Shell).is_err());
        // The descriptor number may be reused by another test immediately;
        // observe the peer closing rather than probing /proc by numeric FD.
        right
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        assert_eq!(right.read(&mut [0; 1]).unwrap(), 0);
    }

    #[test]
    fn herdr_admission_closes_rejected_fd_and_cancellation_returns_capacity() {
        let sessions = SessionManager::new();
        let request =
            r#"{"host":"fixture.invalid","username":"fixture","authentication":{"type":"none"}}"#;
        let mut peers = Vec::new();
        let mut ids = Vec::new();
        for _ in 0..2 {
            let (local, peer) = std::os::unix::net::UnixStream::pair().unwrap();
            ids.push(
                sessions
                    .start(local.into(), request, SessionKind::Herdr)
                    .unwrap(),
            );
            peers.push(peer);
        }
        let (local, mut peer) = std::os::unix::net::UnixStream::pair().unwrap();
        assert!(matches!(
            sessions.start(local.into(), request, SessionKind::Herdr),
            Err(StartError::HerdrResourceLimit)
        ));
        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        assert_eq!(
            peer.read(&mut [0; 1]).unwrap(),
            0,
            "rejected descriptor remained open"
        );
        // A JNI poll/snapshot can still own the closing entry. Keep the
        // second attachment alive and reopen immediately, without retries.
        let in_flight = sessions.get(ids[0]).unwrap();
        sessions.release(ids[0]);
        let release = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(150));
            drop(in_flight);
        });
        let (local, peer) = std::os::unix::net::UnixStream::pair().unwrap();
        let replacement = sessions.start(local.into(), request, SessionKind::Herdr);
        release.join().unwrap();
        sessions.release(ids[1]);
        drop(peers);
        let replacement = replacement
            .expect("a closing Herdr slot must be reclaimed before admitting its replacement");
        sessions.release(replacement);
        drop(peer);
    }

    #[test]
    fn herdr_commands_reject_unbounded_and_irrelevant_payloads() {
        use std::io::Write;
        use std::os::fd::AsRawFd;
        assert!(matches!(
            parse_herdr_command(r#"{"type":"herdr_probe","nonce":"fixture"}"#),
            Some(Command::HerdrProbe { .. })
        ));
        assert!(parse_herdr_command(r#"{"type":"herdr_probe","nonce":""}"#).is_none());
        assert!(
            parse_herdr_command(&format!(
                r#"{{"type":"herdr_probe","nonce":"{}"}}"#,
                "x".repeat(129)
            ))
            .is_none()
        );
        assert!(matches!(
            parse_herdr_command(r#"{"type":"input","base64":"YQ==","herdr_target_epoch":3}"#),
            Some(Command::Input { .. })
        ));
        assert!(parse_herdr_command(r#"{"type":"input","base64":"YQ=="}"#).is_none());
        assert!(
            parse_herdr_command(r#"{"type":"input","base64":"YQ==","herdr_target_epoch":0}"#)
                .is_none()
        );
        assert!(
            parse_herdr_command(
                r#"{"type":"resize","columns":501,"rows":24,"cell_width":8,"cell_height":16}"#
            )
            .is_none()
        );
        assert!(
            parse_herdr_command(&format!(
                r#"{{"type":"input","herdr_target_epoch":1,"base64":"{}"}}"#,
                "A".repeat(HERDR_COMMAND_JSON_LIMIT)
            ))
            .is_none()
        );
        assert!(
            parse_herdr_command(r#"{"type":"herdr_attach","session_name":"../danger"}"#).is_none()
        );
        let (mut local, mut peer) = std::os::unix::net::UnixStream::pair().unwrap();
        // The shared Command deserializer adopts SFTP file descriptors. The
        // Herdr-only parser must reject that variant without touching the FD.
        let hostile = format!(
            r#"{{"type":"sftp_upload","request_id":"x","path":"x","local_fd":{}}}"#,
            local.as_raw_fd()
        );
        assert!(parse_herdr_command(&hostile).is_none());
        local.write_all(b"x").unwrap();
        let mut byte = [0];
        peer.read_exact(&mut byte).unwrap();
        assert_eq!(byte, *b"x");
    }

    #[test]
    fn herdr_command_queue_and_converters_are_nonblocking_and_bounded() {
        let (sender, _receiver) = mpsc::channel(HERDR_COMMAND_QUEUE_LIMIT);
        let (_event_sender, events) = std_mpsc::channel();
        let entry = SessionEntry {
            events: EventReceiver::Ordinary(Mutex::new(events)),
            commands: sender,
            terminal: Arc::new(Mutex::new(TerminalState::new(80, 24, None))),
            displayed_target_epoch: Arc::new(AtomicU64::new(0)),
            cancel: CancellationToken::new(),
            _herdr_permit: Some(crate::herdr_budget::isolated_test_permit()),
            herdr_command_active: AtomicBool::new(false),
            herdr_snapshot_active: AtomicBool::new(false),
        };
        let first = entry.begin_herdr_command().unwrap();
        assert!(entry.begin_herdr_command().is_none());
        drop(first);
        let _next = entry.begin_herdr_command().unwrap();
        assert!(
            entry.snapshot_bytes(0).is_empty(),
            "ordinary snapshot must not bypass the Herdr guard"
        );
        let snapshot = entry.begin_herdr_snapshot().unwrap();
        assert!(entry.begin_herdr_snapshot().is_none());
        drop(snapshot);
        assert!(entry.begin_herdr_snapshot().is_some());
        let valid = r#"{"type":"input","base64":"YQ==","herdr_target_epoch":3}"#;
        for _ in 0..HERDR_COMMAND_QUEUE_LIMIT {
            assert!(entry.send_herdr_json(valid));
        }
        assert!(
            !entry.send_herdr_json(valid),
            "a full command queue must reject immediately"
        );
    }

    #[test]
    fn herdr_close_wakes_pollers_before_transport_shutdown_finishes() {
        let (queue, _live_transport) = herdr_test_queue();
        let (commands, _receiver) = mpsc::channel(1);
        let entry = Arc::new(SessionEntry {
            cancel: queue.cancel.clone(),
            events: EventReceiver::Herdr(queue),
            commands,
            terminal: Arc::new(Mutex::new(TerminalState::new(80, 24, None))),
            displayed_target_epoch: Arc::new(AtomicU64::new(0)),
            _herdr_permit: Some(crate::herdr_budget::isolated_test_permit()),
            herdr_command_active: AtomicBool::new(false),
            herdr_snapshot_active: AtomicBool::new(false),
        });
        let (done, result) = std_mpsc::channel();
        let polling = entry.clone();
        let worker = std::thread::spawn(move || {
            done.send(polling.poll_event(30_000)).unwrap();
        });
        std::thread::sleep(Duration::from_millis(20));
        entry.close();
        assert!(
            result
                .recv_timeout(Duration::from_secs(1))
                .unwrap()
                .contains("event_stream_closed")
        );
        // Cancellation before a new poll must also avoid a 30-second wait.
        assert!(entry.poll_event(30_000).contains("event_stream_closed"));
        worker.join().unwrap();
    }
}
