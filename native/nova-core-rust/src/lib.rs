// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: Apache-2.0

mod herdr;
mod herdr_budget;
mod herdr_transport;
mod model;
mod session;
mod sftp;
mod terminal;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong};
use jni::{Env, NativeMethod, jni_sig, jni_str, native_method};
use model::{API_VERSION, Command};
use session::{SessionKind, manager, start_response};
use std::os::fd::{FromRawFd, OwnedFd};

const _NATIVE_BUILD_INFO: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_build_info() -> JString,
};

const _NATIVE_START_SSH: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_start_ssh(fd: jint, request: JString) -> JString,
};

const _NATIVE_START_HERDR: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_start_herdr(fd: jint, request: JString) -> JString,
};

const _NATIVE_START_SFTP: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_start_sftp(fd: jint, request: JString) -> JString,
};

const _NATIVE_POLL_EVENT: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_poll_event(session_id: jlong, timeout_ms: jint) -> JString,
};

const _NATIVE_SEND_COMMAND: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_send_command(session_id: jlong, command: JString) -> jboolean,
};

const _NATIVE_SNAPSHOT: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_snapshot(session_id: jlong) -> [jbyte],
};
const _NATIVE_SNAPSHOT_IMAGES: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_snapshot_with_images(session_id: jlong, image_revision: jlong) -> [jbyte],
};
const _NATIVE_HERDR_SNAPSHOT: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_herdr_snapshot_with_epoch(session_id: jlong, image_revision: jlong) -> [jbyte],
};
const _NATIVE_FRAME: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_frame(session_id: jlong, frame_revision: jlong, image_revision: jlong) -> [jbyte],
};
const _NATIVE_HERDR_FRAME: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_herdr_frame_with_epoch(session_id: jlong, frame_revision: jlong, image_revision: jlong) -> [jbyte],
};
const _NATIVE_GRAPHICS_PROBE: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_terminal_graphics_probe(commands: JString, scroll: jint) -> [jbyte],
};

const _NATIVE_TERMINAL_PROBE: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_terminal_probe() -> [jbyte],
};

const _NATIVE_RELEASE: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_release(session_id: jlong),
};

const _NATIVE_REQUEST_CLOSE: NativeMethod = native_method! {
    java_type = "cc.galaxnet.novascale.nativecore.NativeCore",
    static extern fn native_request_close(session_id: jlong),
};

fn native_build_info<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
) -> Result<JString<'local>, jni::errors::Error> {
    JString::from_str(
        env,
        format!(
            r#"{{"apiVersion":{},"rustCore":"0.1.0","russh":"0.62.2","russhSftp":"2.3.0","ghostty":"vt-1.0.0","ghosttyCommit":"0fa3d577a5614150ff8e2c152de5fb9d5c6d2dec","snapshotAbi":2,"imageSnapshotAbi":1,"crypto":"ring+rsa"}}"#,
            API_VERSION
        ),
    )
}

fn native_start_ssh<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    fd: jint,
    request: JString<'local>,
) -> Result<JString<'local>, jni::errors::Error> {
    start_native(env, fd, request, SessionKind::Shell)
}

fn native_start_herdr<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    fd: jint,
    request: JString<'local>,
) -> Result<JString<'local>, jni::errors::Error> {
    start_native(env, fd, request, SessionKind::Herdr)
}

fn native_start_sftp<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    fd: jint,
    request: JString<'local>,
) -> Result<JString<'local>, jni::errors::Error> {
    start_native(env, fd, request, SessionKind::Sftp)
}

