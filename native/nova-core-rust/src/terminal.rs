// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
//
// SPDX-License-Identifier: GPL-3.0-only

use crate::model::TerminalThemeConfig;

const HEADER_BYTES: usize = 14 * 4;
#[cfg(not(feature = "ghostty"))]
const CELL_BYTES: usize = 11;
#[cfg(not(feature = "ghostty"))]
const DEFAULT_FOREGROUND: [u8; 3] = [0xe6, 0xed, 0xf3];
#[cfg(not(feature = "ghostty"))]
const DEFAULT_BACKGROUND: [u8; 3] = [0x0d, 0x11, 0x17];

#[cfg(feature = "ghostty")]
unsafe extern "C" {
    fn nova_ghostty_create(columns: u32, rows: u32, max_scrollback: u32) -> usize;
    fn nova_ghostty_destroy(handle: usize);
    fn nova_ghostty_write(handle: usize, data: *const u8, data_len: usize) -> u64;
    fn nova_ghostty_resize(
        handle: usize,
        columns: u32,
        rows: u32,
        cell_width: u32,
        cell_height: u32,
    ) -> u64;
    fn nova_ghostty_scroll(handle: usize, rows: i32) -> u64;
    fn nova_ghostty_scroll_to_bottom(handle: usize) -> u64;
    fn nova_ghostty_set_theme(
        handle: usize,
        foreground: u32,
        background: u32,
        cursor: u32,
        ansi: *const u32,
        ansi_len: usize,
    ) -> u64;
    fn nova_ghostty_take_clipboard(handle: usize, output: *mut u8, capacity: usize) -> usize;
    fn nova_ghostty_drain_pty(handle: usize, output: *mut u8, capacity: usize) -> usize;
    fn nova_ghostty_snapshot(handle: usize, known_revision: u64, out_size: *mut usize)
    -> *const u8;
}

#[cfg(feature = "ghostty")]
#[derive(Debug)]
pub struct TerminalState {
    handle: usize,
}

#[cfg(feature = "ghostty")]
impl TerminalState {
    pub fn new(columns: u32, rows: u32, theme: Option<&TerminalThemeConfig>) -> Self {
        let handle = unsafe { nova_ghostty_create(columns, rows, 10_000) };
        assert_ne!(handle, 0, "libghostty-vt terminal initialization failed");
        let mut state = Self { handle };
        if let Some(theme) = theme {
            state.set_theme(theme);
        }
        state
    }

    pub fn resize(&mut self, columns: u32, rows: u32) -> u64 {
        self.resize_with_metrics(columns, rows, 8, 16)
    }

    pub fn resize_with_metrics(
        &mut self,
        columns: u32,
        rows: u32,
        cell_width: u32,
        cell_height: u32,
    ) -> u64 {
        unsafe { nova_ghostty_resize(self.handle, columns, rows, cell_width, cell_height) }
    }

    pub fn feed(&mut self, data: &[u8]) -> u64 {
        unsafe { nova_ghostty_write(self.handle, data.as_ptr(), data.len()) }
    }

    pub fn scroll(&mut self, rows: i32) -> u64 {
        unsafe { nova_ghostty_scroll(self.handle, rows) }
    }

    pub fn scroll_to_bottom(&mut self) -> Option<u64> {
        match unsafe { nova_ghostty_scroll_to_bottom(self.handle) } {
            0 => None,
            revision => Some(revision),
        }
    }

    pub fn set_theme(&mut self, theme: &TerminalThemeConfig) -> u64 {
        if !theme.is_valid() {
            return 0;
        }
        unsafe {
            nova_ghostty_set_theme(
                self.handle,
                theme.foreground,
                theme.background,
                theme.cursor,
                theme.ansi.as_ptr(),
                theme.ansi.len(),
            )
        }
    }

    pub fn drain_pty_writes(&mut self) -> Vec<u8> {
        let mut output = Vec::new();
        loop {
            let offset = output.len();
            output.resize(offset + 8 * 1024, 0);
            let count = unsafe {
                nova_ghostty_drain_pty(self.handle, output[offset..].as_mut_ptr(), 8 * 1024)
            };
            output.truncate(offset + count);
            if count < 8 * 1024 {
                return output;
            }
        }
    }

    pub fn snapshot_bytes(&mut self) -> Vec<u8> {
        self.snapshot_bytes_since(0)
    }

    pub fn snapshot_bytes_since(&mut self, known_revision: u64) -> Vec<u8> {
        let mut size = 0usize;
        let pointer = unsafe { nova_ghostty_snapshot(self.handle, known_revision, &mut size) };
        if pointer.is_null() || !(HEADER_BYTES..=32 * 1024 * 1024).contains(&size) {
            return Vec::new();
        }
        unsafe { std::slice::from_raw_parts(pointer, size) }.to_vec()
    }
}

