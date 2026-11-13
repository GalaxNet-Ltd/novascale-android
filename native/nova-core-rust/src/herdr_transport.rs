// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0

//! Herdr runs on one authenticated SSH parent. Its binary bridge is an exec
//! channel without a PTY. Neither discovery output nor stderr enters the codec.
use crate::herdr::{Client, Output};
use crate::model::{Command, CoreEvent, StartRequest};
use crate::session::{
    EventSender, HostKeyHandler, SessionError, connect_authenticated, emit, emit_herdr_projection,
};
use crate::terminal::TerminalState;
use base64::Engine;
use russh::{ChannelMsg, Disconnect, client};
use std::os::fd::OwnedFd;
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicU64, Ordering},
};
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::time::timeout;
use tokio_util::sync::CancellationToken;

const MAX_EXEC_OUTPUT: usize = 64 * 1024;
const MAX_PREAMBLE: usize = 16 * 1024;
const MARKER: &[u8] = b"\x1eNOVA_HERDR_BRIDGE_V1\x1f";

// Only fixed local descriptions may cross the boundary; never forward
// peer-provided text or untrusted decoder details to logs or the UI.
fn protocol_error(error: String) -> SessionError {
    let message = match error.as_str() {
        "budget exceeded" | "collection too large" | "invalid or over-budget JSON" => {
            "Herdr message exceeds the decoding budget"
        }
        "Herdr surface memory limit exceeded" => "Herdr surface exceeds the memory budget",
        "Herdr render limit exceeded" => "Herdr display exceeds the render budget",
        "Herdr projection limit exceeded" => "Herdr metadata exceeds the event budget",
        "hyperlink rendering work too large" | "hyperlink too large" => {
            "Herdr hyperlink exceeds the work budget"
        }
        "invalid cell" | "Invalid Herdr cell" => "Herdr sent invalid terminal cell data",
        "Invalid Herdr patch geometry" => "Herdr sent an invalid patch geometry",
        "Invalid Herdr patch pane" => "Herdr sent an unknown patch pane",
        "Invalid Herdr patch cursor" => "Herdr sent an invalid patch cursor",
        "Herdr patch baseline mismatch" => "Herdr patch baseline does not match",
        "Invalid Herdr patch" => "Herdr sent an invalid surface patch",
        "Invalid Herdr frame" | "invalid value" | "invalid string" | "truncated collection" => {
            "Herdr sent an invalid encoded frame"
        }
        _ => return SessionError::HerdrProtocol,
    };
    SessionError::HerdrProtocolDetail(message)
}
const MAX_INPUT_BYTES: usize = 256 * 1024;
const MAX_ENCODED_INPUT: usize = MAX_INPUT_BYTES.div_ceil(3) * 4;

fn accepts_input(ready: bool, supplied: Option<u64>, current: Option<u64>) -> bool {
    ready && supplied.is_some_and(|epoch| epoch != 0) && supplied == current
}

fn decode_input(encoded: &str) -> Option<Vec<u8>> {
    if encoded.len() > MAX_ENCODED_INPUT {
        return None;
    }
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(encoded)
        .ok()?;
    (bytes.len() <= MAX_INPUT_BYTES).then_some(bytes)
}

// Inspect a bounded prefix before retaining startup output. A valid first read
// may contain both the marker and a large protocol frame, which must survive.
fn consume_preamble(pending: &mut Vec<u8>, data: &[u8]) -> Result<Option<Vec<u8>>, SessionError> {
    let limit = MAX_PREAMBLE + MARKER.len();
    let count = data.len().min(limit.saturating_sub(pending.len()));
    pending.extend_from_slice(&data[..count]);
    if let Some(pos) = pending.windows(MARKER.len()).position(|w| w == MARKER) {
        if pos > MAX_PREAMBLE {
            return Err(SessionError::HerdrProtocol);
        }
        let mut payload = pending.split_off(pos + MARKER.len());
        payload.extend_from_slice(&data[count..]);
        pending.clear();
        return Ok(Some(payload));
    }
    if pending.len() >= limit {
        return Err(SessionError::HerdrProtocol);
    }
    Ok(None)
}

