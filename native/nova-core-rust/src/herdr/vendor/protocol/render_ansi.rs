// Herdr v0.9.1, Apache-2.0; see third_party/herdr.
//! Frame blitting — renders FrameData to the terminal using diff-based updates.
//!
//! The blitting strategy:
//! 1. On the first frame, write the entire buffer (full redraw).
//! 2. On subsequent frames, diff against the last frame and only write
//!    the cells that changed.
//! 3. Wrap each frame in synchronized output so terminals that support it do
//!    not expose intermediate cursor positions while the frame is painted.
//! 4. Before writing any cells, hide the cursor to avoid stray cursor
//!    artifacts on terminals that render the hardware cursor at intermediate
//!    `CUP` positions during the frame stream.
//! 5. After writing all changed cells, restore the final cursor visibility
//!    and position from `frame.cursor`.
//! 6. On platforms that need it, repeat the final cursor anchor after ending
//!    synchronized output so external IMEs can place candidate windows at the
//!    real input position. Windows Terminal exposes that repeat as visible
//!    cursor movement during active TUI repaints, so Windows skips it.
//!
//! Escape sequences used:
//! - `CSI H` (CUP) — move cursor to (row, col)
//! - `CSI m` (SGR) — set graphic rendition (colors, bold, etc.)
//! - `CSI ? 2026 h/l` — begin/end synchronized output
//! - `CSI Ps SP q` — DECSCUSR cursor shape
//! - `ESC ] 52 ; c ; <base64> BEL` — OSC 52 clipboard write
//!
//! The goal is minimal output: skip unchanged cells, batch adjacent changes,
//! and minimize cursor movement.

use crate::herdr::capped_writer::CappedWriter;
use std::cmp;
use std::io::Write;

use unicode_width::UnicodeWidthStr;

use crate::herdr::protocol::{
    CellData, CursorState, FrameData, PaneSurfacePatchRow, underline_style_from_modifier,
};

const REVERSED_MODIFIER: u16 = 1 << 6;
const SYNC_OUTPUT_END: &[u8] = b"\x1b[?2026l";

pub(crate) fn final_sync_output_end(bytes: &[u8]) -> Option<usize> {
    bytes
        .windows(SYNC_OUTPUT_END.len())
        .rposition(|window| window == SYNC_OUTPUT_END)
}

/// Bytes produced by a [`BlitEncoder`] for one terminal frame.
pub(crate) struct EncodedBlit {
    /// Terminal escape bytes ready to write to the host terminal.
    pub(crate) bytes: Vec<u8>,
    pub(crate) overflowed: bool,
    /// Whether this frame was encoded as a full redraw.
    pub(crate) full: bool,
    next_last_visible_cursor: Option<(u16, u16)>,
    next_last_cursor_shape: u8,
}

/// Stateful encoder that diffs semantic frames into terminal ANSI bytes.
#[derive(Default)]
pub(crate) struct BlitEncoder {
    last_frame: Option<FrameData>,
    last_visible_cursor: Option<(u16, u16)>,
    last_cursor_shape: u8,
}

impl BlitEncoder {
    pub(crate) fn new() -> Self {
        Self::default()
    }

    pub(crate) fn encode(&self, frame: &FrameData, repaint: bool) -> EncodedBlit {
        self.encode_inner(frame, repaint, false)
    }

    pub(crate) fn encode_with_suppressed_visible_cursor(
        &self,
        frame: &FrameData,
        repaint: bool,
    ) -> EncodedBlit {
        self.encode_inner(frame, repaint, true)
    }

    fn encode_inner(
        &self,
        frame: &FrameData,
        repaint: bool,
        suppress_visible_cursor: bool,
    ) -> EncodedBlit {
        let previous_frame = self.last_frame.as_ref();
        let prev = if repaint { None } else { previous_frame };
        let full = repaint
            || prev.is_none()
            || prev.is_some_and(|p| p.width != frame.width || p.height != frame.height);
        let clear_before_full_redraw = previous_frame.is_none();
        let prof_stats = crate::herdr::render_prof::enabled()
            .then(|| compute_prof_blit_stats(frame, prev, full));
        let prof_started = crate::herdr::render_prof::timer();
        let mut bytes = CappedWriter::new();
        let mut next_last_visible_cursor = self.last_visible_cursor;
        let mut next_last_cursor_shape = self.last_cursor_shape;
        blit_frame_to_with_cursor_memory_and_clear_policy(
            &mut bytes,
            frame,
            prev,
            &mut next_last_visible_cursor,
            &mut next_last_cursor_shape,
            repeat_ime_anchor_after_sync(),
            clear_before_full_redraw,
            suppress_visible_cursor,
        );
        if let Some(stats) = prof_stats {
            crate::herdr::render_prof::duration_since("ansi_encode.total", prof_started);
            crate::herdr::render_prof::counter("ansi_encode.bytes", bytes.len() as u64);
            crate::herdr::render_prof::counter("ansi_encode.scanned_cells", stats.scanned_cells);
            crate::herdr::render_prof::counter("ansi_encode.changed_cells", stats.changed_cells);
            crate::herdr::render_prof::counter("ansi_encode.changed_runs", stats.changed_runs);
            if full {
                crate::herdr::render_prof::event("ansi_encode.full");
            } else {
                crate::herdr::render_prof::event("ansi_encode.partial");
            }
        }
        let (bytes, overflowed) = bytes.finish();
        EncodedBlit {
            bytes,
            overflowed,
            full,
            next_last_visible_cursor,
            next_last_cursor_shape,
        }
    }