#[cfg(feature = "ghostty")]
impl Drop for TerminalState {
    fn drop(&mut self) {
        unsafe { nova_ghostty_destroy(self.handle) };
    }
}

/// Host-test fallback. Android release builds always enable `ghostty`; this
/// implementation keeps unit tests fast and checks the binary snapshot ABI.
#[cfg(not(feature = "ghostty"))]
#[derive(Debug)]
pub struct TerminalState {
    columns: u32,
    rows: u32,
    revision: u64,
    lines: std::collections::VecDeque<String>,
    partial: Vec<u8>,
    viewport_offset: usize,
}

#[cfg(not(feature = "ghostty"))]
impl TerminalState {
    pub fn snapshot_bytes_since(&mut self, _revision: u64) -> Vec<u8> {
        self.snapshot_bytes()
    }
    pub fn resize_with_metrics(&mut self, columns: u32, rows: u32, _cw: u32, _ch: u32) -> u64 {
        self.resize(columns, rows)
    }

    pub fn new(columns: u32, rows: u32, _theme: Option<&TerminalThemeConfig>) -> Self {
        Self {
            columns,
            rows,
            revision: 0,
            lines: std::collections::VecDeque::from([String::new()]),
            partial: Vec::new(),
            viewport_offset: 0,
        }
    }

    pub fn resize(&mut self, columns: u32, rows: u32) -> u64 {
        self.columns = columns;
        self.rows = rows;
        self.revision = self.revision.wrapping_add(1);
        self.trim_scrollback();
        self.clamp_viewport();
        self.revision
    }

    pub fn feed(&mut self, data: &[u8]) -> u64 {
        self.partial.extend_from_slice(data);
        let valid_len = match std::str::from_utf8(&self.partial) {
            Ok(_) => self.partial.len(),
            Err(error) => error.valid_up_to(),
        };
        if valid_len == 0 {
            return self.revision;
        }
        let text = String::from_utf8_lossy(&self.partial[..valid_len]).into_owned();
        self.partial.drain(..valid_len);
        for character in text.chars() {
            match character {
                '\n' => self.lines.push_back(String::new()),
                '\r' => {}
                '\u{0008}' => {
                    self.lines.back_mut().and_then(String::pop);
                }
                value if !value.is_control() || value == '\t' => {
                    if let Some(line) = self.lines.back_mut() {
                        line.push(value);
                    }
                }
                _ => {}
            }
        }
        self.revision = self.revision.wrapping_add(1);
        self.trim_scrollback();
        self.revision
    }

    pub fn set_theme(&mut self, theme: &TerminalThemeConfig) -> u64 {
        if theme.is_valid() {
            self.revision = self.revision.wrapping_add(1);
        }
        self.revision
    }

    pub fn scroll(&mut self, rows: i32) -> u64 {
        let maximum = self.lines.len().saturating_sub(self.rows as usize);
        if rows < 0 {
            self.viewport_offset = self
                .viewport_offset
                .saturating_add(rows.unsigned_abs() as usize)
                .min(maximum);
        } else {
            self.viewport_offset = self.viewport_offset.saturating_sub(rows as usize);
        }
        self.revision = self.revision.wrapping_add(1);
        self.revision
    }

    pub fn scroll_to_bottom(&mut self) -> Option<u64> {
        if self.viewport_offset == 0 {
            return None;
        }
        self.viewport_offset = 0;
        self.revision = self.revision.wrapping_add(1);
        Some(self.revision)
    }

    pub fn drain_pty_writes(&mut self) -> Vec<u8> {
        Vec::new()
    }