fn start_native<'local>(
    env: &mut Env<'local>,
    fd: jint,
    request: JString<'local>,
    kind: SessionKind,
) -> Result<JString<'local>, jni::errors::Error> {
    if fd < 0 {
        return JString::from_str(
            env,
            start_response(Err(session::StartError::InvalidRequest)),
        );
    }
    // Adopt before any JNI string operation so exceptions cannot leak the
    // descriptor transferred by Kotlin.
    let owned = unsafe { OwnedFd::from_raw_fd(fd) };
    if matches!(kind, SessionKind::Herdr)
        && java_string_too_long(env, &request, session::HERDR_START_JSON_LIMIT)?
    {
        return JString::from_str(
            env,
            start_response(Err(session::StartError::InvalidRequest)),
        );
    }
    let request = request.try_to_string(env)?;
    if matches!(kind, SessionKind::Herdr) && request.len() > session::HERDR_START_JSON_LIMIT {
        return JString::from_str(
            env,
            start_response(Err(session::StartError::InvalidRequest)),
        );
    }
    let response = start_response(manager().start(owned, &request, kind));
    JString::from_str(env, response)
}

// Check UTF-16 code units before allocating the Rust String; the subsequent
// byte-length check catches non-ASCII expansion. Only Herdr uses this limit.
fn java_string_too_long(
    env: &mut Env<'_>,
    value: &JString<'_>,
    max: usize,
) -> Result<bool, jni::errors::Error> {
    let length = env
        .call_method(value, jni_str!("length"), jni_sig!("()I"), &[])?
        .i()?;
    Ok(length < 0 || length as usize > max)
}

fn native_poll_event<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    session_id: jlong,
    timeout_ms: jint,
) -> Result<JString<'local>, jni::errors::Error> {
    let event = manager()
        .get(session_id.max(0) as u64)
        .map(|entry| entry.poll_event(timeout_ms.max(0) as u64))
        .unwrap_or_default();
    JString::from_str(env, event)
}

fn native_send_command<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    session_id: jlong,
    command: JString<'local>,
) -> Result<jboolean, jni::errors::Error> {
    let Some(entry) = manager().get(session_id.max(0) as u64) else {
        // A rejected SFTP transfer command may still carry a detached file
        // descriptor. Preserve the ordinary path's ownership contract even
        // if the session disappeared before this call reached Rust.
        let command = command.try_to_string(env)?;
        let _ = serde_json::from_str::<Command>(&command);
        return Ok(JNI_FALSE);
    };
    let sent = if entry.is_herdr() {
        let Some(_guard) = entry.begin_herdr_command() else {
            return Ok(JNI_FALSE);
        };
        if java_string_too_long(env, &command, session::HERDR_COMMAND_JSON_LIMIT)? {
            return Ok(JNI_FALSE);
        }
        let command = command.try_to_string(env)?;
        entry.send_herdr_json(&command)
    } else {
        let command = command.try_to_string(env)?;
        serde_json::from_str::<Command>(&command)
            .ok()
            .is_some_and(|command| entry.send_command(command))
    };
    Ok(if sent { JNI_TRUE } else { JNI_FALSE })
}

fn native_snapshot<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    session_id: jlong,
) -> Result<JByteArray<'local>, jni::errors::Error> {
    snapshot_for_revision(env, session_id, 0, 0)
}

fn native_snapshot_with_images<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    session_id: jlong,
    image_revision: jlong,
) -> Result<JByteArray<'local>, jni::errors::Error> {
    snapshot_for_revision(env, session_id, 0, image_revision.max(0) as u64)
}

fn native_frame<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    session_id: jlong,
    frame_revision: jlong,
    image_revision: jlong,
) -> Result<JByteArray<'local>, jni::errors::Error> {
    snapshot_for_revision(
        env,
        session_id,
        frame_revision.max(0) as u64,
        image_revision.max(0) as u64,
    )
}

fn snapshot_for_revision<'local>(
    env: &mut Env<'local>,
    session_id: jlong,
    frame_revision: u64,
    image_revision: u64,
) -> Result<JByteArray<'local>, jni::errors::Error> {
    let snapshot = manager()
        .get(session_id.max(0) as u64)
        .map(|entry| entry.frame_bytes(frame_revision, image_revision))
        .unwrap_or_default();
    let output = JByteArray::new(env, snapshot.len())?;
    let signed =
        unsafe { std::slice::from_raw_parts(snapshot.as_ptr().cast::<i8>(), snapshot.len()) };
    output.set_region(env, 0, signed)?;
    Ok(output)
}