fn quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}
fn valid_name(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 64
        && s != "."
        && s != ".."
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
}
fn valid_path(s: &str) -> bool {
    !s.is_empty() && s.len() < 4096 && !s.chars().any(char::is_control)
}
fn discovery(executable: &str) -> Option<String> {
    if executable.chars().any(char::is_control) || executable.len() >= 4096 {
        return None;
    }
    let lookup = if executable.is_empty() {
        "p=$(command -v herdr 2>/dev/null || true); if [ -z \"$p\" ]; then for c in \"$HOME/.local/bin/herdr\" /opt/homebrew/bin/herdr /usr/local/bin/herdr; do if [ -x \"$c\" ]; then p=$c; break; fi; done; fi".to_owned()
    } else if let Some(rest) = executable.strip_prefix("~/") {
        format!("p=\"$HOME\"/{}", quote(rest))
    } else {
        format!(
            "p={}; case \"$p\" in */*) ;; *) p=$(command -v \"$p\" 2>/dev/null || true);; esac",
            quote(executable)
        )
    };
    Some(format!(
        "LC_ALL=C; export LC_ALL; case $(uname -s) in Linux|Darwin) ;; *) exit 65;; esac; {lookup}; [ -n \"$p\" ] && [ -x \"$p\" ] && [ \"${{#p}}\" -lt 4096 ] || exit 127; case \"$p\" in *'\n'*|*'\r'*) exit 127;; esac; printf '\\036NOVA_EXEC_V1\\037%s\\036NOVA_SESSIONS_V1\\037' \"$p\"; \"$p\" session list --json"
    ))
}

async fn exec_collect(
    session: &client::Handle<HostKeyHandler>,
    cmd: String,
    cancel: &CancellationToken,
) -> Result<Vec<u8>, SessionError> {
    let task = async {
        let mut ch = session
            .channel_open_session()
            .await
            .map_err(|_| SessionError::Channel)?;
        ch.exec(true, cmd)
            .await
            .map_err(|_| SessionError::Channel)?;
        let mut output = Vec::new();
        let mut status = None;
        loop {
            match ch.wait().await {
                Some(ChannelMsg::Data { data }) => {
                    if output.len().saturating_add(data.len()) > MAX_EXEC_OUTPUT {
                        return Err(SessionError::HerdrProtocol);
                    }
                    output.extend_from_slice(&data);
                }
                Some(ChannelMsg::ExtendedData { .. }) => {} // untrusted diagnostics are never logged
                Some(ChannelMsg::ExitStatus { exit_status }) => status = Some(exit_status),
                Some(ChannelMsg::Eof) => {}
                None | Some(ChannelMsg::Close) => break,
                _ => {}
            }
        }
        if status != Some(0) {
            return Err(SessionError::HerdrDiscovery);
        }
        Ok(output)
    };
    tokio::select! {
        _ = cancel.cancelled() => Err(SessionError::Channel),
        result = timeout(Duration::from_secs(15), task) => result.map_err(|_| SessionError::HerdrDiscovery)?,
    }
}

