// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0
#![allow(dead_code)]

mod appearance;
#[cfg(test)]
mod budget_poc;
mod capped_writer;
mod navigation;
mod preflight;
mod scroll;
mod viewport;
pub use navigation::{ActionResult, NativeAction};

#[path = "vendor/protocol/wire.rs"]
mod protocol;
pub use protocol::*;
#[path = "vendor/protocol/endpoint.rs"]
mod endpoint;
#[path = "vendor/raw_input.rs"]
mod raw_input;
#[path = "vendor/protocol/render_ansi.rs"]
mod render_ansi;
#[path = "vendor/terminal_theme.rs"]
mod terminal_theme;
mod input {
    #[path = "../vendor/input/encode.rs"]
    mod encode;
    #[path = "../vendor/input/model.rs"]
    mod model;
    #[path = "../vendor/input/parse.rs"]
    mod parse;
    pub use encode::{encode_key, encode_mouse_button, encode_mouse_scroll, encode_terminal_key};
    pub use model::{
        KeyboardProtocol, MouseProtocolEncoding, TerminalKey, TextCommit, WindowsKeyRecord,
    };
    pub use parse::parse_terminal_key_sequence;
}
mod api {
    pub mod schema {
        #[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
        #[serde(rename_all = "snake_case")]
        pub enum AgentStatus {
            Idle,
            Working,
            Blocked,
            Done,
            Unknown,
        }
    }
}
mod config {
    #[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
    #[serde(rename_all = "kebab-case")]
    pub enum ToastHerdrPosition {
        TopLeft,
        TopRight,
        BottomLeft,
        BottomRight,
    }
    pub enum CustomCommandAction {
        Shell,
        Pane,
        Popup,
        PluginAction,
    }
}
mod render_prof {
    pub fn enabled() -> bool {
        false
    }
    pub fn timer() -> Option<std::time::Instant> {
        None
    }
    pub fn duration_since(_: &str, _: Option<std::time::Instant>) {}
    pub fn counter(_: &str, _: u64) {}
    pub fn event(_: &str) {}
}

use base64::{Engine, engine::general_purpose::STANDARD};
use endpoint::*;
use raw_input::{RawInputEvent, RawInputFramer};
use render_ansi::BlitEncoder;
#[cfg(test)]
mod health_tests;
#[cfg(test)]
use serde_json::json;

/// Borrowed presentation metadata avoids a `serde_json::Value` clone of the
/// entire server projection. The serialized event uses the capped writer.
#[derive(serde::Serialize)]
struct Projection<'a> {
    ready: bool,
    target_epoch: Option<u64>,
    boot_id: Option<&'a String>,
    focused_pane_id: Option<&'a String>,
    server_version: Option<&'a String>,
    methods: Option<&'a Vec<String>>,
    workspaces: Option<&'a Vec<ClientShellWorkspace>>,
    tabs: Option<&'a Vec<ClientShellTab>>,
    panes: Option<&'a Vec<ClientShellPane>>,
    agents: Option<&'a Vec<ClientShellAgent>>,
}

#[derive(Default)]
pub struct Output {
    pub action_result: Option<ActionResult>,
    pub health_result: Option<(String, bool)>,
    pub outbound: Vec<u8>,
    pub ansi: Vec<u8>,
    pub state: Vec<u8>,
    pub clipboard: Vec<u8>,
    pub detached: bool,
    pub ready: Option<bool>,
    pub target_epoch: Option<u64>,
}

pub struct Client {
    buffer: Vec<u8>,
    welcome: Option<EndpointServerWelcome>,
    snapshot: Option<ClientShellSnapshot>,
    surface: Option<PaneSurfaceFrame>,
    encoder: BlitEncoder,
    repaint_pending: bool,
    input_parser: RawInputFramer,
    input_target: Option<(bool, String)>,
    target_epoch: u64,
    cols: u16,
    rows: u16,
    failed: bool,
    pending_action: Option<navigation::PendingAction>,
    navigation_revision: Option<u64>,
    viewport: viewport::ViewportReset,
    appearance: appearance::Appearance,
    health_nonce: Option<String>,
}