fn native_herdr_snapshot_with_epoch<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    session_id: jlong,
    image_revision: jlong,
) -> Result<JByteArray<'local>, jni::errors::Error> {
    native_herdr_frame_with_epoch(env, _class, session_id, 0, image_revision)
}

fn native_herdr_frame_with_epoch<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    session_id: jlong,
    frame_revision: jlong,
    image_revision: jlong,
) -> Result<JByteArray<'local>, jni::errors::Error> {
    let Some(entry) = manager().get(session_id.max(0) as u64) else {
        return JByteArray::new(env, 0);
    };
    let Some(_guard) = entry.begin_herdr_snapshot() else {
        return JByteArray::new(env, 0);
    };
    // Hold the guard through creation and filling of the Java byte array, so
    // overlapping JNI calls cannot multiply the native snapshot copies.
    let snapshot =
        entry.herdr_frame_bytes(frame_revision.max(0) as u64, image_revision.max(0) as u64);
    let output = JByteArray::new(env, snapshot.len())?;
    let signed =
        unsafe { std::slice::from_raw_parts(snapshot.as_ptr().cast::<i8>(), snapshot.len()) };
    output.set_region(env, 0, signed)?;
    Ok(output)
}

/// Compare visible content, excluding opaque row identities and frame revisions.
#[cfg(feature = "ghostty")]
fn comparable_frame(mut bytes: Vec<u8>) -> Vec<u8> {
    if bytes.len() >= 80 && bytes[..4] == 0x4654564eu32.to_le_bytes() {
        let rows = u32::from_le_bytes(bytes[28..32].try_into().unwrap()) as usize;
        if 80 + rows * 24 <= bytes.len() {
            bytes[8..24].fill(0);
            bytes[80..80 + rows * 24].fill(0);
        }
    }
    bytes
}

#[cfg(feature = "ghostty")]
fn frame_first_text(bytes: &[u8]) -> Option<&[u8]> {
    if bytes.len() < 80 {
        return None;
    }
    let rows = u32::from_le_bytes(bytes[28..32].try_into().ok()?) as usize;
    let cell = 80usize.checked_add(rows.checked_mul(24)?)?.checked_add(8)?;
    let len = u32::from_le_bytes(bytes.get(cell + 16..cell + 20)?.try_into().ok()?) as usize;
    bytes.get(cell + 20..cell + 20 + len)
}