fn parse_discovery(bytes: &[u8]) -> Result<(String, serde_json::Value), SessionError> {
    let out = std::str::from_utf8(bytes).map_err(|_| SessionError::HerdrDiscovery)?;
    // The delimiters contain control bytes forbidden in executable paths and
    // escaped by JSON, so marker-like printable path/JSON text cannot replace
    // the selected executable. Startup output is bounded separately.
    let executable_marker = "\x1eNOVA_EXEC_V1\x1f";
    let sessions_marker = "\x1eNOVA_SESSIONS_V1\x1f";
    let marker = out
        .find(executable_marker)
        .ok_or(SessionError::HerdrDiscovery)?;
    if marker > MAX_PREAMBLE {
        return Err(SessionError::HerdrDiscovery);
    }
    let start = marker + executable_marker.len();
    let split = out[start..]
        .find(sessions_marker)
        .ok_or(SessionError::HerdrDiscovery)?
        + start;
    let path = &out[start..split];
    if !valid_path(path) {
        return Err(SessionError::HerdrDiscovery);
    }
    let value: serde_json::Value = serde_json::from_str(&out[split + sessions_marker.len()..])
        .map_err(|_| SessionError::HerdrDiscovery)?;
    let sessions = value
        .get("sessions")
        .and_then(|x| x.as_array())
        .ok_or(SessionError::HerdrDiscovery)?;
    if sessions.len() > 1024
        || sessions.iter().any(|x| {
            !x.get("name")
                .and_then(|x| x.as_str())
                .is_some_and(valid_name)
                || !x.get("running").is_some_and(|x| x.is_boolean())
        })
    {
        return Err(SessionError::HerdrDiscovery);
    }
    let mut names = std::collections::HashSet::new();
    if sessions
        .iter()
        .any(|session| !names.insert(session["name"].as_str().unwrap()))
    {
        return Err(SessionError::HerdrDiscovery);
    }
    Ok((path.to_owned(), value))
}

fn compatibility(path: &str, name: &str) -> String {
    let executable = quote(path);
    let name = quote(name);
    format!(
        "printf '\\036NOVA_CLIENT_V1\\037'; {executable} --session {name} status client --json && printf '\\036NOVA_SERVER_V1\\037' && {executable} --session {name} status server --json"
    )
}
fn compatible(bytes: &[u8]) -> bool {
    let Ok(s) = std::str::from_utf8(bytes) else {
        return false;
    };
    let client_marker = "\x1eNOVA_CLIENT_V1\x1f";
    let server_marker = "\x1eNOVA_SERVER_V1\x1f";
    let Some(a) = s.find(client_marker) else {
        return false;
    };
    if a > MAX_PREAMBLE {
        return false;
    }
    let rest = &s[a + client_marker.len()..];
    let Some(i) = rest.find(server_marker) else {
        return false;
    };
    let Ok(client) = serde_json::from_str::<serde_json::Value>(&rest[..i]) else {
        return false;
    };
    let Ok(server) = serde_json::from_str::<serde_json::Value>(&rest[i + server_marker.len()..])
    else {
        return false;
    };
    client["endpoint_protocol_generation"].as_u64() == Some(1)
        && (server["running"].as_bool() == Some(false)
            || (server["running"].as_bool() == Some(true)
                && server["capabilities"]["endpoint_protocol_generation"].as_u64() == Some(1)))
}

fn publish(
    output: Output,
    terminal: &Arc<Mutex<TerminalState>>,
    events: &EventSender,
    displayed_target_epoch: &Arc<AtomicU64>,
    was_ready: bool,
) -> (Vec<u8>, Option<bool>, bool) {
    let ready = output.ready;
    let new_epoch = match ready {
        Some(true) => Some(output.target_epoch.unwrap_or(0)),
        Some(false) => Some(0),
        None => None,
    };
    if !output.state.is_empty() {
        if ready.is_none() || !emit_herdr_projection(events, &output.state) {
            let _term = terminal.lock().expect("terminal state poisoned");
            displayed_target_epoch.store(0, Ordering::Relaxed);
            let mut error = CoreEvent::named("error");
            error.category = Some("protocol");
            error.message = Some("Herdr projection exceeds the event limit");
            emit(events, error);
            return (Vec::new(), Some(false), true);
        }
    }
    if !output.ansi.is_empty() {
        let mut term = terminal.lock().expect("terminal state poisoned");
        let revision = term.feed(&output.ansi);
        // Publish the epoch only alongside the matching pixels while holding
        // the same lock used by the atomic snapshot+epoch JNI read.
        if let Some(epoch) = new_epoch {
            displayed_target_epoch.store(epoch, Ordering::Relaxed);
        }
        // Ghostty-generated replies belong to the local display, not the binary bridge.
        let _ = term.drain_pty_writes();
        let _ = term.take_clipboard();
        let mut event = CoreEvent::named("snapshot_changed");
        event.revision = Some(revision);
        emit(events, event);
    } else if ready == Some(false) {
        let _term = terminal.lock().expect("terminal state poisoned");
        displayed_target_epoch.store(0, Ordering::Relaxed);
    }
    if was_ready && ready != Some(false) && !output.clipboard.is_empty() {
        if let Ok(text) = std::str::from_utf8(&output.clipboard) {
            let payload = serde_json::json!({"text": text});
            let mut event = CoreEvent::named("clipboard_write");
            event.payload = Some(&payload);
            emit(events, event);
        }
    }
    // Re-enable native controls/IME only after the projection and matching
    // displayed frame have been published. A completion must not expose the
    // previous window's input target between events.
    if let Some(result) = &output.action_result {
        let payload = serde_json::to_value(result).expect("bounded action result");
        let mut event = CoreEvent::named("herdr_action_result");
        event.payload = Some(&payload);
        emit(events, event);
    }
    if let Some((nonce, healthy)) = &output.health_result {
        let payload = serde_json::json!({"nonce": nonce, "healthy": healthy});
        let mut event = CoreEvent::named("herdr_health");
        event.payload = Some(&payload);
        emit(events, event);
    }
    (output.outbound, ready, output.detached)
}