    pub(crate) fn commit(&mut self, frame: FrameData, encoded: EncodedBlit) {
        self.last_visible_cursor = encoded.next_last_visible_cursor;
        self.last_cursor_shape = encoded.next_last_cursor_shape;
        self.last_frame = Some(frame);
    }

    pub(crate) fn is_current(&self, frame: &FrameData) -> bool {
        self.last_frame.as_ref() == Some(frame)
    }

    pub(crate) fn encode_patch(
        &self,
        rows: &[PaneSurfacePatchRow],
        cursor: Option<CursorState>,
        suppress_visible_cursor: bool,
    ) -> Option<EncodedBlit> {
        let frame = self.last_frame.as_ref()?;
        if rows.iter().any(|row| !patch_row_fits(frame, row)) || patch_rows_overlap(rows) {
            return None;
        }
        let mut bytes = CappedWriter::new();
        let mut next_last_visible_cursor = self.last_visible_cursor;
        let mut next_last_cursor_shape = self.last_cursor_shape;
        blit_patch_to(
            &mut bytes,
            frame,
            rows,
            cursor,
            &mut next_last_visible_cursor,
            &mut next_last_cursor_shape,
            repeat_ime_anchor_after_sync(),
            suppress_visible_cursor,
        );
        let (bytes, overflowed) = bytes.finish();
        Some(EncodedBlit {
            bytes,
            overflowed,
            full: false,
            next_last_visible_cursor,
            next_last_cursor_shape,
        })
    }

    pub(crate) fn patch_rows_with_drawn_cursor(
        &self,
        rows: &[PaneSurfacePatchRow],
        cursor: Option<&CursorState>,
    ) -> Option<Vec<PaneSurfacePatchRow>> {
        let frame = self.last_frame.as_ref()?;
        let mut rows = rows.to_vec();
        let previous = frame
            .cursor
            .as_ref()
            .filter(|cursor| cursor.visible)
            .map(|cursor| clamp_cursor_position(frame, cursor.x, cursor.y));
        let next = cursor
            .filter(|cursor| cursor.visible)
            .map(|cursor| clamp_cursor_position(frame, cursor.x, cursor.y));

        if let Some((x, y)) = previous.filter(|position| Some(*position) != next) {
            if patch_cell_mut(&mut rows, x, y).is_none() {
                let mut cell = frame.cells.get(frame_cell_index(frame, x, y)?)?.clone();
                cell.modifier ^= REVERSED_MODIFIER;
                rows.push(PaneSurfacePatchRow {
                    x,
                    y,
                    cells: vec![cell],
                });
            }
        }
        if let Some((x, y)) = next {
            if let Some(cell) = patch_cell_mut(&mut rows, x, y) {
                cell.modifier ^= REVERSED_MODIFIER;
            } else if previous != next {
                let mut cell = frame.cells.get(frame_cell_index(frame, x, y)?)?.clone();
                cell.modifier ^= REVERSED_MODIFIER;
                rows.push(PaneSurfacePatchRow {
                    x,
                    y,
                    cells: vec![cell],
                });
            }
        }
        Some(rows)
    }

    pub(crate) fn commit_patch(
        &mut self,
        rows: &[PaneSurfacePatchRow],
        cursor: Option<CursorState>,
        encoded: EncodedBlit,
    ) -> bool {
        let Some(frame) = self.last_frame.as_mut() else {
            return false;
        };
        for row in rows {
            let start = usize::from(row.y) * usize::from(frame.width) + usize::from(row.x);
            let end = start + row.cells.len();
            let Some(target) = frame.cells.get_mut(start..end) else {
                return false;
            };
            target.clone_from_slice(&row.cells);
        }
        frame.cursor = cursor;
        self.last_visible_cursor = encoded.next_last_visible_cursor;
        self.last_cursor_shape = encoded.next_last_cursor_shape;
        true
    }
}