/// Exercises behavior affected by the libghostty-vt 1.0 migration in the
/// packaged library, including replies split across transport reads.
#[cfg(feature = "ghostty")]
fn terminal_vt_release_probe() -> bool {
    // The SIMD path handles large reads while short fragments take different
    // decoder branches. Their complete screen, cursor and replies must agree,
    // including sequences split across vector and UTF-8 boundaries.
    let mut input = b"ASCII ".repeat(24);
    input.extend_from_slice("\r\n界e\u{301}👩\u{200d}💻 mixed text\r\n".as_bytes());
    input.extend_from_slice(b"invalid: \xff\xc0\xaf\xed\xa0\x80\xf4\x90\x80\x80\r\n");
    input.extend_from_slice(b"\x1b[31mred\x1b[0m\r\n\x1b]10;?\x1b\\\x1b[6n");
    let mut complete = terminal::TerminalState::new(80, 24, None);
    complete.feed(&input);
    let expected = comparable_frame(complete.snapshot_bytes());
    let replies = complete.drain_pty_writes();
    if expected.is_empty() {
        return false;
    }
    for size in [1, 7, 15, 16, 17, 31, 32, 33, 63, 64, 65, 255] {
        let mut fragmented = terminal::TerminalState::new(80, 24, None);
        for chunk in input.chunks(size) {
            fragmented.feed(chunk);
        }
        if comparable_frame(fragmented.snapshot_bytes()) != expected
            || fragmented.drain_pty_writes() != replies
        {
            return false;
        }
    }
    let theme = model::TerminalThemeConfig {
        foreground: 0x123456,
        background: 0x234567,
        cursor: 0x345678,
        ansi: vec![0; 16],
    };
    let mut terminal = terminal::TerminalState::new(80, 24, Some(&theme));
    terminal.feed(b"\x1b]10;");
    terminal.feed(b"?\x1b\\");
    if terminal.drain_pty_writes() != b"\x1b]10;rgb:1212/3434/5656\x1b\\" {
        return false;
    }
    // A color set and query in one read must see the updated color and must
    // produce exactly one reply (the old adapter also scanned these queries).
    terminal.feed(b"\x1b]10;#abcdef\x07\x1b]10;?\x07\x1b]11;?\x1b\\\x1b]12;?\x1b\\");
    if terminal.drain_pty_writes()
        != b"\x1b]10;rgb:abab/cdcd/efef\x07\x1b]11;rgb:2323/4545/6767\x1b\\\x1b]12;rgb:3434/5656/7878\x1b\\"
    {
        return false;
    }
    terminal.feed(b"\x1b]110\x07\x1b]10;?\x1b\\");
    if terminal.drain_pty_writes() != b"\x1b]10;rgb:1212/3434/5656\x1b\\" {
        return false;
    }
    terminal.resize_with_metrics(80, 24, 9, 18);
    terminal.feed(b"\x1b[16t");
    if terminal.drain_pty_writes() != b"\x1b[6;18;9t" {
        return false;
    }
    // More than 10 KB of history: the new upstream byte-limit default must
    // not truncate our configured 10,000-line SSH scrollback.
    terminal.feed(b"HISTORY_START\r\n");
    terminal.feed(&b"retained history line\r\n".repeat(300));
    terminal.feed(b"END");
    terminal.scroll(-10_000);
    let top = terminal.snapshot_bytes();
    if frame_first_text(&top) != Some(b"H".as_slice())
        || terminal.scroll_to_bottom().is_none()
        || terminal.scroll_to_bottom().is_some()
    {
        return false;
    }
    // Herdr already keeps history on the host: its local terminal stays at
    // zero scrollback even with the new independent byte and line limits.
    let Some(mut herdr) = terminal::TerminalState::try_new_herdr(80, 24, None) else {
        return false;
    };
    herdr.feed(b"\x1b]10;?\x1b\\\x1b]11;?\x1b\\\x1b]12;?\x1b\\");
    if herdr.drain_pty_writes()
        != b"\x1b]10;rgb:f2f2/f2f2/f2f2\x1b\\\x1b]11;rgb:0000/0000/0000\x1b\\\x1b]12;rgb:f2f2/f2f2/f2f2\x1b\\"
    {
        return false;
    }
    herdr.feed(&b"host-owned history\r\n".repeat(100));
    let active = herdr.snapshot_bytes();
    herdr.scroll(-10_000);
    comparable_frame(active) == comparable_frame(herdr.snapshot_bytes())
}