    pub fn snapshot_bytes(&mut self) -> Vec<u8> {
        let columns = self.columns as usize;
        let rows = self.rows as usize;
        let mut output = vec![0u8; HEADER_BYTES + columns * rows * CELL_BYTES];
        put_i32(&mut output, 0, self.columns as i32);
        put_i32(&mut output, 4, self.rows as i32);
        put_i32(
            &mut output,
            8,
            if self.viewport_offset == 0 { 0 } else { -1 },
        );
        put_i32(
            &mut output,
            12,
            if self.viewport_offset == 0 {
                (self.lines.len().min(rows).saturating_sub(1)) as i32
            } else {
                -1
            },
        );
        put_i32(&mut output, 16, 1);
        for (index, value) in DEFAULT_BACKGROUND.into_iter().enumerate() {
            put_i32(&mut output, 20 + index * 4, value as i32);
        }
        for (index, value) in DEFAULT_FOREGROUND.into_iter().enumerate() {
            put_i32(&mut output, 32 + index * 4, value as i32);
        }
        put_i32(&mut output, 48, 0);
        let end = self.lines.len().saturating_sub(self.viewport_offset);
        let visible: Vec<&String> = self
            .lines
            .iter()
            .skip(end.saturating_sub(rows))
            .take(rows)
            .collect();
        for row in 0..rows {
            let characters: Vec<char> = visible
                .get(row)
                .map_or_else(Vec::new, |line| line.chars().collect());
            for column in 0..columns {
                let offset = HEADER_BYTES + (row * columns + column) * CELL_BYTES;
                put_i32(
                    &mut output,
                    offset,
                    characters.get(column).copied().unwrap_or(' ') as i32,
                );
                output[offset + 4..offset + 7].copy_from_slice(&DEFAULT_FOREGROUND);
                output[offset + 7..offset + 10].copy_from_slice(&DEFAULT_BACKGROUND);
            }
        }
        output
    }

    fn trim_scrollback(&mut self) {
        let limit = (self.rows as usize).saturating_add(2_000);
        while self.lines.len() > limit {
            self.lines.pop_front();
        }
        self.clamp_viewport();
    }

    fn clamp_viewport(&mut self) {
        self.viewport_offset = self
            .viewport_offset
            .min(self.lines.len().saturating_sub(self.rows as usize));
    }
}

#[cfg(not(feature = "ghostty"))]
fn put_i32(output: &mut [u8], offset: usize, value: i32) {
    output[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn retains_complete_utf8_and_emits_snapshot_abi() {
        let mut state = TerminalState::new(80, 2, None);
        state.feed(&[0xe2, 0x82]);
        state.feed(&[0xac, b'\n', b'o', b'k']);
        let snapshot = state.snapshot_bytes();
        assert_eq!(i32::from_le_bytes(snapshot[0..4].try_into().unwrap()), 80);
        assert_eq!(i32::from_le_bytes(snapshot[4..8].try_into().unwrap()), 2);
        assert_eq!(
            i32::from_le_bytes(snapshot[HEADER_BYTES..HEADER_BYTES + 4].try_into().unwrap()),
            '€' as i32
        );
    }

    #[test]
    fn scrolls_snapshot_into_history_and_back_to_active_screen() {
        let mut state = TerminalState::new(8, 2, None);
        state.feed(b"one\ntwo\nthree\nfour");

        state.scroll(-2);
        let history = state.snapshot_bytes();
        assert_eq!(snapshot_row_text(&history, 8, 0), "one");
        assert_eq!(snapshot_row_text(&history, 8, 1), "two");
        assert_eq!(i32::from_le_bytes(history[8..12].try_into().unwrap()), -1);

        assert!(state.scroll_to_bottom().is_some());
        assert!(state.scroll_to_bottom().is_none());
        let active = state.snapshot_bytes();
        assert_eq!(snapshot_row_text(&active, 8, 0), "three");
        assert_eq!(snapshot_row_text(&active, 8, 1), "four");
        assert_eq!(i32::from_le_bytes(active[8..12].try_into().unwrap()), 0);
    }

    fn snapshot_row_text(snapshot: &[u8], columns: usize, row: usize) -> String {
        (0..columns)
            .filter_map(|column| {
                let offset = HEADER_BYTES + (row * columns + column) * CELL_BYTES;
                char::from_u32(
                    i32::from_le_bytes(snapshot[offset..offset + 4].try_into().unwrap()) as u32,
                )
            })
            .collect::<String>()
            .trim_end()
            .to_owned()
    }
}

impl TerminalState {
    pub fn take_clipboard(&mut self) -> Option<String> {
        #[cfg(feature = "ghostty")]
        {
            use base64::Engine;
            let size = unsafe {
                nova_ghostty_take_clipboard(
                    self.handle,
                    std::ptr::NonNull::<u8>::dangling().as_ptr(),
                    0,
                )
            };
            if size == 0 || size > 1_398_104 {
                return None;
            }
            let mut encoded = vec![0u8; size];
            let count = unsafe {
                nova_ghostty_take_clipboard(self.handle, encoded.as_mut_ptr(), encoded.len())
            };
            if count == 0 {
                return None;
            }
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(&encoded[..count])
                .ok()?;
            if bytes.len() > 1024 * 1024 {
                return None;
            }
            String::from_utf8(bytes).ok()
        }
        #[cfg(not(feature = "ghostty"))]
        {
            None
        }
    }
}