pub(crate) fn frame_with_drawn_cursor(mut frame: FrameData) -> FrameData {
    if let Some(cursor) = frame.cursor.as_ref().filter(|cursor| cursor.visible) {
        let (x, y) = clamp_cursor_position(&frame, cursor.x, cursor.y);
        let idx = (y as usize)
            .saturating_mul(frame.width as usize)
            .saturating_add(x as usize);
        if let Some(cell) = frame.cells.get_mut(idx) {
            cell.modifier ^= REVERSED_MODIFIER;
        }
    }
    frame
}

#[derive(Clone, Copy, Default)]
struct ProfBlitStats {
    scanned_cells: u64,
    changed_cells: u64,
    changed_runs: u64,
}

fn compute_prof_blit_stats(
    frame: &FrameData,
    prev: Option<&FrameData>,
    full: bool,
) -> ProfBlitStats {
    let Some(prev) = prev.filter(|_| !full) else {
        let changed_cells = frame.cells.iter().filter(|cell| !cell.skip).count() as u64;
        return ProfBlitStats {
            scanned_cells: frame.cells.len() as u64,
            changed_cells,
            changed_runs: changed_cells,
        };
    };
    if prev.width != frame.width || prev.height != frame.height {
        let changed_cells = frame.cells.iter().filter(|cell| !cell.skip).count() as u64;
        return ProfBlitStats {
            scanned_cells: frame.cells.len() as u64,
            changed_cells,
            changed_runs: changed_cells,
        };
    }

    let sanitized_hyperlinks = sanitized_frame_hyperlinks(frame);
    let prev_sanitized_hyperlinks = sanitized_frame_hyperlinks(prev);
    let mut stats = ProfBlitStats {
        scanned_cells: frame.cells.len() as u64,
        changed_cells: 0,
        changed_runs: 0,
    };
    for row in 0..frame.height {
        let mut in_run = false;
        let mut invalidated = 0usize;
        let mut to_skip = 0usize;
        for col in 0..frame.width {
            let idx = (row as usize) * (frame.width as usize) + (col as usize);
            let cell = &frame.cells[idx];
            let prev_cell = &prev.cells[idx];
            let changed = !cell.skip
                && (!cells_visually_equal(
                    &sanitized_hyperlinks,
                    cell,
                    &prev_sanitized_hyperlinks,
                    prev_cell,
                ) || invalidated > 0)
                && to_skip == 0;
            if changed {
                stats.changed_cells += 1;
                if !in_run {
                    stats.changed_runs += 1;
                    in_run = true;
                }
            } else {
                in_run = false;
            }
            to_skip = cell_width(cell).saturating_sub(1);
            let affected_width = cmp::max(cell_width(cell), cell_width(prev_cell));
            invalidated = cmp::max(affected_width, invalidated).saturating_sub(1);
        }
    }
    stats
}

// ---------------------------------------------------------------------------
// Color → escape sequence
// ---------------------------------------------------------------------------

/// Converts a packed u32 color to an SGR escape sequence fragment.
///
/// Returns a string like `38;5;123` (indexed) or `38;2;255;128;64` (RGB)
/// or `39` (reset), without the leading `\x1b[` or trailing `m`.
fn color_to_sgr_fg(val: u32) -> String {
    match val >> 24 {
        0x00 => match val & 0xFF {
            0x00 => "39".to_owned(), // Reset
            0x01 => "30".to_owned(), // Black
            0x02 => "31".to_owned(), // Red
            0x03 => "32".to_owned(), // Green
            0x04 => "33".to_owned(), // Yellow
            0x05 => "34".to_owned(), // Blue
            0x06 => "35".to_owned(), // Magenta
            0x07 => "36".to_owned(), // Cyan
            0x08 => "37".to_owned(), // Gray (light gray)
            0x09 => "90".to_owned(), // DarkGray
            0x0A => "91".to_owned(), // LightRed
            0x0B => "92".to_owned(), // LightGreen
            0x0C => "93".to_owned(), // LightYellow
            0x0D => "94".to_owned(), // LightBlue
            0x0E => "95".to_owned(), // LightMagenta
            0x0F => "96".to_owned(), // LightCyan
            0x10 => "97".to_owned(), // White
            _ => "39".to_owned(),    // Unknown → Reset
        },
        0x01 => format!("38;5;{}", val & 0xFF), // Indexed
        0x02 => {
            // RGB
            let r = (val >> 16) & 0xFF;
            let g = (val >> 8) & 0xFF;
            let b = val & 0xFF;
            format!("38;2;{r};{g};{b}")
        }
        _ => "39".to_owned(), // Unknown → Reset
    }
}