fn native_terminal_probe<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
) -> Result<JByteArray<'local>, jni::errors::Error> {
    #[cfg(feature = "ghostty")]
    if !terminal_vt_release_probe() {
        return JByteArray::new(env, 0);
    }
    let mut terminal = terminal::TerminalState::new(12, 2, None);
    // Exercise Ghostty's mode parser and packed input-mode ABI in the device probe.
    #[cfg(feature = "ghostty")]
    {
        for (input, expected) in [
            (b"\x1b[?1002;1006h".as_slice(), 4 | 16),
            (b"\x1b[?1002;1006l".as_slice(), 0),
            (b"\x1b[?1003;1006h".as_slice(), 8 | 16),
            (b"\x1b[?2004h".as_slice(), 8 | 16 | 256),
            (b"\x1b[>1u".as_slice(), 8 | 16 | 256 | (1 << 9)),
            (b"\x1b[>9u".as_slice(), 8 | 16 | 256 | (9 << 9)),
            (b"\x1b[<u".as_slice(), 8 | 16 | 256 | (1 << 9)),
            (b"\x1b[?1049h".as_slice(), 8 | 16 | 256),
            (b"\x1b[?1049l".as_slice(), 8 | 16 | 256 | (1 << 9)),
            (b"\x1bc".as_slice(), 0),
        ] {
            terminal.feed(input);
            let snapshot = terminal.snapshot_bytes();
            if snapshot.len() < 80
                || i32::from_le_bytes(snapshot[56..60].try_into().unwrap()) != expected
            {
                return JByteArray::new(env, 0);
            }
        }
    }
    #[cfg(feature = "ghostty")]
    {
        terminal.feed(b"\x1b]52;c;Y2xpcGJv");
        terminal.feed(b"YXJk\x07");
        if terminal.take_clipboard().as_deref() != Some("clipboard")
            || terminal.take_clipboard().is_some()
        {
            return JByteArray::new(env, 0);
        }
        terminal.feed(b"\x1b]52;c;?\x1b\\");
        if terminal.take_clipboard().is_some() || !terminal.drain_pty_writes().is_empty() {
            return JByteArray::new(env, 0);
        }
        terminal.feed(b"\x1b]52;c;%%%\x07");
        if terminal.take_clipboard().is_some() {
            return JByteArray::new(env, 0);
        }
    }
    terminal.feed(b"\x1b[1;32mGhostty\x1b[0m");
    let snapshot = terminal.snapshot_bytes();
    #[cfg(feature = "ghostty")]
    {
        let offset = u32::from_le_bytes(snapshot[64..68].try_into().unwrap()) as usize;
        let revision = u64::from_le_bytes(snapshot[offset + 8..offset + 16].try_into().unwrap());
        let lean = terminal.snapshot_bytes_since(revision);
        if lean.len() < offset + 24 || lean[offset + 16..offset + 20] != [0; 4] {
            return JByteArray::new(env, 0);
        }
    }
    let output = JByteArray::new(env, snapshot.len())?;
    let signed =
        unsafe { std::slice::from_raw_parts(snapshot.as_ptr().cast::<i8>(), snapshot.len()) };
    output.set_region(env, 0, signed)?;
    Ok(output)
}

fn native_release<'local>(
    _env: &mut Env<'local>,
    _class: JClass<'local>,
    session_id: jlong,
) -> Result<(), jni::errors::Error> {
    manager().release(session_id.max(0) as u64);
    Ok(())
}

fn native_request_close<'local>(
    _env: &mut Env<'local>,
    _class: JClass<'local>,
    session_id: jlong,
) -> Result<(), jni::errors::Error> {
    // Signal only: no SSH I/O, task join or terminal destruction on the caller.
    if let Some(entry) = manager().get(session_id.max(0) as u64) {
        entry.close();
    }
    Ok(())
}

fn native_terminal_graphics_probe<'local>(
    env: &mut Env<'local>,
    _class: JClass<'local>,
    commands: JString,
    scroll: jint,
) -> Result<JByteArray<'local>, jni::errors::Error> {
    use base64::Engine;
    let encoded = commands.try_to_string(env)?;
    let data = base64::engine::general_purpose::STANDARD
        .decode(encoded)
        .unwrap_or_default();
    let mut terminal = terminal::TerminalState::new(80, 24, None);
    terminal.resize_with_metrics(80, 24, 8, 16);
    for chunk in data.chunks(17) {
        terminal.feed(chunk);
    }
    terminal.scroll(scroll.clamp(-100, 100));
    let snapshot = terminal.snapshot_bytes();
    #[cfg(feature = "ghostty")]
    {
        let offset = u32::from_le_bytes(snapshot[64..68].try_into().unwrap()) as usize;
        let revision = u64::from_le_bytes(snapshot[offset + 8..offset + 16].try_into().unwrap());
        let lean = terminal.snapshot_bytes_since(revision);
        if lean.len() < offset + 24 || lean[offset + 16..offset + 20] != [0; 4] {
            return JByteArray::new(env, 0);
        }
    }
    let output = JByteArray::new(env, snapshot.len())?;
    let signed =
        unsafe { std::slice::from_raw_parts(snapshot.as_ptr().cast::<i8>(), snapshot.len()) };
    output.set_region(env, 0, signed)?;
    Ok(output)
}
