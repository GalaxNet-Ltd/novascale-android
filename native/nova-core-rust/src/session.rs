// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: GPL-3.0-only

use crate::model::{API_VERSION, Authentication, Command, CoreEvent, StartRequest};
use crate::sftp::run_sftp;
use crate::terminal::TerminalState;
use base64::Engine;
use russh::client;
use russh::keys::{PrivateKeyWithHashAlg, decode_secret_key, ssh_key};
use russh::{ChannelMsg, Disconnect};
use serde::Serialize;
use std::collections::HashMap;
use std::os::fd::OwnedFd;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, mpsc as std_mpsc};
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
}

#[derive(Debug, Error)]
pub enum StartError {
    #[error("invalid request")]
    InvalidRequest,
    #[error("native runtime unavailable")]
    RuntimeUnavailable,
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
    #[error("session channel failed")]
    Channel,
}

pub struct SessionEntry {
    events: Mutex<std_mpsc::Receiver<String>>,
    commands: mpsc::Sender<Command>,
    terminal: Arc<Mutex<TerminalState>>,
    cancel: CancellationToken,
}

impl SessionEntry {
    pub fn poll_event(&self, timeout_ms: u64) -> String {
        let wait = Duration::from_millis(timeout_ms.min(30_000));
        match self
            .events
            .lock()
            .expect("event receiver poisoned")
            .recv_timeout(wait)
        {
            Ok(event) => event,
            Err(std_mpsc::RecvTimeoutError::Timeout) => String::new(),
            Err(std_mpsc::RecvTimeoutError::Disconnected) => {
                event_json(CoreEvent::named("event_stream_closed"))
            }
        }
    }

    pub fn send_command(&self, command: Command) -> bool {
        self.commands.blocking_send(command).is_ok()
    }

    pub fn snapshot_bytes(&self, image_revision: u64) -> Vec<u8> {
        self.terminal
            .lock()
            .expect("terminal state poisoned")
            .snapshot_bytes_since(image_revision)
    }

    pub fn close(&self) {
        self.cancel.cancel();
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

        let (event_sender, event_receiver) = std_mpsc::channel();
        let (command_sender, command_receiver) = mpsc::channel(64);
        let terminal = Arc::new(Mutex::new(TerminalState::new(
            request.columns,
            request.rows,
            request.terminal_theme.as_ref(),
        )));
        let cancel = CancellationToken::new();
        let entry = Arc::new(SessionEntry {
            events: Mutex::new(event_receiver),
            commands: command_sender,
            terminal: terminal.clone(),
            cancel: cancel.clone(),
        });
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.sessions
            .lock()
            .expect("session map poisoned")
            .insert(id, entry);

        runtime()
            .map_err(|_| StartError::RuntimeUnavailable)?
            .spawn(async move {
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
    events: std_mpsc::Sender<String>,
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
    events: &std_mpsc::Sender<String>,
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
    events: std_mpsc::Sender<String>,
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
        tokio::select! {
            _ = cancel.cancelled() => break,
            Some(command) = commands.recv() => match command {
                Command::Input { base64 } => {
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
                        channel.window_change(columns, rows, columns * cell_width.clamp(1, 512), rows * cell_height.clamp(1, 512)).await
                            .map_err(|_| SessionError::Channel)?;
                        let replies = {
                            let mut terminal = terminal.lock().expect("terminal state poisoned");
                            terminal.resize_with_metrics(columns, rows, cell_width.clamp(1, 512), cell_height.clamp(1, 512));
                            terminal.drain_pty_writes()
                        };
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
            Self::Channel => "protocol",
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
        }
    }
}

pub fn emit(sender: &std_mpsc::Sender<String>, event: CoreEvent<'_>) {
    let _ = sender.send(event_json(event));
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
    use std::os::fd::{FromRawFd, IntoRawFd};
    use std::path::Path;

    #[test]
    fn invalid_request_consumes_the_owned_descriptor() {
        let (left, right) = std::os::unix::net::UnixStream::pair().unwrap();
        let raw = left.into_raw_fd();
        let owned = unsafe { OwnedFd::from_raw_fd(raw) };
        assert!(manager().start(owned, "{}", SessionKind::Shell).is_err());
        assert!(!Path::new(&format!("/proc/self/fd/{raw}")).exists());
        drop(right);
    }
}