/// Converts a packed u32 color to a background SGR fragment.
fn color_to_sgr_bg(val: u32) -> String {
    match val >> 24 {
        0x00 => match val & 0xFF {
            0x00 => "49".to_owned(),  // Reset
            0x01 => "40".to_owned(),  // Black
            0x02 => "41".to_owned(),  // Red
            0x03 => "42".to_owned(),  // Green
            0x04 => "43".to_owned(),  // Yellow
            0x05 => "44".to_owned(),  // Blue
            0x06 => "45".to_owned(),  // Magenta
            0x07 => "46".to_owned(),  // Cyan
            0x08 => "47".to_owned(),  // Gray (light gray)
            0x09 => "100".to_owned(), // DarkGray
            0x0A => "101".to_owned(), // LightRed
            0x0B => "102".to_owned(), // LightGreen
            0x0C => "103".to_owned(), // LightYellow
            0x0D => "104".to_owned(), // LightBlue
            0x0E => "105".to_owned(), // LightMagenta
            0x0F => "106".to_owned(), // LightCyan
            0x10 => "107".to_owned(), // White
            _ => "49".to_owned(),     // Unknown → Reset
        },
        0x01 => format!("48;5;{}", val & 0xFF), // Indexed
        0x02 => {
            let r = (val >> 16) & 0xFF;
            let g = (val >> 8) & 0xFF;
            let b = val & 0xFF;
            format!("48;2;{r};{g};{b}")
        }
        _ => "49".to_owned(),
    }
}

// ---------------------------------------------------------------------------
// Modifier → SGR
// ---------------------------------------------------------------------------

/// Converts a u16 modifier bitmask to SGR escape sequence fragments.
///
/// Returns a Vec of SGR parameter strings (e.g., "1" for bold, "3" for italic).
fn modifier_to_sgr_parts(val: u16) -> Vec<&'static str> {
    let mut parts = Vec::new();

    // ratatui::Modifier bits (from bitflags)
    const BOLD: u16 = 1 << 0; // 0x01
    const DIM: u16 = 1 << 1; // 0x02
    const ITALIC: u16 = 1 << 2; // 0x04
    const UNDERLINED: u16 = 1 << 3; // 0x08
    const SLOW_BLINK: u16 = 1 << 4; // 0x10
    const RAPID_BLINK: u16 = 1 << 5; // 0x20
    const HIDDEN: u16 = 1 << 7; // 0x80
    const CROSSED_OUT: u16 = 1 << 8; // 0x100

    if val & BOLD != 0 {
        parts.push("1");
    }
    if val & DIM != 0 {
        parts.push("2");
    }
    if val & ITALIC != 0 {
        parts.push("3");
    }
    if val & UNDERLINED != 0 {
        parts.push(match underline_style_from_modifier(val) {
            2 => "4:2",
            3 => "4:3",
            4 => "4:4",
            5 => "4:5",
            _ => "4",
        });
    }
    if val & SLOW_BLINK != 0 {
        parts.push("5");
    }
    if val & RAPID_BLINK != 0 {
        parts.push("6");
    }
    if val & REVERSED_MODIFIER != 0 {
        parts.push("7");
    }
    if val & HIDDEN != 0 {
        parts.push("8");
    }
    if val & CROSSED_OUT != 0 {
        parts.push("9");
    }

    parts
}

/// Builds a complete SGR escape sequence for a cell's style.
fn build_sgr(fg: u32, bg: u32, modifier: u16) -> String {
    let mut parts = vec!["0".to_owned()];
    parts.extend(
        modifier_to_sgr_parts(modifier)
            .into_iter()
            .map(str::to_owned),
    );
    parts.push(color_to_sgr_fg(fg));
    parts.push(color_to_sgr_bg(bg));
    format!("\x1b[{}m", parts.join(";"))
}

// ---------------------------------------------------------------------------
// Cell comparison
// ---------------------------------------------------------------------------

/// Checks if two cells are visually identical.
fn cells_equal(a: &CellData, b: &CellData) -> bool {
    a.symbol == b.symbol
        && a.fg == b.fg
        && a.bg == b.bg
        && a.modifier == b.modifier
        && a.hyperlink == b.hyperlink
    // Skip flag is only for ratatui internal use, not visual.
}

// ---------------------------------------------------------------------------
// Blitting
// ---------------------------------------------------------------------------

/// Blits a frame to a writer, diffing against the previous frame.
#[cfg(test)]
fn blit_frame_to(writer: impl Write, frame: &FrameData, prev: Option<&FrameData>) {
    let mut last_visible_cursor = None;
    let mut last_cursor_shape = 0;
    blit_frame_to_with_cursor_memory(
        writer,
        frame,
        prev,
        &mut last_visible_cursor,
        &mut last_cursor_shape,
        false,
    );
}