impl Client {
    pub fn new(cols: u16, rows: u16) -> Self {
        Self {
            buffer: Vec::new(),
            welcome: None,
            snapshot: None,
            surface: None,
            encoder: BlitEncoder::new(),
            repaint_pending: false,
            input_parser: RawInputFramer::default(),
            input_target: None,
            target_epoch: 0,
            cols: cols.clamp(1, 500),
            rows: rows.clamp(1, 300),
            failed: false,
            pending_action: None,
            navigation_revision: None,
            viewport: viewport::ViewportReset::default(),
            appearance: appearance::Appearance::default(),
            health_nonce: None,
        }
    }
    /// Prove that the endpoint, not only its SSH parent, is still responsive.
    pub fn probe(&mut self, nonce: String) -> Result<Output, String> {
        if nonce.is_empty() || nonce.len() > 128 {
            return Err("Invalid Herdr health probe".into());
        }
        let mut out = Output::default();
        self.health_nonce = None;
        if self
            .welcome
            .as_ref()
            .is_some_and(|w| w.capabilities.iter().any(|c| c == HEALTH_CHECK_CAPABILITY))
        {
            send(
                &mut out,
                ClientMessage::EndpointControl {
                    kind: HEALTH_PING_KIND.into(),
                    data: nonce.clone(),
                },
            )?;
            self.health_nonce = Some(nonce);
        } else {
            out.health_result = Some((nonce, false));
        }
        Ok(out)
    }
    pub fn hello(&mut self) -> Result<Output, String> {
        let hello = EndpointClientHello {
            generation: 1,
            cell_width_px: 0,
            cell_height_px: 0,
            surface_size: ClientSurfaceSize {
                cols: self.cols,
                rows: self.rows,
            },
            pixel_mouse: false,
            direct_graphics: false,
            endpoint_keybindings: false,
            mouse_capture: true,
            surface_active: true,
            surface_reuse: false,
            surface_delta: false,
            snapshot_codecs: vec![SNAPSHOT_CODEC_V1.into()],
            surface_codecs: vec![SURFACE_CODEC_V1.into()],
            input_codecs: vec![INPUT_CODEC_V1.into()],
            blob_codecs: vec![BLOB_CODEC_V1.into()],
        };
        let mut out = Output::default();
        send(
            &mut out,
            ClientMessage::EndpointControl {
                kind: ENDPOINT_HELLO_KIND.into(),
                data: serde_json::to_string(&hello).map_err(|e| e.to_string())?,
            },
        )?;
        Ok(out)
    }
    pub fn receive(&mut self, bytes: &[u8]) -> Result<Output, String> {
        self.receive_inner(bytes, false).map(|(output, _)| output)
    }
    /// Consume at most one complete wire frame. The transport must publish and
    /// feed its output before calling again with the unconsumed bytes: this
    /// keeps each encoder baseline synchronized with the terminal and preserves
    /// metadata, target, bell and clipboard transitions in stream order.
    pub fn receive_one(&mut self, bytes: &[u8]) -> Result<(Output, usize), String> {
        self.receive_inner(bytes, true)
    }
    fn receive_inner(
        &mut self,
        mut bytes: &[u8],
        one_frame: bool,
    ) -> Result<(Output, usize), String> {
        if self.failed {
            return Err("Herdr attachment closed".into());
        }
        let input_len = bytes.len();
        let result = (|| {
            let mut out = Output::default();
            while !bytes.is_empty() {
                let needed = if self.buffer.len() < 4 {
                    4 - self.buffer.len()
                } else {
                    let len = u32::from_le_bytes(self.buffer[..4].try_into().unwrap()) as usize;
                    if len == 0 || len > MAX_FRAME_SIZE {
                        return Err("Invalid Herdr frame size".into());
                    }
                    4 + len - self.buffer.len()
                };
                let take = needed.min(bytes.len());
                self.buffer.extend_from_slice(&bytes[..take]);
                bytes = &bytes[take..];
                if self.buffer.len() < 4 {
                    continue;
                }
                let len = u32::from_le_bytes(self.buffer[..4].try_into().unwrap()) as usize;
                if len == 0 || len > MAX_FRAME_SIZE {
                    return Err("Invalid Herdr frame size".into());
                }
                if self.buffer.len() < len + 4 {
                    continue;
                }
                // Serde's bincode adapter forwards untrusted collection hints.
                // Inspect the frozen schema and bound collections before it
                // creates owned data. Unsupported, unnegotiated messages are
                // ignored without deserializing their bodies. The logical
                // preflight bound is not a whole-session/RSS memory guarantee.
                if !preflight::disposition(&self.buffer[4..])? {
                    self.buffer.clear();
                    if one_frame {
                        return Ok((out, input_len - bytes.len()));
                    }
                    continue;
                }
                preflight::preflight(&self.buffer[4..], preflight::FRAME_BUDGET)?;
                // The wire length has already been capped independently. The
                // decoder's allocation accounting also charges container
                // memory, which can legitimately exceed the encoded size.
                let (msg, used): (ServerMessage, usize) = bincode::serde::decode_from_slice(
                    &self.buffer[4..],
                    bincode::config::standard().with_limit::<{ preflight::FRAME_BUDGET }>(),
                )
                .map_err(|_| "Invalid Herdr frame")?;
                if used != len {
                    return Err("Trailing Herdr frame data".into());
                }
                self.buffer.clear();
                let previous_target = self.current_target();
                self.message(msg, &mut out)?;
                self.complete_navigation(&mut out)?;
                if previous_target != self.current_target() {
                    self.target_epoch = self
                        .target_epoch
                        .checked_add(1)
                        .ok_or("Herdr target epoch exhausted")?;
                    // message() may have published the old epoch. Replace that
                    // projection after updating it, including within one read.
                    self.publish(&mut out)?;
                }
                // Invalidate immediately, even if a later frame switches back
                // before the input timeout fires. Never carry partial VT input
                // through an incoherent projection or a different target.
                if self.input_target.is_some() && self.input_target != self.current_target() {
                    self.input_parser = RawInputFramer::default();
                    self.input_target = None;
                }
                if out.ansi.len() > MAX_GRAPHICS_FRAME_SIZE {
                    return Err("Herdr render batch too large".into());
                }
                if one_frame {
                    return Ok((out, input_len - bytes.len()));
                }
            }
            Ok((out, input_len - bytes.len()))
        })();
        if result.is_err() {
            self.failed = true;
        }
        result
    }
    fn message(&mut self, msg: ServerMessage, out: &mut Output) -> Result<(), String> {
        match msg {
            ServerMessage::EndpointControl { kind, data } if kind == HEALTH_PONG_KIND => {
                if self.health_nonce.as_ref() == Some(&data) {
                    self.health_nonce = None;
                    out.health_result = Some((data, true));
                }
            }
            ServerMessage::ClientShellEndpointResponseChunk {
                boot_id,
                request_id,
                final_chunk,
                data,
            } => self.response(&boot_id, &request_id, final_chunk, &data, out)?,
            ServerMessage::EndpointControl { kind, data } if kind == ENDPOINT_WELCOME_KIND => {
                if self.welcome.is_some() {
                    return Err("Duplicate Herdr handshake".into());
                }
                let w: EndpointServerWelcome =
                    serde_json::from_str(&data).map_err(|_| "Invalid Herdr welcome")?;
                if w.error.is_some()
                    || w.generation != 1
                    || w.snapshot_codec != SNAPSHOT_CODEC_V1
                    || w.surface_codec != SURFACE_CODEC_V1
                    || w.input_codec != INPUT_CODEC_V1
                    || w.blob_codec != BLOB_CODEC_V1
                {
                    return Err("Incompatible Herdr endpoint".into());
                }
                self.welcome = Some(w);
                self.send_host_theme(out)?;
                self.publish(out)?;
            }
            ServerMessage::EndpointControl { kind, data } if kind == ENDPOINT_SNAPSHOT_KIND => {
                if self.welcome.is_none() {
                    return Err("Herdr snapshot before handshake".into());
                }
                let s: ClientShellSnapshot =
                    serde_json::from_str(&data).map_err(|_| "Invalid Herdr snapshot")?;
                if let Some(old) = &self.snapshot {
                    if old.boot_id != s.boot_id {
                        return Err("Herdr server restarted".into());
                    }
                    if old.revision >= s.revision {
                        return Ok(());
                    }
                }
                self.snapshot = Some(s);
                self.render(out)?;
                self.publish(out)?;
            }
            ServerMessage::PaneSurface(s) => {
                if self.welcome.is_none() {
                    return Err("Herdr surface before handshake".into());
                }
                validate_surface(&s)?;
                if let Some(snapshot) = &self.snapshot {
                    if s.boot_id != snapshot.boot_id {
                        return Err("Herdr server changed".into());
                    }
                    if s.projection_revision < snapshot.revision {
                        return Ok(());
                    }
                }
                if self
                    .surface
                    .as_ref()
                    .is_some_and(|old| old.surface_revision >= s.surface_revision)
                {
                    return Ok(());
                }
                self.surface = Some(s);
                self.render(out)?;
                self.publish(out)?;
            }
            ServerMessage::PaneSurfacePatch(patch) => {
                let s = self
                    .surface
                    .as_mut()
                    .ok_or("Herdr patch without baseline")?;
                if patch.boot_id != s.boot_id
                    || patch.base_surface_revision != s.surface_revision
                    || patch.projection_revision != s.projection_revision
                    || patch.surface_revision <= s.surface_revision
                {
                    return Err("Herdr patch baseline mismatch".into());
                }
                let mut proposed_heap = surface_heap_bytes(s)?;
                let mut row_ranges = Vec::with_capacity(patch.rows.len());
                for row in &patch.rows {
                    let end = usize::from(row.x)
                        .checked_add(row.cells.len())
                        .ok_or("Invalid patch")?;
                    if row.y >= s.frame.height || end > s.frame.width as usize {
                        return Err("Invalid Herdr patch row".into());
                    }
                    for cell in &row.cells {
                        validate_cell(cell, s.frame.hyperlinks.len())?;
                    }
                    if !row.cells.is_empty() {
                        row_ranges.push((row.y, usize::from(row.x), end));
                    }
                }
                row_ranges.sort_unstable();
                if row_ranges
                    .windows(2)
                    .any(|pair| pair[0].0 == pair[1].0 && pair[0].2 > pair[1].1)
                {
                    return Err("Overlapping Herdr patch rows".into());
                }
                for row in &patch.rows {
                    let start = row.y as usize * s.frame.width as usize + row.x as usize;
                    for (old, new) in s.frame.cells[start..start + row.cells.len()]
                        .iter()
                        .zip(&row.cells)
                    {
                        proposed_heap = proposed_heap
                            .checked_sub(string_capacity_heap(&old.symbol)?)
                            .ok_or("Herdr memory accounting underflow")?;
                        proposed_heap = proposed_heap
                            .checked_add(string_capacity_heap(&new.symbol)?)
                            .ok_or("Herdr memory accounting overflow")?;
                    }
                }
                if patch
                    .cursor
                    .as_ref()
                    .is_some_and(|c| c.x >= s.frame.width || c.y >= s.frame.height)
                {
                    return Err("Invalid Herdr patch cursor".into());
                }
                if s.panes.len() > 4096 || patch.panes.len() > 4096 {
                    return Err("Too many Herdr panes".into());
                }
                let mut pane_index = std::collections::HashMap::with_capacity(s.panes.len());
                for (index, pane) in s.panes.iter().enumerate() {
                    if pane.pane_id.is_empty()
                        || pane.pane_id.len() > MAX_PANE_ID_BYTES
                        || pane_index.insert(pane.pane_id.as_str(), index).is_some()
                    {
                        return Err("Invalid Herdr pane identity".into());
                    }
                }
                let mut updated = std::collections::HashSet::new();
                let mut patch_indices = Vec::with_capacity(patch.panes.len());
                for p in &patch.panes {
                    if p.pane_id.is_empty() || p.pane_id.len() > MAX_PANE_ID_BYTES {
                        return Err("Invalid Herdr pane identity".into());
                    }
                    let index = *pane_index
                        .get(p.pane_id.as_str())
                        .ok_or("Invalid Herdr patch pane")?;
                    let previous = &s.panes[index];
                    // Pane geometry (including a scrollbar appearing as output
                    // grows) can legitimately change in a surface patch. The
                    // upstream client accepts those updates in its full redraw
                    // path, provided every rectangle stays within the frame.
                    if !updated.insert(index)
                        || !rect_in_frame(p.rect, &s.frame)
                        || !rect_in_frame(p.inner_rect, &s.frame)
                        || p.scrollbar_rect
                            .is_some_and(|rect| !rect_in_frame(rect, &s.frame))
                    {
                        return Err("Invalid Herdr patch geometry".into());
                    }
                    proposed_heap = proposed_heap
                        .checked_sub(string_capacity_heap(&previous.pane_id)?)
                        .ok_or("Herdr memory accounting underflow")?;
                    proposed_heap = proposed_heap
                        .checked_add(string_capacity_heap(&p.pane_id)?)
                        .ok_or("Herdr memory accounting overflow")?;
                    patch_indices.push(index);
                }
                if proposed_heap > MAX_SURFACE_HEAP {
                    return Err("Herdr surface memory limit exceeded".into());
                }
                // These validation structures can be large. Release them
                // before retaining a new encoder frame and ANSI output.
                drop(row_ranges);
                drop(updated);
                drop(pane_index);
                // Move cells instead of clone_from: clone_from retains old large
                // String capacities, allowing many small patches to accumulate
                // unbounded hidden capacity in the retained baseline.
                for row in patch.rows {
                    let start = row.y as usize * s.frame.width as usize + row.x as usize;
                    for (dst, cell) in s.frame.cells[start..start + row.cells.len()]
                        .iter_mut()
                        .zip(row.cells)
                    {
                        *dst = cell;
                    }
                }
                for (p, index) in patch.panes.into_iter().zip(patch_indices) {
                    s.panes[index] = p;
                }
                s.frame.cursor = patch.cursor;
                s.surface_revision = patch.surface_revision;
                self.render(out)?;
                self.publish(out)?;
            }
            ServerMessage::Clipboard { data } => {
                let decoded = STANDARD.decode(data).map_err(|_| "Invalid clipboard")?;
                std::str::from_utf8(&decoded).map_err(|_| "Invalid clipboard")?;
                out.clipboard = decoded;
            }
            ServerMessage::TerminalBell { .. } => {
                capped_writer::append_bounded(&mut out.ansi, &[7], capped_writer::MAX_RENDER_BYTES)
                    .map_err(|_| "Herdr render limit exceeded")?
            }
            ServerMessage::ServerShutdown { .. } => return Err("Herdr server stopped".into()),
            ServerMessage::ClientShellError { .. } => return Err("Herdr server error".into()),
            ServerMessage::Welcome { .. } | ServerMessage::ClientShellSnapshot(_) => {
                return Err("Incompatible Herdr endpoint".into());
            }
            _ => {}
        }
        Ok(())
    }
    fn coherent(&self) -> Option<(&ClientShellSnapshot, &PaneSurfaceFrame)> {
        let s = self.snapshot.as_ref()?;
        let f = self.surface.as_ref()?;
        (s.boot_id == f.boot_id
            && s.revision == f.projection_revision
            && f.frame.width == self.cols
            && f.frame.height == self.rows)
            .then_some((s, f))
    }
    fn current_target(&self) -> Option<(bool, String)> {
        if !self.navigation_ready() || !self.viewport.ready() {
            return None;
        }
        let (snapshot, surface) = self.coherent()?;
        if let Some(p) = &surface.popup {
            return Some((true, p.terminal_id.clone()));
        }
        snapshot
            .focused_pane_id
            .as_ref()
            .filter(|id| surface.panes.iter().any(|p| &p.pane_id == *id))
            .map(|id| (false, id.clone()))
    }
    pub fn target_epoch(&self) -> Option<u64> {
        self.current_target().map(|_| self.target_epoch)
    }
    fn render(&mut self, out: &mut Output) -> Result<(), String> {
        if !self.restore_viewport(out)? {
            return Ok(());
        }
        self.render_frame(out)
    }
    fn render_frame(&mut self, out: &mut Output) -> Result<(), String> {
        if !self.viewport.ready() {
            return Ok(());
        }
        let Some((_, surface)) = self.coherent() else {
            return Ok(());
        };
        let mut frame = surface.frame.clone();
        if let Some(popup) = &surface.popup {
            let p = &popup.frame;
            let x = frame.width.saturating_sub(p.width) / 2;
            let y = frame.height.saturating_sub(p.height) / 2;
            let link_offset = frame.hyperlinks.len() as u32;
            frame.hyperlinks.extend(p.hyperlinks.iter().cloned());
            for row in 0..p.height.min(frame.height) {
                for col in 0..p.width.min(frame.width) {
                    let mut cell = p.cells[row as usize * p.width as usize + col as usize].clone();
                    cell.hyperlink = cell.hyperlink.and_then(|i| i.checked_add(link_offset));
                    frame.cells[(y + row) as usize * frame.width as usize + (x + col) as usize] =
                        cell;
                }
            }
            frame.cursor = p.cursor.clone().map(|mut c| {
                c.x += x;
                c.y += y;
                c
            });
        }
        frame.graphics.clear();
        self.appearance.apply(&mut frame);
        let mut encoded = self.encoder.encode(&frame, self.repaint_pending);
        if encoded.overflowed {
            return Err("Herdr render limit exceeded".into());
        }
        // The common one-frame receive moves the encoder buffer instead of
        // retaining a second full-sized copy. Coalesced frames use a bounded
        // append; never commit a baseline when that append would overflow.
        if out.ansi.is_empty() {
            out.ansi = std::mem::take(&mut encoded.bytes);
        } else {
            capped_writer::append_bounded(
                &mut out.ansi,
                &encoded.bytes,
                capped_writer::MAX_RENDER_BYTES,
            )
            .map_err(|_| "Herdr render limit exceeded")?;
        }
        self.encoder.commit(frame, encoded);
        self.repaint_pending = false;
        Ok(())
    }
    fn publish(&self, out: &mut Output) -> Result<(), String> {
        let snapshot = self.snapshot.as_ref();
        let value = Projection {
            ready: self.coherent().is_some() && self.navigation_ready() && self.viewport.ready(),
            target_epoch: self.target_epoch(),
            boot_id: snapshot.map(|v| &v.boot_id),
            focused_pane_id: snapshot.and_then(|v| v.focused_pane_id.as_ref()),
            server_version: self.welcome.as_ref().map(|v| &v.server_version),
            methods: self.welcome.as_ref().map(|v| &v.methods),
            workspaces: snapshot.map(|v| &v.workspaces),
            tabs: snapshot.map(|v| &v.tabs),
            panes: snapshot.map(|v| &v.panes),
            agents: snapshot.map(|v| &v.agents),
        };
        let mut writer = capped_writer::CappedWriter::with_limit(4 * 1024 * 1024);
        serde_json::to_writer(&mut writer, &value)
            .map_err(|_| "Herdr projection limit exceeded")?;
        let (state, failed) = writer.finish();
        if failed {
            return Err("Herdr projection limit exceeded".into());
        }
        out.ready = Some(value.ready);
        out.target_epoch = value.target_epoch;
        out.state = state;
        Ok(())
    }
    pub fn input(&mut self, bytes: &[u8]) -> Result<Output, String> {
        if self.input_parser.pending_len().saturating_add(bytes.len()) > MAX_FRAME_SIZE / 2 {
            self.input_parser = RawInputFramer::default();
            return Err("Herdr input too large".into());
        }
        let mut out = Output::default();
        let Some((popup, id)) = self.current_target() else {
            self.input_parser = RawInputFramer::default();
            self.input_target = None;
            return Ok(out);
        };
        if self.input_target.as_ref() != Some(&(popup, id.clone())) {
            self.input_parser = RawInputFramer::default();
            self.input_target = Some((popup, id.clone()));
        }
        let events = self.input_parser.push(bytes);
        self.emit_input(events, popup, id, &mut out)?;
        Ok(out)
    }
    pub fn flush(&mut self) -> Result<Output, String> {
        let mut out = Output::default();
        let target = self.input_target.clone();
        let valid = self.current_target();
        if target.is_none() || target != valid {
            self.input_parser = RawInputFramer::default();
            self.input_target = None;
            return Ok(out);
        }
        let events = self.input_parser.flush_timeout();
        let (popup, id) = target.unwrap();
        self.emit_input(events, popup, id, &mut out)?;
        Ok(out)
    }
    fn emit_input(
        &mut self,
        events: Vec<RawInputEvent>,
        popup: bool,
        id: String,
        out: &mut Output,
    ) -> Result<(), String> {
        let mut semantic = Vec::new();
        for event in events {
            if let RawInputEvent::Mouse(mouse) = event {
                if !semantic.is_empty() {
                    let events = std::mem::take(&mut semantic);
                    send(
                        out,
                        if popup {
                            ClientMessage::ClientShellPopupInput {
                                terminal_id: id.clone(),
                                events,
                            }
                        } else {
                            ClientMessage::ClientShellPaneInput {
                                pane_id: id.clone(),
                                events,
                            }
                        },
                    )?;
                }
                self.scroll_mouse(mouse, out)?;
                continue;
            }
            match event {
                RawInputEvent::Key(k) => {
                    if let Some(k) = ClientPaneInputEvent::from_terminal_key(k) {
                        semantic.push(k);
                    }
                }
                RawInputEvent::Text(t) => {
                    semantic.push(ClientPaneInputEvent::TextCommit(t.as_str().to_string()))
                }
                RawInputEvent::Paste(t) => semantic.push(ClientPaneInputEvent::Paste(t)),
                _ => {}
            }
        }
        if !semantic.is_empty() {
            send(
                out,
                if popup {
                    ClientMessage::ClientShellPopupInput {
                        terminal_id: id,
                        events: semantic,
                    }
                } else {
                    ClientMessage::ClientShellPaneInput {
                        pane_id: id,
                        events: semantic,
                    }
                },
            )?;
        }
        Ok(())
    }
    pub fn resize(&mut self, cols: u16, rows: u16) -> Result<Output, String> {
        self.target_epoch = self
            .target_epoch
            .checked_add(1)
            .ok_or("Herdr target epoch exhausted")?;
        self.input_parser = RawInputFramer::default();
        self.input_target = None;
        self.cols = cols.clamp(1, 500);
        self.rows = rows.clamp(1, 300);
        // The local terminal may have been rearranged even when the final
        // geometry is unchanged, for example when a retained view reattaches.
        // Keep this pending until a matching server surface can be painted.
        self.repaint_pending = true;
        let mut out = Output::default();
        send(
            &mut out,
            ClientMessage::ClientShellResize {
                cell_width_px: 0,
                cell_height_px: 0,
                surface_size: ClientSurfaceSize {
                    cols: self.cols,
                    rows: self.rows,
                },
                pixel_mouse: false,
            },
        )?;
        self.render(&mut out)?;
        self.publish(&mut out)?;
        Ok(out)
    }
    pub fn detach(&mut self) -> Result<Output, String> {
        let mut out = Output::default();
        send(&mut out, ClientMessage::Detach)?;
        out.detached = true;
        Ok(out)
    }
}
fn send(out: &mut Output, msg: ClientMessage) -> Result<(), String> {
    protocol::write_message(&mut out.outbound, &msg).map_err(|e| e.to_string())
}
// Each retained server surface is bounded separately from temporary decoding,
// encoder/output buffers, metadata, Ghostty and JNI. This is a conservative
// logical charge, not a measurement of process RSS.
const MAX_SURFACE_HEAP: usize = 24 * 1024 * 1024;
const MAX_PANE_ID_BYTES: usize = 256;
const ALLOCATION_ALLOWANCE: usize = 32;
fn string_capacity_heap(value: &String) -> Result<usize, &'static str> {
    value
        .capacity()
        .checked_add(ALLOCATION_ALLOWANCE)
        .ok_or("Herdr memory accounting overflow")
}
fn vec_heap<T>(values: &Vec<T>) -> Result<usize, &'static str> {
    values
        .capacity()
        .checked_mul(std::mem::size_of::<T>())
        .and_then(|bytes| bytes.checked_add(ALLOCATION_ALLOWANCE))
        .ok_or("Herdr memory accounting overflow")
}
fn add_heap(total: &mut usize, bytes: usize) -> Result<(), &'static str> {
    *total = total
        .checked_add(bytes)
        .ok_or("Herdr memory accounting overflow")?;
    Ok(())
}
fn frame_heap_bytes(frame: &FrameData) -> Result<usize, &'static str> {
    let mut total = vec_heap(&frame.cells)?;
    for cell in &frame.cells {
        add_heap(&mut total, string_capacity_heap(&cell.symbol)?)?;
    }
    add_heap(&mut total, vec_heap(&frame.hyperlinks)?)?;
    for uri in &frame.hyperlinks {
        add_heap(&mut total, string_capacity_heap(uri)?)?;
    }
    add_heap(&mut total, vec_heap(&frame.graphics)?)?;
    Ok(total)
}
fn surface_heap_bytes(surface: &PaneSurfaceFrame) -> Result<usize, &'static str> {
    let mut total = std::mem::size_of::<PaneSurfaceFrame>() + ALLOCATION_ALLOWANCE;
    add_heap(&mut total, string_capacity_heap(&surface.boot_id)?)?;
    add_heap(&mut total, frame_heap_bytes(&surface.frame)?)?;
    add_heap(&mut total, vec_heap(&surface.panes)?)?;
    for pane in &surface.panes {
        add_heap(&mut total, string_capacity_heap(&pane.pane_id)?)?;
    }
    add_heap(&mut total, vec_heap(&surface.splits)?)?;
    for split in &surface.splits {
        add_heap(&mut total, vec_heap(&split.path)?)?;
    }
    if let Some(popup) = &surface.popup {
        add_heap(
            &mut total,
            std::mem::size_of::<ClientShellPopupSurface>() + ALLOCATION_ALLOWANCE,
        )?;
        add_heap(&mut total, string_capacity_heap(&popup.terminal_id)?)?;
        add_heap(&mut total, string_capacity_heap(&popup.title)?)?;
        add_heap(&mut total, frame_heap_bytes(&popup.frame)?)?;
    }
    add_heap(&mut total, vec_heap(&surface.graphics.assets)?)?;
    add_heap(&mut total, vec_heap(&surface.graphics.placements)?)?;
    add_heap(&mut total, vec_heap(&surface.graphics.retained_assets)?)?;
    Ok(total)
}
fn validate_frame(frame: &FrameData) -> Result<(), String> {
    if frame.width == 0
        || frame.width > 500
        || frame.height == 0
        || frame.height > 300
        || frame.cells.len() != frame.width as usize * frame.height as usize
        || frame
            .cursor
            .as_ref()
            .is_some_and(|c| c.x >= frame.width || c.y >= frame.height)
        || !frame.graphics.is_empty()
    {
        return Err("Invalid Herdr surface".into());
    }
    for cell in &frame.cells {
        validate_cell(cell, frame.hyperlinks.len())?;
    }
    Ok(())
}
fn validate_cell(cell: &CellData, hyperlink_count: usize) -> Result<(), String> {
    // A cell is text, not terminal instructions. BlitEncoder writes symbols
    // directly, so a hostile host must not inject CSI/OSC control sequences.
    if cell.symbol.len() > 4096
        || cell.symbol.chars().any(char::is_control)
        || cell
            .hyperlink
            .is_some_and(|i| i as usize >= hyperlink_count)
    {
        return Err("Invalid Herdr cell".into());
    }
    Ok(())
}
fn rect_in_frame(rect: SurfaceRect, frame: &FrameData) -> bool {
    u32::from(rect.x) + u32::from(rect.width) <= u32::from(frame.width)
        && u32::from(rect.y) + u32::from(rect.height) <= u32::from(frame.height)
}
fn validate_surface(surface: &PaneSurfaceFrame) -> Result<(), String> {
    validate_frame(&surface.frame)?;
    if let Some(p) = &surface.popup {
        validate_frame(&p.frame)?;
    }
    if surface_heap_bytes(surface)? > MAX_SURFACE_HEAP {
        return Err("Herdr surface memory limit exceeded".into());
    }
    if surface.panes.len() > 4096
        || surface.splits.len() > 4096
        || !surface.graphics.assets.is_empty()
        || !surface.graphics.placements.is_empty()
        || !surface.graphics.retained_assets.is_empty()
    {
        return Err("Unsupported Herdr surface".into());
    }
    let mut ids = std::collections::HashSet::new();
    for pane in &surface.panes {
        if pane.pane_id.is_empty() || pane.pane_id.len() > MAX_PANE_ID_BYTES {
            return Err("Invalid Herdr pane identity".into());
        }
        if pane.pane_id.is_empty()
            || pane.pane_id.len() > 4096
            || !ids.insert(&pane.pane_id)
            || !rect_in_frame(pane.rect, &surface.frame)
            || !rect_in_frame(pane.inner_rect, &surface.frame)
            || pane
                .scrollbar_rect
                .is_some_and(|r| !rect_in_frame(r, &surface.frame))
        {
            return Err("Invalid Herdr pane geometry".into());
        }
    }
    for split in &surface.splits {
        if split.path.len() > 4096
            || !rect_in_frame(split.area, &surface.frame)
            || !rect_in_frame(split.hit_rect, &surface.frame)
        {
            return Err("Invalid Herdr split geometry".into());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn accessory_control_then_escape_keeps_independent_modifiers() {
        let mut parser = RawInputFramer::default();
        let mut events = parser.push(&[3]);
        events.extend(parser.flush_timeout());
        events.extend(parser.push(&[27]));
        events.extend(parser.flush_timeout());
        let keys: Vec<_> = events
            .into_iter()
            .filter_map(|event| match event {
                RawInputEvent::Key(key) => ClientPaneInputEvent::from_terminal_key(key),
                _ => None,
            })
            .collect();
        assert_eq!(keys.len(), 2);
        assert!(matches!(
            &keys[0],
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::Char('c'),
                modifiers: 2,
                ..
            }
        ));
        assert!(matches!(
            &keys[1],
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::Esc,
                modifiers: 0,
                ..
            }
        ));
        let mut parser = RawInputFramer::default();
        let mut events = parser.push(b"\x1b[27;1u\x1b[13;1u");
        events.extend(parser.flush_timeout());
        let keys: Vec<_> = events
            .into_iter()
            .filter_map(|event| match event {
                RawInputEvent::Key(key) => ClientPaneInputEvent::from_terminal_key(key),
                _ => None,
            })
            .collect();
        assert_eq!(keys.len(), 2);
        assert!(matches!(
            &keys[0],
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::Esc,
                modifiers: 0,
                ..
            }
        ));
        assert!(matches!(
            &keys[1],
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::Enter,
                modifiers: 0,
                ..
            }
        ));
    }
    struct FixtureCleanup {
        binary: std::path::PathBuf,
        root: std::path::PathBuf,
        runtime: std::path::PathBuf,
    }
    impl Drop for FixtureCleanup {
        fn drop(&mut self) {
            let _ = std::process::Command::new(&self.binary)
                .args(["session", "stop", "novascale-test-fixture", "--json"])
                .env("XDG_CONFIG_HOME", self.root.join("config"))
                .env("XDG_STATE_HOME", self.root.join("state"))
                .env("XDG_DATA_HOME", self.root.join("data"))
                .env("XDG_RUNTIME_DIR", &self.runtime)
                .env_remove("HERDR_SESSION")
                .env_remove("HERDR_SOCKET_PATH")
                .env_remove("HERDR_CLIENT_SOCKET_PATH")
                .env_remove("HERDR_CONFIG_PATH")
                .current_dir(&self.root)
                .output();
        }
    }
    #[test]
    fn hello_is_a_framed_generation_one_endpoint_request() {
        let mut c = Client::new(80, 24);
        let out = c.hello().unwrap();
        let len = u32::from_le_bytes(out.outbound[..4].try_into().unwrap()) as usize;
        assert_eq!(len, out.outbound.len() - 4);
        let (msg, used): (ClientMessage, usize) =
            bincode::serde::decode_from_slice(&out.outbound[4..], bincode::config::standard())
                .unwrap();
        assert_eq!(used, len);
        let ClientMessage::EndpointControl { kind, data } = msg else {
            panic!("expected endpoint hello")
        };
        assert_eq!(kind, ENDPOINT_HELLO_KIND);
        let hello: EndpointClientHello = serde_json::from_str(&data).unwrap();
        assert_eq!(hello.generation, 1);
        assert!(!hello.surface_delta);
        assert!(!hello.direct_graphics);
    }
    #[test]
    fn fragmented_and_invalid_frames_are_bounded() {
        let mut c = Client::new(80, 24);
        for byte in [1u8, 0, 0] {
            assert!(c.receive(&[byte]).unwrap().state.is_empty());
        }
        assert!(c.receive(&[0, 255]).is_err());
        assert!(c.receive(&[0]).is_err());
        let mut c = Client::new(80, 24);
        assert!(
            c.receive(&((MAX_FRAME_SIZE as u32 + 1).to_le_bytes()))
                .is_err()
        );
    }
    #[test]
    fn huge_nested_sequence_hint_is_rejected() {
        // Frozen generation-1 tag 13 = PaneSurface. Construct an otherwise
        // truncated frame with a claimed cell count far beyond the byte limit.
        let config = bincode::config::standard();
        let mut payload = bincode::serde::encode_to_vec(13u32, config).unwrap();
        payload.extend(bincode::serde::encode_to_vec("boot", config).unwrap());
        payload.extend(bincode::serde::encode_to_vec(1u64, config).unwrap());
        payload.extend(bincode::serde::encode_to_vec(1u64, config).unwrap());
        payload.extend(bincode::serde::encode_to_vec(u64::MAX, config).unwrap());
        let mut wire = (payload.len() as u32).to_le_bytes().to_vec();
        wire.extend(payload);
        let mut client = Client::new(80, 24);
        assert!(client.receive(&wire).is_err());
        assert!(client.receive(&[0]).is_err());
    }
    #[test]
    fn invalid_surface_cells_geometry_and_cursor_are_rejected() {
        let cell = CellData {
            symbol: "A".into(),
            fg: 0,
            bg: 0,
            modifier: 0,
            skip: false,
            hyperlink: None,
        };
        let mut frame = FrameData {
            width: 1,
            height: 1,
            cells: vec![cell],
            cursor: None,
            hyperlinks: vec![],
            graphics: vec![],
        };
        assert!(validate_frame(&frame).is_ok());
        frame.cells[0].symbol = "\u{1b}[31m".into();
        assert!(validate_frame(&frame).is_err());
        frame.cells[0].symbol = "a".repeat(4097);
        assert!(validate_frame(&frame).is_err());
        frame.cells[0].symbol = "A".into();
        frame.cells[0].hyperlink = Some(0);
        assert!(validate_frame(&frame).is_err());
        frame.cells[0].hyperlink = None;
        frame.cursor = Some(CursorState {
            x: u16::MAX,
            y: 0,
            visible: true,
            shape: 0,
        });
        assert!(validate_frame(&frame).is_err());
        frame.cursor = None;
        let rect = SurfaceRect {
            x: 0,
            y: 0,
            width: 2,
            height: 1,
        };
        let surface = PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame,
            panes: vec![PaneSurfacePane {
                pane_id: "pane".into(),
                content_revision: 1,
                rect,
                inner_rect: rect,
                scrollbar_rect: None,
                scroll: None,
                focused: true,
                mouse_reporting: false,
                sgr_pixel_mouse: false,
                alternate_screen_active: false,
                pixel_width: 0,
                pixel_height: 0,
            }],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        };
        assert!(validate_surface(&surface).is_err());
    }
    #[test]
    fn invalid_patch_is_rejected_before_any_row_is_applied() {
        let cell = CellData {
            symbol: "A".into(),
            fg: 0,
            bg: 0,
            modifier: 0,
            skip: false,
            hyperlink: None,
        };
        let frame = FrameData {
            width: 1,
            height: 1,
            cells: vec![cell.clone()],
            cursor: None,
            hyperlinks: vec![],
            graphics: vec![],
        };
        let surface = PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame,
            panes: vec![],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        };
        let mut client = Client::new(1, 1);
        client.surface = Some(surface);
        let good = CellData {
            symbol: "B".into(),
            ..cell.clone()
        };
        let bad = CellData {
            symbol: "\u{1b}]52;c;bad".into(),
            ..cell
        };
        let patch = PaneSurfacePatch {
            boot_id: "boot".into(),
            projection_revision: 1,
            base_surface_revision: 1,
            surface_revision: 2,
            rows: vec![
                PaneSurfacePatchRow {
                    x: 0,
                    y: 0,
                    cells: vec![good],
                },
                PaneSurfacePatchRow {
                    x: 0,
                    y: 0,
                    cells: vec![bad],
                },
            ],
            panes: vec![],
            cursor: None,
        };
        assert!(
            client
                .message(
                    ServerMessage::PaneSurfacePatch(patch),
                    &mut Output::default()
                )
                .is_err()
        );
        let retained = client.surface.unwrap();
        assert_eq!(retained.surface_revision, 1);
        assert_eq!(retained.frame.cells[0].symbol, "A");
    }
    #[test]
    fn patch_releases_replaced_string_capacity_and_rejects_overlaps() {
        let mut large = String::with_capacity(16_384);
        large.push('A');
        let cell = CellData {
            symbol: large,
            fg: 0,
            bg: 0,
            modifier: 0,
            skip: false,
            hyperlink: None,
        };
        let frame = FrameData {
            width: 1,
            height: 1,
            cells: vec![cell],
            cursor: None,
            hyperlinks: vec![],
            graphics: vec![],
        };
        let surface = PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame,
            panes: vec![],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        };
        let mut client = Client::new(1, 1);
        client.surface = Some(surface);
        let tiny = CellData {
            symbol: "x".into(),
            fg: 0,
            bg: 0,
            modifier: 0,
            skip: false,
            hyperlink: None,
        };
        let patch = PaneSurfacePatch {
            boot_id: "boot".into(),
            projection_revision: 1,
            base_surface_revision: 1,
            surface_revision: 2,
            rows: vec![PaneSurfacePatchRow {
                x: 0,
                y: 0,
                cells: vec![tiny.clone()],
            }],
            panes: vec![],
            cursor: None,
        };
        client
            .message(
                ServerMessage::PaneSurfacePatch(patch),
                &mut Output::default(),
            )
            .unwrap();
        assert!(
            client.surface.as_ref().unwrap().frame.cells[0]
                .symbol
                .capacity()
                < 100
        );
        let patch = PaneSurfacePatch {
            boot_id: "boot".into(),
            projection_revision: 1,
            base_surface_revision: 2,
            surface_revision: 3,
            rows: vec![
                PaneSurfacePatchRow {
                    x: 0,
                    y: 0,
                    cells: vec![tiny.clone()],
                },
                PaneSurfacePatchRow {
                    x: 0,
                    y: 0,
                    cells: vec![tiny],
                },
            ],
            panes: vec![],
            cursor: None,
        };
        assert!(
            client
                .message(
                    ServerMessage::PaneSurfacePatch(patch),
                    &mut Output::default()
                )
                .is_err()
        );
        assert_eq!(client.surface.as_ref().unwrap().surface_revision, 2);
    }
    #[test]
    fn patch_accepts_bounded_scrollbar_change_and_rejects_out_of_bounds() {
        let cell = CellData {
            symbol: " ".into(),
            fg: 0,
            bg: 0,
            modifier: 0,
            skip: false,
            hyperlink: None,
        };
        let frame = FrameData {
            width: 4,
            height: 4,
            cells: vec![cell; 16],
            cursor: None,
            hyperlinks: vec![],
            graphics: vec![],
        };
        let rect = SurfaceRect {
            x: 0,
            y: 0,
            width: 4,
            height: 4,
        };
        let pane = PaneSurfacePane {
            pane_id: "pane".into(),
            content_revision: 1,
            rect,
            inner_rect: rect,
            scrollbar_rect: None,
            scroll: None,
            focused: true,
            mouse_reporting: false,
            sgr_pixel_mouse: false,
            alternate_screen_active: false,
            pixel_width: 0,
            pixel_height: 0,
        };
        let surface = PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame,
            panes: vec![pane.clone()],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        };
        let mut client = Client::new(4, 4);
        client.surface = Some(surface);
        let mut changed = pane;
        changed.inner_rect = SurfaceRect {
            x: 0,
            y: 0,
            width: 3,
            height: 4,
        };
        changed.scrollbar_rect = Some(SurfaceRect {
            x: 3,
            y: 0,
            width: 1,
            height: 4,
        });
        let patch = PaneSurfacePatch {
            boot_id: "boot".into(),
            projection_revision: 1,
            base_surface_revision: 1,
            surface_revision: 2,
            rows: vec![],
            panes: vec![changed.clone()],
            cursor: None,
        };
        client
            .message(
                ServerMessage::PaneSurfacePatch(patch),
                &mut Output::default(),
            )
            .unwrap();
        assert_eq!(
            client.surface.as_ref().unwrap().panes[0].scrollbar_rect,
            changed.scrollbar_rect
        );
        changed.scrollbar_rect = Some(SurfaceRect {
            x: 4,
            y: 0,
            width: 1,
            height: 4,
        });
        let invalid = PaneSurfacePatch {
            boot_id: "boot".into(),
            projection_revision: 1,
            base_surface_revision: 2,
            surface_revision: 3,
            rows: vec![],
            panes: vec![changed],
            cursor: None,
        };
        assert!(
            client
                .message(
                    ServerMessage::PaneSurfacePatch(invalid),
                    &mut Output::default()
                )
                .is_err()
        );
        assert_eq!(client.surface.as_ref().unwrap().surface_revision, 2);
    }
    #[test]
    fn many_common_prefix_panes_are_validated_and_updated_by_index() {
        let rect = SurfaceRect {
            x: 0,
            y: 0,
            width: 1,
            height: 1,
        };
        let panes: Vec<_> = (0..4096)
            .map(|i| PaneSurfacePane {
                pane_id: format!("{}{:04}", "p".repeat(200), i),
                content_revision: 1,
                rect,
                inner_rect: rect,
                scrollbar_rect: None,
                scroll: None,
                focused: false,
                mouse_reporting: false,
                sgr_pixel_mouse: false,
                alternate_screen_active: false,
                pixel_width: 0,
                pixel_height: 0,
            })
            .collect();
        let frame = FrameData {
            width: 1,
            height: 1,
            cells: vec![CellData {
                symbol: " ".into(),
                fg: 0,
                bg: 0,
                modifier: 0,
                skip: false,
                hyperlink: None,
            }],
            cursor: None,
            hyperlinks: vec![],
            graphics: vec![],
        };
        let surface = PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame,
            panes,
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        };
        validate_surface(&surface).unwrap();
        let mut updates = surface.panes.clone();
        for pane in &mut updates {
            pane.content_revision = 2;
        }
        updates.reverse();
        let patch = PaneSurfacePatch {
            boot_id: "boot".into(),
            projection_revision: 1,
            base_surface_revision: 1,
            surface_revision: 2,
            rows: vec![],
            panes: updates,
            cursor: None,
        };
        let mut client = Client::new(1, 1);
        client.surface = Some(surface);
        client
            .message(
                ServerMessage::PaneSurfacePatch(patch),
                &mut Output::default(),
            )
            .unwrap();
        assert!(
            client
                .surface
                .unwrap()
                .panes
                .iter()
                .all(|pane| pane.content_revision == 2)
        );
    }
    #[test]
    fn retained_surface_limit_counts_capacity_before_applying_patches() {
        let mut large = String::with_capacity(MAX_SURFACE_HEAP - 4096);
        large.push('a');
        let cell = |symbol| CellData {
            symbol,
            fg: 0,
            bg: 0,
            modifier: 0,
            skip: false,
            hyperlink: None,
        };
        let frame = FrameData {
            width: 2,
            height: 1,
            cells: vec![cell(large), cell("b".into())],
            cursor: None,
            hyperlinks: vec![],
            graphics: vec![],
        };
        let surface = PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame,
            panes: vec![],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        };
        assert!(surface_heap_bytes(&surface).unwrap() < MAX_SURFACE_HEAP);
        let mut new_symbol = String::with_capacity(8192);
        new_symbol.push('c');
        let patch = PaneSurfacePatch {
            boot_id: "boot".into(),
            projection_revision: 1,
            base_surface_revision: 1,
            surface_revision: 2,
            rows: vec![PaneSurfacePatchRow {
                x: 1,
                y: 0,
                cells: vec![cell(new_symbol)],
            }],
            panes: vec![],
            cursor: None,
        };
        let mut client = Client::new(2, 1);
        client.surface = Some(surface);
        assert!(
            client
                .message(
                    ServerMessage::PaneSurfacePatch(patch),
                    &mut Output::default()
                )
                .is_err()
        );
        let retained = client.surface.unwrap();
        assert_eq!(retained.surface_revision, 1);
        assert_eq!(retained.frame.cells[1].symbol, "b");
    }
    #[test]
    fn repeated_hyperlinks_cannot_expand_ansi_unboundedly_or_commit_a_truncated_frame() {
        let cells: Vec<_> = (0..10_000)
            .map(|i| CellData {
                symbol: "A".into(),
                fg: 0,
                bg: 0,
                modifier: 0,
                skip: false,
                hyperlink: (i % 2 == 0).then_some(0),
            })
            .collect();
        let frame = FrameData {
            width: 100,
            height: 100,
            cells,
            cursor: None,
            hyperlinks: vec!["x".repeat(4096)],
            graphics: vec![],
        };
        let encoded = render_ansi::BlitEncoder::new().encode(&frame, false);
        assert!(encoded.overflowed);
        assert!(encoded.bytes.len() <= capped_writer::MAX_RENDER_BYTES);
        let snapshot: ClientShellSnapshot = serde_json::from_value(json!({
            "boot_id":"boot","revision":1,"config_diagnostic":null,"product_announcement":null,"update_available":null,
            "update_install_command":"","server_keybindings_toml":null,"latest_release_notes_available":false,
            "integration_updates_available":false,"worktree_directory":"","release_notes":null,
            "focused_workspace_id":null,"focused_tab_id":null,"focused_pane_id":null,
            "tab_bar_right":[],"tab_bar_right_separator":"","agent_view_label":null,
            "agent_order":[],"workspaces":[],"tabs":[],"panes":[],"agents":[],"commands":[]
        })).unwrap();
        let mut client = Client::new(100, 100);
        client.snapshot = Some(snapshot);
        client.surface = Some(PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame,
            panes: vec![],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        });
        let mut output = Output::default();
        assert!(client.render(&mut output).is_err());
        assert!(output.ansi.is_empty());
        assert!(
            !client
                .encoder
                .is_current(&client.surface.as_ref().unwrap().frame)
        );
    }
    #[test]
    fn coalesced_render_overflow_does_not_commit_an_unpublished_baseline() {
        let snapshot: ClientShellSnapshot = serde_json::from_value(json!({
            "boot_id":"boot","revision":1,"config_diagnostic":null,"product_announcement":null,"update_available":null,
            "update_install_command":"","server_keybindings_toml":null,"latest_release_notes_available":false,
            "integration_updates_available":false,"worktree_directory":"","release_notes":null,
            "focused_workspace_id":null,"focused_tab_id":null,"focused_pane_id":null,
            "tab_bar_right":[],"tab_bar_right_separator":"","agent_view_label":null,
            "agent_order":[],"workspaces":[],"tabs":[],"panes":[],"agents":[],"commands":[]
        })).unwrap();
        let frame = FrameData {
            width: 1,
            height: 1,
            cells: vec![CellData {
                symbol: "a".into(),
                fg: 0,
                bg: 0,
                modifier: 0,
                skip: false,
                hyperlink: None,
            }],
            cursor: None,
            hyperlinks: vec![],
            graphics: vec![],
        };
        let mut client = Client::new(1, 1);
        client.snapshot = Some(snapshot);
        client.surface = Some(PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame,
            panes: vec![],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        });
        let mut output = Output {
            ansi: vec![b'x'; capped_writer::MAX_RENDER_BYTES],
            ..Output::default()
        };
        assert!(client.render(&mut output).is_err());
        assert_eq!(output.ansi.len(), capped_writer::MAX_RENDER_BYTES);
        assert!(output.ansi.iter().all(|&byte| byte == b'x'));
        assert!(
            !client
                .encoder
                .is_current(&client.surface.as_ref().unwrap().frame)
        );
    }
    #[test]
    fn maximum_complex_surface_renders_under_the_bounded_budget() {
        let cells: Vec<_> = (0..150_000u32)
            .map(|i| CellData {
                symbol: "A".into(),
                fg: 0x0200_0000 | (i.wrapping_mul(97) & 0x00ff_ffff),
                bg: 0x0200_0000 | (i.wrapping_mul(7919) & 0x00ff_ffff),
                modifier: if i % 2 == 0 { 1 } else { 2 },
                skip: false,
                hyperlink: (i % 2 == 0).then_some(0),
            })
            .collect();
        let frame = FrameData {
            width: 500,
            height: 300,
            cells,
            cursor: None,
            hyperlinks: vec!["https://example.test/valid".into()],
            graphics: vec![],
        };
        let required = render_ansi::nova_count_blit_for_test(&frame);
        println!("maximum complex surface generated ANSI bytes: {required}");
        assert!(required < capped_writer::MAX_RENDER_BYTES);
        let encoded = render_ansi::BlitEncoder::new().encode(&frame, false);
        assert!(!encoded.overflowed);
        assert_eq!(encoded.bytes.len(), required);
        assert!(encoded.bytes.len() <= capped_writer::MAX_RENDER_BYTES);
    }
    #[test]
    fn coalesced_large_valid_frames_can_be_published_one_at_a_time() {
        let snapshot: ClientShellSnapshot = serde_json::from_value(json!({
            "boot_id":"boot","revision":1,"config_diagnostic":null,"product_announcement":null,"update_available":null,
            "update_install_command":"","server_keybindings_toml":null,"latest_release_notes_available":false,
            "integration_updates_available":false,"worktree_directory":"","release_notes":null,
            "focused_workspace_id":null,"focused_tab_id":null,"focused_pane_id":null,
            "tab_bar_right":[],"tab_bar_right_separator":"","agent_view_label":null,
            "agent_order":[],"workspaces":[],"tabs":[],"panes":[],"agents":[],"commands":[]
        })).unwrap();
        let cells: Vec<_> = (0..100_000u32)
            .map(|i| CellData {
                symbol: "A".into(),
                fg: 0x0200_0000 | (i.wrapping_mul(97) & 0x00ff_ffff),
                bg: 0x0200_0000 | (i.wrapping_mul(7919) & 0x00ff_ffff),
                modifier: if i % 2 == 0 { 1 } else { 2 },
                skip: false,
                hyperlink: (i % 2 == 0).then_some(0),
            })
            .collect();
        let mut frame = FrameData {
            width: 500,
            height: 200,
            cells,
            cursor: None,
            hyperlinks: vec!["https://example.test/valid".into()],
            graphics: vec![],
        };
        let mut wire = Vec::new();
        for revision in 1..=3 {
            for cell in &mut frame.cells {
                cell.fg ^= 1;
            }
            let surface = PaneSurfaceFrame {
                boot_id: "boot".into(),
                projection_revision: 1,
                surface_revision: revision,
                frame: frame.clone(),
                panes: vec![],
                splits: vec![],
                popup: None,
                graphics: SurfaceGraphicsScene::default(),
            };
            protocol::write_message(&mut wire, &ServerMessage::PaneSurface(surface)).unwrap();
        }
        let mut client = Client::new(500, 200);
        client.snapshot = Some(snapshot);
        client.welcome=Some(serde_json::from_value(json!({"generation":1,"server_version":"test",
            "snapshot_codec":SNAPSHOT_CODEC_V1,"surface_codec":SURFACE_CODEC_V1,
            "input_codec":INPUT_CODEC_V1,"blob_codec":BLOB_CODEC_V1,"methods":[],"capabilities":[]})).unwrap());
        let mut offset = 0usize;
        let mut total_ansi = 0usize;
        for _ in 0..3 {
            let (output, consumed) = client.receive_one(&wire[offset..]).unwrap();
            assert!(consumed > 0);
            offset += consumed;
            assert!(
                !output.ansi.is_empty() && output.ansi.len() <= capped_writer::MAX_RENDER_BYTES
            );
            assert_eq!(output.ready, Some(true));
            total_ansi += output.ansi.len();
            // The transport feeds/publishes this output before the next call.
        }
        assert_eq!(offset, wire.len());
        assert!(total_ansi > capped_writer::MAX_RENDER_BYTES);
        assert_eq!(client.surface.as_ref().unwrap().surface_revision, 3);
    }
    #[test]
    fn resize_invalidates_surface_and_input() {
        let mut c = Client::new(80, 24);
        let out = c.resize(100, 30).unwrap();
        assert!(
            !serde_json::from_slice::<serde_json::Value>(&out.state).unwrap()["ready"]
                .as_bool()
                .unwrap()
        );
        assert!(c.input(b"do not send").unwrap().outbound.is_empty());
    }
    #[test]
    fn resize_repaints_retained_surface_and_waits_for_matching_geometry() {
        let mut c = Client::new(1, 1);
        c.snapshot = Some(serde_json::from_value(json!({
            "boot_id":"boot","revision":1,"config_diagnostic":null,"product_announcement":null,"update_available":null,
            "update_install_command":"","server_keybindings_toml":null,"latest_release_notes_available":false,
            "integration_updates_available":false,"worktree_directory":"","release_notes":null,
            "focused_workspace_id":null,"focused_tab_id":null,"focused_pane_id":"pane",
            "tab_bar_right":[],"tab_bar_right_separator":"","agent_view_label":null,
            "agent_order":[],"workspaces":[],"tabs":[],"panes":[],"agents":[],"commands":[]
        })).unwrap());
        let rect = SurfaceRect {
            x: 0,
            y: 0,
            width: 1,
            height: 1,
        };
        let cell = CellData {
            symbol: "X".into(),
            fg: 0,
            bg: 0,
            modifier: 0,
            skip: false,
            hyperlink: None,
        };
        c.surface = Some(PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame: FrameData {
                width: 1,
                height: 1,
                cells: vec![cell.clone()],
                cursor: None,
                hyperlinks: vec![],
                graphics: vec![],
            },
            panes: vec![PaneSurfacePane {
                pane_id: "pane".into(),
                content_revision: 1,
                rect,
                inner_rect: rect,
                scrollbar_rect: None,
                scroll: None,
                focused: true,
                mouse_reporting: false,
                sgr_pixel_mouse: false,
                alternate_screen_active: false,
                pixel_width: 0,
                pixel_height: 0,
            }],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        });
        c.render(&mut Output::default()).unwrap();

        // A recreated view may report the same size: publish the complete
        // surface and the new epoch immediately, without waiting for traffic.
        let same = c.resize(1, 1).unwrap();
        assert!(same.ansi.contains(&b'X'));
        assert_eq!(same.ready, Some(true));
        assert_eq!(same.target_epoch, Some(1));
        assert!(!c.repaint_pending);

        // A different geometry must not repaint an old-sized surface or
        // authorize input, but its first coherent surface must repaint fully.
        let changed = c.resize(2, 1).unwrap();
        assert!(changed.ansi.is_empty());
        assert_eq!(changed.ready, Some(false));
        assert!(c.repaint_pending);
        let frame = &mut c.surface.as_mut().unwrap().frame;
        frame.width = 2;
        frame.cells.push(cell);
        let mut recovered = Output::default();
        c.render(&mut recovered).unwrap();
        assert_eq!(recovered.ansi.iter().filter(|&&b| b == b'X').count(), 2);
        assert!(!c.repaint_pending);
    }
    #[test]
    fn projection_change_discards_partial_input_before_focus_returns() {
        let mut c = Client::new(1, 1);
        c.welcome = Some(
            serde_json::from_value(json!({
                "generation":1,"server_version":"test", "snapshot_codec":SNAPSHOT_CODEC_V1,
                "surface_codec":SURFACE_CODEC_V1,"input_codec":INPUT_CODEC_V1,
                "blob_codec":BLOB_CODEC_V1,"methods":[],"capabilities":[]
            }))
            .unwrap(),
        );
        let mut snapshot: ClientShellSnapshot = serde_json::from_value(json!({
            "boot_id":"boot","revision":1,"config_diagnostic":null,
            "product_announcement":null,"update_available":null,"update_install_command":"",
            "server_keybindings_toml":null,"latest_release_notes_available":false,
            "integration_updates_available":false,"worktree_directory":"","release_notes":null,
            "focused_workspace_id":null,"focused_tab_id":null,"focused_pane_id":"pane-a",
            "tab_bar_right":[],"tab_bar_right_separator":"","agent_view_label":null,
            "agent_order":[],"workspaces":[],"tabs":[],"panes":[],"agents":[],"commands":[]
        }))
        .unwrap();
        let rect = SurfaceRect {
            x: 0,
            y: 0,
            width: 1,
            height: 1,
        };
        let mut surface = PaneSurfaceFrame {
            boot_id: "boot".into(),
            projection_revision: 1,
            surface_revision: 1,
            frame: FrameData {
                width: 1,
                height: 1,
                cells: vec![CellData {
                    symbol: " ".into(),
                    fg: 0,
                    bg: 0,
                    modifier: 0,
                    skip: false,
                    hyperlink: None,
                }],
                cursor: None,
                hyperlinks: vec![],
                graphics: vec![],
            },
            panes: vec![PaneSurfacePane {
                pane_id: "pane-a".into(),
                content_revision: 1,
                rect,
                inner_rect: rect,
                scrollbar_rect: None,
                scroll: None,
                focused: true,
                mouse_reporting: false,
                sgr_pixel_mouse: false,
                alternate_screen_active: false,
                pixel_width: 0,
                pixel_height: 0,
            }],
            splits: vec![],
            popup: None,
            graphics: SurfaceGraphicsScene::default(),
        };
        c.snapshot = Some(snapshot.clone());
        c.surface = Some(surface.clone());
        let before = c.target_epoch().unwrap();
        assert!(c.input(b"\x1b[").unwrap().outbound.is_empty());
        assert!(c.input_parser.pending_len() > 0);
        snapshot.revision = 2;
        snapshot.focused_pane_id = Some("pane-b".into());
        let mut frame = Vec::new();
        protocol::write_message(
            &mut frame,
            &ServerMessage::EndpointControl {
                kind: ENDPOINT_SNAPSHOT_KIND.into(),
                data: serde_json::to_string(&snapshot).unwrap(),
            },
        )
        .unwrap();
        let mut pending = Client::new(1, 1);
        pending.welcome = c.welcome.clone();
        pending.snapshot = c.snapshot.clone();
        pending.surface = c.surface.clone();
        let state = pending.receive(&frame).unwrap().state;
        assert_eq!(pending.target_epoch(), None);
        assert!(
            serde_json::from_slice::<serde_json::Value>(&state).unwrap()["target_epoch"].is_null()
        );
        surface.projection_revision = 2;
        surface.surface_revision = 2;
        surface.panes[0].pane_id = "pane-b".into();
        protocol::write_message(&mut frame, &ServerMessage::PaneSurface(surface.clone())).unwrap();
        snapshot.revision = 3;
        snapshot.focused_pane_id = Some("pane-a".into());
        protocol::write_message(
            &mut frame,
            &ServerMessage::EndpointControl {
                kind: ENDPOINT_SNAPSHOT_KIND.into(),
                data: serde_json::to_string(&snapshot).unwrap(),
            },
        )
        .unwrap();
        surface.projection_revision = 3;
        surface.surface_revision = 3;
        surface.panes[0].pane_id = "pane-a".into();
        protocol::write_message(&mut frame, &ServerMessage::PaneSurface(surface)).unwrap();
        let result = c.receive(&frame).unwrap();
        assert_eq!(c.input_parser.pending_len(), 0);
        assert!(c.input_target.is_none());
        assert_eq!(c.target_epoch(), Some(before + 4));
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&result.state).unwrap()["target_epoch"],
            before + 4
        );
        assert!(c.flush().unwrap().outbound.is_empty());
    }
    #[test]
    fn isolated_fixture_cleanup_runs_on_unwind() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let program = dir.path().join("cleanup");
        std::fs::write(
            &program,
            "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$XDG_STATE_HOME\"\n",
        )
        .unwrap();
        std::fs::set_permissions(&program, std::fs::Permissions::from_mode(0o700)).unwrap();
        let root = dir.path().to_path_buf();
        let _ = std::panic::catch_unwind(|| {
            let _guard = FixtureCleanup {
                binary: program,
                root: root.clone(),
                runtime: root.clone(),
            };
            panic!("exercise cleanup");
        });
        assert_eq!(
            std::fs::read_to_string(root.join("state")).unwrap(),
            "session\nstop\nnovascale-test-fixture\n--json\n"
        );
    }
    #[test]
    #[ignore = "requires NOVA_HERDR_TEST_BIN and starts a disposable isolated server"]
    fn isolated_live_bridge_first_frame_and_reattach() {
        use std::io::{Read, Write};
        use std::os::unix::fs::PermissionsExt;
        use std::process::{Command, Stdio};
        use std::sync::mpsc;
        use std::time::{Duration, Instant};
        let binary = std::env::var("NOVA_HERDR_TEST_BIN").expect("set NOVA_HERDR_TEST_BIN");
        let dir = tempfile::tempdir().unwrap();
        let runtime = dir.path().join("run");
        std::fs::create_dir(&runtime).unwrap();
        std::fs::set_permissions(&runtime, std::fs::Permissions::from_mode(0o700)).unwrap();
        let _cleanup = FixtureCleanup {
            binary: binary.clone().into(),
            root: dir.path().into(),
            runtime: runtime.clone(),
        };
        let spawn_bridge = || {
            Command::new(&binary)
                .args([
                    "--session",
                    "novascale-test-fixture",
                    "remote-client-bridge",
                ])
                .env("XDG_CONFIG_HOME", dir.path().join("config"))
                .env("XDG_STATE_HOME", dir.path().join("state"))
                .env("XDG_DATA_HOME", dir.path().join("data"))
                .env("XDG_RUNTIME_DIR", &runtime)
                .env("SHELL", "/bin/sh")
                .env_remove("HERDR_SESSION")
                .env_remove("HERDR_SOCKET_PATH")
                .env_remove("HERDR_CLIENT_SOCKET_PATH")
                .env_remove("HERDR_CONFIG_PATH")
                .current_dir(dir.path())
                .stdin(Stdio::piped())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
                .unwrap()
        };
        let attach = |exercise_input: bool| {
            let mut child = spawn_bridge();
            let mut stdin = child.stdin.take().unwrap();
            let mut stdout = child.stdout.take().unwrap();
            let (tx, rx) = mpsc::channel();
            std::thread::spawn(move || {
                let mut buf = [0u8; 8192];
                loop {
                    match stdout.read(&mut buf) {
                        Ok(0) | Err(_) => break,
                        Ok(n) => {
                            if tx.send(buf[..n].to_vec()).is_err() {
                                break;
                            }
                        }
                    }
                }
            });
            let mut client = Client::new(80, 24);
            stdin.write_all(&client.hello().unwrap().outbound).unwrap();
            let deadline = Instant::now() + Duration::from_secs(20);
            let mut state = None;
            while Instant::now() < deadline {
                let Ok(data) = rx.recv_timeout(Duration::from_millis(300)) else {
                    continue;
                };
                let output = client.receive(&data).expect("decode Herdr output");
                stdin.write_all(&output.outbound).unwrap();
                if !output.state.is_empty() {
                    let value: serde_json::Value = serde_json::from_slice(&output.state).unwrap();
                    if value["ready"] == true {
                        state = Some(value);
                        break;
                    }
                }
            }
            let state = state.expect("no coherent Herdr frame");
            if !exercise_input {
                let (_, surface) = client.coherent().unwrap();
                let pane = surface
                    .panes
                    .iter()
                    .find(|p| {
                        Some(&p.pane_id)
                            == client.snapshot.as_ref().unwrap().focused_pane_id.as_ref()
                    })
                    .unwrap();
                assert_eq!(
                    pane.scroll.unwrap().offset_from_bottom,
                    0,
                    "reattach must open at the live bottom"
                );
                let text: String = surface
                    .frame
                    .cells
                    .iter()
                    .map(|cell| cell.symbol.as_str())
                    .collect();
                assert!(
                    text.contains("NOVASCALE_713"),
                    "reattach hid the latest output"
                );
            }
            assert!(
                dir.path()
                    .join("config/herdr/sessions/novascale-test-fixture/herdr-client.sock")
                    .exists(),
                "fixture did not use the isolated XDG session namespace"
            );
            if exercise_input {
                // The expected text is absent from the command itself, so seeing
                // it proves the shell executed input rather than merely echoing it.
                let input = client
                    .input(b"seq 1 100; printf 'NOVA%s_%s\\n' SCALE 713\r")
                    .unwrap();
                stdin.write_all(&input.outbound).unwrap();
                let deadline = Instant::now() + Duration::from_secs(10);
                let mut found = false;
                while Instant::now() < deadline {
                    let Ok(data) = rx.recv_timeout(Duration::from_millis(300)) else {
                        continue;
                    };
                    client.receive(&data).expect("decode input response");
                    if let Some((_, surface)) = client.coherent() {
                        let text: String = surface
                            .frame
                            .cells
                            .iter()
                            .map(|cell| cell.symbol.as_str())
                            .collect();
                        if text.contains("NOVASCALE_713") {
                            found = true;
                            break;
                        }
                    }
                }
                assert!(
                    found,
                    "typed command was not reflected on the terminal surface"
                );
                // Reproduce attaching to a session that another client left in
                // scrollback, without typing any terminal escape/key shortcuts.
                let mut scroll = Output::default();
                send(
                    &mut scroll,
                    ClientMessage::ClientShellEndpointRequest {
                        boot_id: state["boot_id"].as_str().unwrap().into(),
                        request: json!({"id":"fixture-scroll", "method":"pane.scroll", "params":{
                        "pane_id":state["focused_pane_id"], "offset_from_bottom":60}})
                        .to_string(),
                    },
                )
                .unwrap();
                stdin.write_all(&scroll.outbound).unwrap();
                let deadline = Instant::now() + Duration::from_secs(10);
                let mut scrolled = false;
                while Instant::now() < deadline {
                    let Ok(data) = rx.recv_timeout(Duration::from_millis(300)) else {
                        continue;
                    };
                    let output = client.receive(&data).expect("decode scrolled surface");
                    stdin.write_all(&output.outbound).unwrap();
                    if client.coherent().is_some_and(|(_, s)| {
                        s.panes
                            .iter()
                            .any(|p| p.scroll.is_some_and(|m| m.offset_from_bottom > 0))
                    }) {
                        scrolled = true;
                        break;
                    }
                }
                assert!(scrolled, "fixture could not leave the pane in scrollback");
                stdin
                    .write_all(&client.resize(100, 30).unwrap().outbound)
                    .unwrap();
                let deadline = Instant::now() + Duration::from_secs(10);
                let mut resized = false;
                while Instant::now() < deadline {
                    let Ok(data) = rx.recv_timeout(Duration::from_millis(300)) else {
                        continue;
                    };
                    client.receive(&data).expect("decode resized surface");
                    if client.coherent().is_some() {
                        resized = true;
                        break;
                    }
                }
                assert!(resized, "no coherent resized surface");
                assert!(
                    client
                        .coherent()
                        .unwrap()
                        .1
                        .panes
                        .iter()
                        .any(|p| p.scroll.is_some_and(|m| m.offset_from_bottom > 0)),
                    "ordinary resize must preserve manual scrollback"
                );
                // Reopen the retained bridge, exactly as the Sessions screen
                // does. The RPC response need not advance projection revision.
                let out = client.resume_viewport().unwrap();
                assert_eq!(out.ready, Some(false));
                stdin.write_all(&out.outbound).unwrap();
                let deadline = Instant::now() + Duration::from_secs(10);
                let mut restored = false;
                while Instant::now() < deadline {
                    let Ok(data) = rx.recv_timeout(Duration::from_millis(300)) else {
                        continue;
                    };
                    let output = client.receive(&data).expect("decode restored viewport");
                    stdin.write_all(&output.outbound).unwrap();
                    if output.ready == Some(true) {
                        let (_, surface) = client.coherent().unwrap();
                        assert!(
                            surface
                                .panes
                                .iter()
                                .all(|p| p.scroll.is_none_or(|s| s.offset_from_bottom == 0))
                        );
                        let text: String = surface
                            .frame
                            .cells
                            .iter()
                            .map(|cell| cell.symbol.as_str())
                            .collect();
                        assert!(
                            text.contains("NOVASCALE_713"),
                            "retained reopen hid the latest output"
                        );
                        restored = true;
                        break;
                    }
                }
                assert!(restored, "retained reopen did not return to the bottom");
            }
            stdin.write_all(&client.detach().unwrap().outbound).unwrap();
            drop(stdin);
            let _ = child.wait();
            state
        };
        let first = attach(true);
        let second = attach(false);
        assert_eq!(first["boot_id"], second["boot_id"]);
        assert_eq!(first["focused_pane_id"], second["focused_pane_id"]);
        drop(_cleanup);
        let stopped = Command::new(&binary)
            .args(["session", "list", "--json"])
            .env("XDG_CONFIG_HOME", dir.path().join("config"))
            .env("XDG_STATE_HOME", dir.path().join("state"))
            .env("XDG_DATA_HOME", dir.path().join("data"))
            .env("XDG_RUNTIME_DIR", &runtime)
            .env_remove("HERDR_SESSION")
            .env_remove("HERDR_SOCKET_PATH")
            .env_remove("HERDR_CLIENT_SOCKET_PATH")
            .env_remove("HERDR_CONFIG_PATH")
            .current_dir(dir.path())
            .output()
            .unwrap();
        assert!(stopped.status.success());
        let list: serde_json::Value = serde_json::from_slice(&stopped.stdout).unwrap();
        assert!(list["sessions"].as_array().unwrap().iter().all(|entry|
            entry["name"] != "novascale-test-fixture" || entry["running"] == false));
    }
}