pub async fn run_herdr(
    fd: OwnedFd,
    mut request: StartRequest,
    events: EventSender,
    mut commands: mpsc::Receiver<Command>,
    terminal: Arc<Mutex<TerminalState>>,
    displayed_target_epoch: Arc<AtomicU64>,
    cancel: CancellationToken,
) -> Result<(), SessionError> {
    let session = tokio::select! { _ = cancel.cancelled() => return Ok(()), result = connect_authenticated(fd, &mut request, &events) => result? };
    let cmd = discovery(&request.herdr_executable).ok_or(SessionError::HerdrDiscovery)?;
    let (path, sessions) = parse_discovery(&exec_collect(&session, cmd, &cancel).await?)?;
    let mut event = CoreEvent::named("herdr_sessions");
    event.payload = Some(&sessions);
    emit(&events, event);
    let name = if let Some(name) = request.herdr_session_name.take() {
        name
    } else {
        loop {
            tokio::select! {
                _ = cancel.cancelled() => return Ok(()),
                command = commands.recv() => match command {
                    Some(Command::HerdrAttach { session_name }) => break session_name,
                    Some(Command::HerdrDisplay { improve_text_contrast, focused }) => {
                        request.herdr_improve_text_contrast = improve_text_contrast;
                        request.herdr_focused = focused;
                    }
                    Some(Command::TerminalTheme { foreground, background, cursor, ansi }) => {
                        request.terminal_theme = Some(crate::model::TerminalThemeConfig { foreground, background, cursor, ansi });
                    }
                    None | Some(Command::Close) => return Ok(()), _ => {}
                }
            }
        }
    };
    if !valid_name(&name) {
        return Err(SessionError::HerdrDiscovery);
    }
    if !compatible(&exec_collect(&session, compatibility(&path, &name), &cancel).await?) {
        return Err(SessionError::HerdrCompatibility);
    }
    let mut channel = tokio::select! {
        _ = cancel.cancelled() => return Ok(()),
        result = timeout(Duration::from_secs(15), session.channel_open_session()) =>
            result.map_err(|_| SessionError::Channel)?.map_err(|_| SessionError::Channel)?,
    };
    let bridge = format!(
        "printf '\\036NOVA_HERDR_BRIDGE_V1\\037'; exec {} --session {} remote-client-bridge",
        quote(&path),
        quote(&name)
    );
    tokio::select! {
        _ = cancel.cancelled() => return Ok(()),
        result = timeout(Duration::from_secs(15), channel.exec(true, bridge)) =>
            result.map_err(|_| SessionError::Channel)?.map_err(|_| SessionError::Channel)?,
    }
    // The Herdr display is a local alternate screen, with encoded semantic
    // input, mouse and focus reports interpreted by the Herdr client.
    {
        let mut term = terminal.lock().expect("terminal state poisoned");
        term.feed(b"\x1bc\x1b[?1049h\x1b[?7l\x1b[?2004h\x1b[?1002h\x1b[?1006h\x1b[?1004h\x1b[>31u");
        let _ = term.drain_pty_writes();
    }
    let mut codec = Client::new(request.columns as u16, request.rows as u16);
    codec
        .set_display(request.herdr_improve_text_contrast, request.herdr_focused)
        .map_err(protocol_error)?;
    if let Some(theme) = &request.terminal_theme {
        terminal
            .lock()
            .expect("terminal state poisoned")
            .set_theme(theme);
        codec.set_theme(theme.clone()).map_err(protocol_error)?;
    }
    let mut preamble = Vec::new();
    let mut marker_found = false;
    let mut ready = false;
    let mut ever_ready = false;
    let (hello, _, _) = publish(
        codec.hello().map_err(|_| SessionError::HerdrProtocol)?,
        &terminal,
        &events,
        &displayed_target_epoch,
        false,
    );
    tokio::select! {
        _ = cancel.cancelled() => return Ok(()),
        result = timeout(Duration::from_secs(15), channel.data(&hello[..])) =>
            result.map_err(|_| SessionError::Channel)?.map_err(|_| SessionError::Channel)?,
    }
    let first_frame = tokio::time::sleep(Duration::from_secs(15));
    tokio::pin!(first_frame);
    let input_flush = tokio::time::sleep(Duration::from_secs(3600));
    tokio::pin!(input_flush);
    let mut flush_pending = false;
    let mut action_timer = tokio::time::interval(Duration::from_millis(250));
    'session: loop {
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
            _ = action_timer.tick(), if codec.action_pending() => {
                let (_, next_ready, _) = publish(codec.expire_action().map_err(protocol_error)?, &terminal, &events, &displayed_target_epoch, ready);
                if let Some(value) = next_ready { ready = value; }
            }
            _ = &mut first_frame, if !ever_ready => return Err(SessionError::HerdrCompatibility),
            _ = &mut input_flush, if flush_pending => {
                flush_pending = false;
                if ready {
                    let (bytes, next_ready, detached) = publish(codec.flush().map_err(|_| SessionError::HerdrProtocol)?, &terminal, &events, &displayed_target_epoch, ready);
                    if let Some(next_ready) = next_ready { ready = next_ready; }
                    if !bytes.is_empty() {
                        tokio::select! {
                            _ = cancel.cancelled() => break,
                            result = timeout(Duration::from_secs(15), channel.data(&bytes[..])) =>
                                result.map_err(|_| SessionError::Channel)?.map_err(|_| SessionError::Channel)?,
                        }
                    }
                    if detached { break; }
                }
            }
            command = commands.recv() => {
                let output = match command {
                    Some(Command::HerdrResume) => codec.resume_viewport(),
                    Some(Command::HerdrProbe { nonce }) => codec.probe(nonce),
                    Some(Command::HerdrDisplay { improve_text_contrast, focused }) => codec.set_display(improve_text_contrast, focused),
                    Some(Command::TerminalTheme { foreground, background, cursor, ansi }) => {
                        let theme = crate::model::TerminalThemeConfig { foreground, background, cursor, ansi };
                        terminal.lock().expect("terminal state poisoned").set_theme(&theme);
                        codec.set_theme(theme)
                    }
                    Some(Command::HerdrAction { request_id, boot_id, action }) => codec.action(request_id, &boot_id, action),
                    None | Some(Command::Close) => { if let Ok(out) = codec.detach() { let (bytes, _, _) = publish(out, &terminal, &events, &displayed_target_epoch, ready); let _ = timeout(Duration::from_millis(500), channel.data(&bytes[..])).await; } break; }
                    Some(Command::Input { base64, herdr_target_epoch }) if accepts_input(ready, herdr_target_epoch, codec.target_epoch()) => { let Some(bytes) = decode_input(&base64) else { continue; }; flush_pending = true; input_flush.as_mut().reset(tokio::time::Instant::now() + Duration::from_millis(20)); codec.input(&bytes) }
                    Some(Command::Input { herdr_target_epoch, .. }) => {
                        // Report only numeric target state, never the discarded
                        // input. A stale command must not be retargeted.
                        let payload = serde_json::json!({"submittedEpoch": herdr_target_epoch, "currentEpoch": codec.target_epoch(), "ready": ready});
                        let mut event = CoreEvent::named("herdr_input_stale");
                        event.payload = Some(&payload);
                        emit(&events, event);
                        continue;
                    }
                    Some(Command::Resize { columns, rows, cell_width, cell_height }) if (20..=500).contains(&columns) && (5..=300).contains(&rows) => { { let mut term = terminal.lock().expect("terminal state poisoned"); term.resize_with_metrics(columns, rows, cell_width.clamp(1,512), cell_height.clamp(1,512)); displayed_target_epoch.store(0, Ordering::Relaxed); } ready = false; codec.resize(columns as u16, rows as u16) }
                    _ => continue,
                }.map_err(|_| SessionError::HerdrProtocol)?;
                let (bytes, next_ready, detached) = publish(output, &terminal, &events, &displayed_target_epoch, ready);
                if let Some(next_ready) = next_ready { ready = next_ready; }
                if !bytes.is_empty() {
                        tokio::select! {
                            _ = cancel.cancelled() => break,
                            result = timeout(Duration::from_secs(15), channel.data(&bytes[..])) =>
                                result.map_err(|_| SessionError::Channel)?.map_err(|_| SessionError::Channel)?,
                        }
                    }
                if detached { break; }
            },
            msg = channel.wait() => match msg {
                Some(ChannelMsg::Data { data }) => {
                    let bytes = if marker_found { data.to_vec() } else {
                        if let Some(payload) = consume_preamble(&mut preamble, &data)? {
                            marker_found = true; payload
                        } else { continue; }
                    };
                    // Publish each completed frame before parsing the next.
                    // An SSH read can contain multiple independently large
                    // renders; accumulating them would exceed the bounded
                    // output budget and desynchronize the encoder baseline.
                    let mut offset = 0;
                    while offset < bytes.len() {
                        let (output, consumed) = codec.receive_one(&bytes[offset..])
                            .map_err(protocol_error)?;
                        if consumed == 0 || consumed > bytes.len() - offset {
                            return Err(SessionError::HerdrProtocol);
                        }
                        offset += consumed;
                        let (outbound, now_ready, detached) = publish(output, &terminal, &events, &displayed_target_epoch, ready);
                        if now_ready == Some(true) && !ever_ready { emit(&events, CoreEvent::named("ready")); ever_ready = true; }
                        if let Some(now_ready) = now_ready { ready = now_ready; }
                        if !outbound.is_empty() {
                            tokio::select! {
                                _ = cancel.cancelled() => break 'session,
                                result = timeout(Duration::from_secs(15), channel.data(&outbound[..])) =>
                                    result.map_err(|_| SessionError::Channel)?.map_err(|_| SessionError::Channel)?,
                            }
                        }
                        if detached { break 'session; }
                    }
                }
                Some(ChannelMsg::ExtendedData { .. }) => {},
                None | Some(ChannelMsg::Close) | Some(ChannelMsg::ExitStatus { .. }) => break,
                _ => {}
            }
        }
    }
    let _ = timeout(Duration::from_secs(2), async {
        let _ = channel.eof().await;
        let _ = channel.close().await;
        let _ = session
            .disconnect(Disconnect::ByApplication, "", "en")
            .await;
    })
    .await;
    emit(&events, CoreEvent::named("closed"));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn protocol_diagnostics_never_forward_untrusted_text() {
        assert!(matches!(
            protocol_error("Herdr render limit exceeded".into()),
            SessionError::HerdrProtocolDetail("Herdr display exceeds the render budget")
        ));
        assert!(matches!(
            protocol_error("private remote content".into()),
            SessionError::HerdrProtocol
        ));
    }
    #[test]
    fn queued_input_is_rejected_after_target_or_coherence_changes() {
        assert!(accepts_input(true, Some(7), Some(7)));
        assert!(!accepts_input(false, Some(7), Some(7)));
        assert!(!accepts_input(true, None, Some(7)));
        assert!(!accepts_input(true, Some(7), None));
        assert!(!accepts_input(true, Some(7), Some(8)));
        assert!(!accepts_input(true, Some(0), Some(0)));
    }
    #[test]
    fn input_decode_is_bounded_before_allocation() {
        use base64::Engine;
        let allowed = base64::engine::general_purpose::STANDARD.encode(vec![0x41; MAX_INPUT_BYTES]);
        assert_eq!(decode_input(&allowed).unwrap().len(), MAX_INPUT_BYTES);
        let too_large =
            base64::engine::general_purpose::STANDARD.encode(vec![0x41; MAX_INPUT_BYTES + 1]);
        assert!(decode_input(&too_large).is_none());
        assert!(decode_input(&"A".repeat(MAX_ENCODED_INPUT + 4)).is_none());
    }
    #[test]
    fn fragmented_preamble_preserves_large_coalesced_frame() {
        let mut pending = Vec::new();
        assert!(
            consume_preamble(&mut pending, b"banner\x1eNOVA_HERDR_")
                .unwrap()
                .is_none()
        );
        let mut rest = b"BRIDGE_V1\x1f".to_vec();
        rest.extend(vec![0x42; MAX_PREAMBLE * 2]);
        let payload = consume_preamble(&mut pending, &rest).unwrap().unwrap();
        assert_eq!(payload, vec![0x42; MAX_PREAMBLE * 2]);
        assert!(pending.is_empty());
        assert!(consume_preamble(&mut pending, &vec![0x41; MAX_PREAMBLE + MARKER.len()]).is_err());
    }
    #[test]
    fn clipboard_is_suppressed_before_ready_and_after_invalidation() {
        let terminal = Arc::new(Mutex::new(TerminalState::new(80, 24, None)));
        let (sender, receiver) = std::sync::mpsc::channel();
        let events = EventSender::Ordinary(sender);
        let displayed_target_epoch = Arc::new(AtomicU64::new(0));
        let output = || Output {
            clipboard: b"secret".to_vec(),
            ..Output::default()
        };
        publish(output(), &terminal, &events, &displayed_target_epoch, false);
        assert!(receiver.try_recv().is_err());
        publish(
            Output {
                state: br#"{"ready":false}"#.to_vec(),
                ready: Some(false),
                ..output()
            },
            &terminal,
            &events,
            &displayed_target_epoch,
            true,
        );
        // The projection is emitted, but there is no clipboard event.
        assert!(receiver.try_recv().unwrap().contains("herdr_projection"));
        assert!(receiver.try_recv().is_err());
        publish(output(), &terminal, &events, &displayed_target_epoch, true);
        assert!(receiver.try_recv().unwrap().contains("clipboard_write"));
    }
    #[test]
    fn displayed_epoch_changes_only_with_coherent_render_and_invalidates() {
        let terminal = Arc::new(Mutex::new(TerminalState::new(80, 24, None)));
        let (sender, _receiver) = std::sync::mpsc::channel();
        let events = EventSender::Ordinary(sender);
        let epoch = Arc::new(AtomicU64::new(0));
        let frame = |state: &[u8], ansi: &[u8]| {
            let metadata: serde_json::Value = serde_json::from_slice(state).unwrap();
            Output {
                state: state.to_vec(),
                ansi: ansi.to_vec(),
                ready: metadata["ready"].as_bool(),
                target_epoch: metadata["target_epoch"].as_u64(),
                ..Output::default()
            }
        };
        publish(
            frame(br#"{"ready":true,"target_epoch":42}"#, b"frame-a"),
            &terminal,
            &events,
            &epoch,
            false,
        );
        assert_eq!(epoch.load(Ordering::Relaxed), 42);
        // Merely seeing the next projection cannot assign its target to old pixels.
        publish(
            frame(br#"{"ready":true,"target_epoch":43}"#, b""),
            &terminal,
            &events,
            &epoch,
            true,
        );
        assert_eq!(epoch.load(Ordering::Relaxed), 42);
        publish(
            frame(br#"{"ready":false,"target_epoch":null}"#, b""),
            &terminal,
            &events,
            &epoch,
            true,
        );
        assert_eq!(epoch.load(Ordering::Relaxed), 0);
        publish(
            frame(br#"{"ready":true}"#, b"frame-b"),
            &terminal,
            &events,
            &epoch,
            false,
        );
        assert_eq!(epoch.load(Ordering::Relaxed), 0);
    }

    #[test]
    fn navigation_completion_follows_projection_and_display_publication() {
        let terminal = Arc::new(Mutex::new(TerminalState::new(80, 24, None)));
        let (sender, receiver) = std::sync::mpsc::channel();
        let events = EventSender::Ordinary(sender);
        let epoch = Arc::new(AtomicU64::new(0));
        let mut output = Output {
            state: br#"{"ready":true,"target_epoch":42}"#.to_vec(),
            ready: Some(true),
            target_epoch: Some(42),
            ansi: b"new window".to_vec(),
            ..Output::default()
        };
        output.action_result = Some(crate::herdr::ActionResult {
            request_id: 1,
            code: "ok",
            message: None,
        });
        publish(output, &terminal, &events, &epoch, false);
        let events: Vec<serde_json::Value> = receiver
            .try_iter()
            .map(|s| serde_json::from_str(&s).unwrap())
            .collect();
        let kinds: Vec<_> = events.iter().map(|v| v["type"].as_str().unwrap()).collect();
        assert_eq!(
            kinds,
            [
                "herdr_projection",
                "snapshot_changed",
                "herdr_action_result"
            ]
        );
        assert_eq!(epoch.load(Ordering::Relaxed), 42);
    }
    #[test]
    fn names_and_shell_quoting() {
        assert!(valid_name("work-1.abc"));
        assert!(!valid_name("../x"));
        assert!(!valid_name(".."));
        assert_eq!(quote("a'b"), "'a'\\''b'");
        assert!(discovery("bad\nname").is_none());
    }
    #[test]
    fn discovery_markers_do_not_confuse_user_text() {
        let payload = b"banner\x1eNOVA_EXEC_V1\x1f/tmp/NOVA_EXEC:tool\x1eNOVA_SESSIONS_V1\x1f{\"sessions\":[{\"name\":\"NOVA_EXEC:fake\",\"running\":true}]}";
        // Colons are forbidden in actual session names, but cannot alter the path.
        assert!(parse_discovery(payload).is_err());
        let payload = b"banner\x1eNOVA_EXEC_V1\x1f/tmp/NOVA_EXEC:tool\x1eNOVA_SESSIONS_V1\x1f{\"sessions\":[{\"name\":\"work\",\"running\":true,\"extra\":\"NOVA_EXEC:fake\"}]}";
        assert_eq!(parse_discovery(payload).unwrap().0, "/tmp/NOVA_EXEC:tool");
    }
    #[test]
    fn hostile_executable_path_is_a_single_shell_argument() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let executable = dir.path().join("herdr';touch PWNED;'");
        std::fs::write(&executable, "#!/bin/sh\nprintf '%s' '{\"sessions\":[]}'\n").unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        let path = executable.to_str().unwrap();
        let result = std::process::Command::new("sh")
            .arg("-c")
            .arg(discovery(path).unwrap())
            .current_dir(dir.path())
            .output()
            .unwrap();
        assert!(result.status.success());
        assert_eq!(parse_discovery(&result.stdout).unwrap().0, path);
        assert!(!dir.path().join("PWNED").exists());
    }
    #[test]
    fn parses_compatible_running_and_stopped_server() {
        assert!(compatible(b"\x1eNOVA_CLIENT_V1\x1f{\"endpoint_protocol_generation\":1}\x1eNOVA_SERVER_V1\x1f{\"running\":true,\"capabilities\":{\"endpoint_protocol_generation\":1}}"));
        assert!(!compatible(b"\x1eNOVA_CLIENT_V1\x1f{\"endpoint_protocol_generation\":1}\x1eNOVA_SERVER_V1\x1f{\"running\":true,\"capabilities\":{\"endpoint_protocol_generation\":2}}"));
    }
}