#[cfg(test)]
fn blit_frame_to_with_cursor_memory(
    writer: impl Write,
    frame: &FrameData,
    prev: Option<&FrameData>,
    last_visible_cursor: &mut Option<(u16, u16)>,
    last_cursor_shape: &mut u8,
    suppress_visible_cursor: bool,
) {
    blit_frame_to_with_cursor_memory_and_policy(
        writer,
        frame,
        prev,
        last_visible_cursor,
        last_cursor_shape,
        repeat_ime_anchor_after_sync(),
        suppress_visible_cursor,
    );
}

#[cfg(test)]
fn blit_frame_to_with_cursor_memory_and_policy(
    writer: impl Write,
    frame: &FrameData,
    prev: Option<&FrameData>,
    last_visible_cursor: &mut Option<(u16, u16)>,
    last_cursor_shape: &mut u8,
    repeat_ime_anchor: bool,
    suppress_visible_cursor: bool,
) {
    blit_frame_to_with_cursor_memory_and_clear_policy(
        writer,
        frame,
        prev,
        last_visible_cursor,
        last_cursor_shape,
        repeat_ime_anchor,
        true,
        suppress_visible_cursor,
    );
}

fn frame_cell_index(frame: &FrameData, x: u16, y: u16) -> Option<usize> {
    (x < frame.width && y < frame.height)
        .then(|| usize::from(y) * usize::from(frame.width) + usize::from(x))
}

fn patch_cell_mut(rows: &mut [PaneSurfacePatchRow], x: u16, y: u16) -> Option<&mut CellData> {
    rows.iter_mut().rev().find_map(|row| {
        if row.y != y || x < row.x {
            return None;
        }
        row.cells.get_mut(usize::from(x - row.x))
    })
}

fn patch_rows_overlap(rows: &[PaneSurfacePatchRow]) -> bool {
    rows.iter().enumerate().any(|(index, left)| {
        let left_end = left.x.saturating_add(left.cells.len() as u16);
        rows[index + 1..].iter().any(|right| {
            if left.y != right.y {
                return false;
            }
            let right_end = right.x.saturating_add(right.cells.len() as u16);
            left.x < right_end && right.x < left_end
        })
    })
}

fn patch_row_fits(frame: &FrameData, row: &PaneSurfacePatchRow) -> bool {
    let Ok(len) = u16::try_from(row.cells.len()) else {
        return false;
    };
    if row.y >= frame.height
        || row.x.saturating_add(len) > frame.width
        || row.cells.iter().any(|cell| cell.hyperlink.is_some())
    {
        return false;
    }
    let start = usize::from(row.y) * usize::from(frame.width) + usize::from(row.x);
    let end = start + row.cells.len();
    frame
        .cells
        .get(start..end)
        .is_some_and(|cells| cells.iter().all(|cell| cell.hyperlink.is_none()))
}

fn blit_patch_to(
    mut writer: impl Write,
    frame: &FrameData,
    rows: &[PaneSurfacePatchRow],
    cursor: Option<CursorState>,
    last_visible_cursor: &mut Option<(u16, u16)>,
    last_cursor_shape: &mut u8,
    repeat_ime_anchor: bool,
    suppress_visible_cursor: bool,
) {
    let _ = writer.write_all(b"\x1b[?2026h\x1b[?25l\x1b]8;;\x1b\\");
    let mut last_sgr = String::new();
    let mut active_hyperlink = None;
    for row in rows {
        let mut invalidated = 0usize;
        let mut to_skip = 0usize;
        let mut next_inline_col = None;
        for (offset, cell) in row.cells.iter().enumerate() {
            let col = row.x + offset as u16;
            let idx = usize::from(row.y) * usize::from(frame.width) + usize::from(col);
            let prev_cell = &frame.cells[idx];
            if !cell.skip && (!cells_equal(cell, prev_cell) || invalidated > 0) && to_skip == 0 {
                let cursor_position =
                    (next_inline_col != Some(col) || invalidated > 0).then_some((col, row.y));
                write_cell(
                    &mut writer,
                    cursor_position,
                    cell,
                    &mut last_sgr,
                    &mut active_hyperlink,
                    frame,
                );
                next_inline_col = (cell.symbol.is_ascii() && cell_width(cell) == 1)
                    .then_some(col.saturating_add(1));
            }
            to_skip = cell_width(cell).saturating_sub(1);
            let affected_width = cmp::max(cell_width(cell), cell_width(prev_cell));
            invalidated = cmp::max(affected_width, invalidated).saturating_sub(1);
        }
    }
    close_hyperlink(&mut writer, &mut active_hyperlink);
    if !last_sgr.is_empty() {
        let _ = writer.write_all(b"\x1b[0m");
    }

    let cursor_frame = FrameData {
        cells: Vec::new(),
        width: frame.width,
        height: frame.height,
        cursor,
        hyperlinks: Vec::new(),
        graphics: Vec::new(),
    };
    let mut host_cursor = resolve_host_cursor_state(&cursor_frame, last_visible_cursor);
    if suppress_visible_cursor && host_cursor.visible {
        host_cursor.visible = false;
    }
    write_host_cursor_state(&mut writer, host_cursor, last_cursor_shape);
    let _ = writer.write_all(b"\x1b[?2026l");
    if repeat_ime_anchor {
        write_ime_anchor_cursor_state(&mut writer, host_cursor);
    }
    let _ = writer.flush();
}

fn blit_frame_to_with_cursor_memory_and_clear_policy(
    mut writer: impl Write,
    frame: &FrameData,
    prev: Option<&FrameData>,
    last_visible_cursor: &mut Option<(u16, u16)>,
    last_cursor_shape: &mut u8,
    repeat_ime_anchor: bool,
    clear_before_full_redraw: bool,
    suppress_visible_cursor: bool,
) {
    // On first frame or size change, do a full redraw.
    let full_redraw =
        prev.is_none() || prev.is_some_and(|p| p.width != frame.width || p.height != frame.height);

    // Ask terminals that support synchronized output to apply the whole frame
    // atomically. This keeps IMEs and cursor trackers from observing the
    // intermediate CUP positions used while painting changed cells.
    let _ = writer.write_all(b"\x1b[?2026h");

    // Hide cursor before any cell writes to avoid stray cursor artifacts
    // on terminals that render the hardware cursor at intermediate CUP positions.
    let _ = writer.write_all(b"\x1b[?25l");

    // Start each frame from a known OSC 8 state. If a previous write was
    // interrupted or the outer terminal had an active hyperlink, unlinked cells
    // must not inherit it.
    let _ = writer.write_all(b"\x1b]8;;\x1b\\");

    if full_redraw {
        if clear_before_full_redraw {
            let _ = writer.write_all(b"\x1b[2J");
        }
        write_all_cells(&mut writer, frame);
    } else {
        // Diff-based update: only write changed cells.
        let prev = prev.unwrap();
        write_changed_cells(&mut writer, frame, prev);
    }

    // Position the cursor while it is still hidden, then restore visibility.
    // Showing before moving makes slow terminals and IMEs briefly observe the
    // cursor at the last painted cell, which can be an animated sidebar/status
    // cell rather than the focused pane's input position. When the focused pane
    // hides its cursor, still park the host cursor intentionally so IMEs do not
    // anchor to whichever cell happened to be painted last.
    let mut host_cursor = resolve_host_cursor_state(frame, last_visible_cursor);
    if suppress_visible_cursor && host_cursor.visible {
        host_cursor.visible = false;
    }
    write_host_cursor_state(&mut writer, host_cursor, last_cursor_shape);

    // End the synchronized output block immediately after the final cursor
    // state is emitted so supporting terminals can present the frame atomically.
    let _ = writer.write_all(b"\x1b[?2026l");

    // Some native IMEs track candidate-window placement from normal terminal
    // cursor updates and may not observe cursor moves emitted inside synchronized
    // output. Re-emit only the resolved final cursor anchor after the sync block
    // on targets that need it; Windows Terminal exposes that repeat as cursor
    // movement during active TUI repaints.
    if repeat_ime_anchor {
        write_ime_anchor_cursor_state(&mut writer, host_cursor);
    }
    let _ = writer.flush();
}

#[cfg(windows)]
fn repeat_ime_anchor_after_sync() -> bool {
    false
}

#[cfg(not(windows))]
fn repeat_ime_anchor_after_sync() -> bool {
    true
}

/// Writes all cells in the frame (full redraw).
fn cell_width(cell: &CellData) -> usize {
    if is_halfwidth_katakana_voiced_grapheme(&cell.symbol) {
        return 2;
    }
    cell.symbol.width()
}

fn is_halfwidth_katakana_voiced_grapheme(symbol: &str) -> bool {
    let mut chars = symbol.chars();
    let Some(base) = chars.next() else {
        return false;
    };
    let Some(mark) = chars.next() else {
        return false;
    };
    chars.next().is_none()
        && ('\u{ff66}'..='\u{ff9d}').contains(&base)
        && matches!(mark, '\u{ff9e}' | '\u{ff9f}')
}

#[derive(Clone, Copy)]
struct HostCursorState {
    position: (u16, u16),
    visible: bool,
    /// DECSCUSR parameter (0–6). 0 means terminal default.
    shape: u8,
}

fn resolve_host_cursor_state(
    frame: &FrameData,
    last_visible_cursor: &mut Option<(u16, u16)>,
) -> HostCursorState {
    if let Some(cursor) = &frame.cursor {
        if cursor.visible {
            let position = clamp_cursor_position(frame, cursor.x, cursor.y);
            *last_visible_cursor = Some(position);
            return HostCursorState {
                position,
                visible: true,
                shape: normalize_cursor_shape(cursor.shape),
            };
        }

        let position = clamp_cursor_position(frame, cursor.x, cursor.y);
        return HostCursorState {
            position,
            visible: false,
            shape: normalize_cursor_shape(cursor.shape),
        };
    }

    let position = (*last_visible_cursor)
        .map(|(x, y)| clamp_cursor_position(frame, x, y))
        .unwrap_or_else(|| default_hidden_cursor_position(frame));
    HostCursorState {
        position,
        visible: false,
        shape: 0,
    }
}

fn normalize_cursor_shape(shape: u8) -> u8 {
    if shape <= 6 { shape } else { 0 }
}

fn default_hidden_cursor_position(frame: &FrameData) -> (u16, u16) {
    (
        frame.width.saturating_sub(1),
        frame.height.saturating_sub(1),
    )
}

fn clamp_cursor_position(frame: &FrameData, x: u16, y: u16) -> (u16, u16) {
    (
        x.min(frame.width.saturating_sub(1)),
        y.min(frame.height.saturating_sub(1)),
    )
}

fn write_cursor_position(writer: &mut impl Write, (x, y): (u16, u16)) {
    // CUP: move cursor to (row+1, col+1) — 1-based.
    let _ = write!(writer, "\x1b[{};{}H", y + 1, x + 1);
}

fn write_host_cursor_state(writer: &mut impl Write, cursor: HostCursorState, last_shape: &mut u8) {
    write_cursor_position(writer, cursor.position);
    if cursor.shape != *last_shape {
        let _ = write!(writer, "\x1b[{} q", cursor.shape);
        *last_shape = cursor.shape;
    }
    if cursor.visible {
        // Show cursor only after it is already at the final position.
        let _ = writer.write_all(b"\x1b[?25h");
    } else {
        let _ = writer.write_all(b"\x1b[?25l");
    }
}

fn write_ime_anchor_cursor_state(writer: &mut impl Write, cursor: HostCursorState) {
    write_cursor_position(writer, cursor.position);
    if cursor.visible {
        let _ = writer.write_all(b"\x1b[?25h");
    } else {
        let _ = writer.write_all(b"\x1b[?25l");
    }
}

fn write_all_cells(writer: &mut impl Write, frame: &FrameData) {
    let mut last_sgr = String::new();
    let mut active_hyperlink = None;
    for row in 0..frame.height {
        let mut to_skip = 0usize;
        let mut next_inline_col = None;
        for col in 0..frame.width {
            if to_skip > 0 {
                to_skip -= 1;
                continue;
            }

            let idx = (row as usize) * (frame.width as usize) + (col as usize);
            let cell = &frame.cells[idx];

            if cell.skip {
                next_inline_col = None;
                continue;
            }

            let cursor_position = (next_inline_col != Some(col)).then_some((col, row));
            write_cell(
                writer,
                cursor_position,
                cell,
                &mut last_sgr,
                &mut active_hyperlink,
                frame,
            );
            let width = cell_width(cell);
            next_inline_col =
                (cell.symbol.is_ascii() && width == 1).then_some(col.saturating_add(1));
            to_skip = width.saturating_sub(1);
        }
    }

    close_hyperlink(writer, &mut active_hyperlink);

    // Reset style at the end.
    let _ = writer.write_all(b"\x1b[0m");
}

fn cell_hyperlink_uri<'a>(frame: &'a FrameData, cell: &CellData) -> Option<&'a str> {
    let index = cell.hyperlink? as usize;
    frame.hyperlinks.get(index).map(String::as_str)
}

fn sanitized_hyperlink_uri(uri: &str) -> Option<String> {
    let sanitized: String = uri
        .chars()
        .filter(|ch| *ch != '\x1b' && *ch != '\x07' && !ch.is_control())
        .collect();
    (!sanitized.is_empty()).then_some(sanitized)
}

fn sanitized_frame_hyperlinks(frame: &FrameData) -> Vec<Option<String>> {
    frame
        .hyperlinks
        .iter()
        .map(|uri| sanitized_hyperlink_uri(uri))
        .collect()
}

fn sanitized_cell_hyperlink_uri<'a>(
    sanitized_hyperlinks: &'a [Option<String>],
    cell: &CellData,
) -> Option<&'a str> {
    let index = cell.hyperlink? as usize;
    sanitized_hyperlinks.get(index)?.as_deref()
}

fn write_hyperlink_if_changed(
    writer: &mut impl Write,
    active: &mut Option<String>,
    requested: Option<&str>,
) {
    let requested = requested.and_then(sanitized_hyperlink_uri);
    if active.as_deref() == requested.as_deref() {
        return;
    }

    if active.is_some() {
        let _ = writer.write_all(b"\x1b]8;;\x1b\\");
    }
    *active = requested;
    if let Some(uri) = active.as_deref() {
        let _ = write!(writer, "\x1b]8;;{uri}\x1b\\");
    }
}

fn close_hyperlink(writer: &mut impl Write, active: &mut Option<String>) {
    if active.take().is_some() {
        let _ = writer.write_all(b"\x1b]8;;\x1b\\");
    }
}

fn write_cell(
    writer: &mut impl Write,
    cursor_position: Option<(u16, u16)>,
    cell: &CellData,
    last_sgr: &mut String,
    active_hyperlink: &mut Option<String>,
    frame: &FrameData,
) {
    if cell.skip {
        return;
    }

    if let Some(position) = cursor_position {
        write_cursor_position(writer, position);
    }

    let sgr = build_sgr(cell.fg, cell.bg, cell.modifier);
    if sgr != *last_sgr {
        let _ = writer.write_all(sgr.as_bytes());
        *last_sgr = sgr;
    }

    write_hyperlink_if_changed(writer, active_hyperlink, cell_hyperlink_uri(frame, cell));
    let _ = writer.write_all(cell.symbol.as_bytes());
}

/// Writes only the cells that changed between the previous and current frame.
fn cells_visually_equal(
    sanitized_hyperlinks: &[Option<String>],
    cell: &CellData,
    prev_sanitized_hyperlinks: &[Option<String>],
    prev_cell: &CellData,
) -> bool {
    cell.symbol == prev_cell.symbol
        && cell.fg == prev_cell.fg
        && cell.bg == prev_cell.bg
        && cell.modifier == prev_cell.modifier
        && sanitized_cell_hyperlink_uri(sanitized_hyperlinks, cell)
            == sanitized_cell_hyperlink_uri(prev_sanitized_hyperlinks, prev_cell)
    // Skip flag is only for ratatui internal use, not visual.
}

fn write_changed_cells(writer: &mut impl Write, frame: &FrameData, prev: &FrameData) {
    let mut last_sgr = String::new(); // Track last SGR to avoid redundant style changes.
    let mut active_hyperlink = None;
    let sanitized_hyperlinks = sanitized_frame_hyperlinks(frame);
    let prev_sanitized_hyperlinks = sanitized_frame_hyperlinks(prev);

    for row in 0..frame.height {
        let mut invalidated = 0usize;
        let mut to_skip = 0usize;
        // Herdr clients disable host autowrap, so safe cells can advance inline
        // without spilling into adjacent rows during a resize race.
        let mut next_inline_col = None;

        for col in 0..frame.width {
            let idx = (row as usize) * (frame.width as usize) + (col as usize);
            let cell = &frame.cells[idx];
            let prev_cell = &prev.cells[idx];

            if !cell.skip
                && (!cells_visually_equal(
                    &sanitized_hyperlinks,
                    cell,
                    &prev_sanitized_hyperlinks,
                    prev_cell,
                ) || invalidated > 0)
                && to_skip == 0
            {
                let cursor_position =
                    (next_inline_col != Some(col) || invalidated > 0).then_some((col, row));
                write_cell(
                    writer,
                    cursor_position,
                    cell,
                    &mut last_sgr,
                    &mut active_hyperlink,
                    frame,
                );
                next_inline_col = (cell.symbol.is_ascii() && cell_width(cell) == 1)
                    .then_some(col.saturating_add(1));
            }

            to_skip = cell_width(cell).saturating_sub(1);
            let affected_width = cmp::max(cell_width(cell), cell_width(prev_cell));
            invalidated = cmp::max(affected_width, invalidated).saturating_sub(1);
        }
    }

    close_hyperlink(writer, &mut active_hyperlink);

    // Reset style if we wrote anything.
    if !last_sgr.is_empty() {
        let _ = writer.write_all(b"\x1b[0m");
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

// NovaScale: test-only measurement without allocating the generated frame.
#[cfg(test)]
pub(super) fn nova_count_blit_for_test(frame: &FrameData) -> usize {
    struct Counter(usize);
    impl Write for Counter {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0 = self.0.saturating_add(bytes.len());
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut counter = Counter(0);
    let mut visible_cursor = None;
    let mut cursor_shape = 0;
    blit_frame_to_with_cursor_memory_and_clear_policy(
        &mut counter,
        frame,
        None,
        &mut visible_cursor,
        &mut cursor_shape,
        repeat_ime_anchor_after_sync(),
        true,
        false,
    );
    counter.0
}
