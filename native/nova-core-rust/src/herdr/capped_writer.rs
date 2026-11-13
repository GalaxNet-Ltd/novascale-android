// NovaScale for Android
// Copyright (C) 2026 NovaScale contributors
// SPDX-License-Identifier: Apache-2.0

//! A hard output-length cap for untrusted terminal surfaces. The upstream
//! renderer intentionally ignores writer errors, so callers MUST inspect the
//! overflow flag before committing or publishing the generated output.
use std::io::{self, Write};

pub(super) const MAX_RENDER_BYTES: usize = 16 * 1024 * 1024;

pub(super) fn append_bounded(target: &mut Vec<u8>, data: &[u8], limit: usize) -> io::Result<()> {
    if target.capacity() > limit {
        return Err(io::Error::other("render capacity exceeds limit"));
    }
    let needed = target
        .len()
        .checked_add(data.len())
        .ok_or_else(|| io::Error::other("render size overflow"))?;
    if needed > limit {
        return Err(io::Error::other("render limit exceeded"));
    }
    if needed > target.capacity() {
        let next = target.capacity().saturating_mul(2).max(needed).min(limit);
        target
            .try_reserve_exact(next - target.len())
            .map_err(|_| io::Error::other("render allocation failed"))?;
        // Vec's allocator may grant more capacity than requested. Do not use
        // that extra capacity as part of an allegedly bounded output buffer.
        if target.capacity() > limit {
            return Err(io::Error::other("render capacity exceeds limit"));
        }
    }
    target.extend_from_slice(data);
    Ok(())
}

pub(super) struct CappedWriter {
    bytes: Vec<u8>,
    failed: bool,
    limit: usize,
}

impl CappedWriter {
    pub(super) fn new() -> Self {
        Self::with_limit(MAX_RENDER_BYTES)
    }
    pub(super) fn with_limit(limit: usize) -> Self {
        Self {
            bytes: Vec::new(),
            failed: false,
            limit: limit.min(MAX_RENDER_BYTES),
        }
    }
    pub(super) fn len(&self) -> usize {
        self.bytes.len()
    }
    pub(super) fn finish(self) -> (Vec<u8>, bool) {
        (self.bytes, self.failed)
    }
}

impl Write for CappedWriter {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if self.failed {
            return Err(io::Error::other("render already failed"));
        }
        if let Err(err) = append_bounded(&mut self.bytes, bytes, self.limit) {
            self.failed = true;
            return Err(err);
        }
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn coalesced_output_cannot_grow_past_limit_and_overflow_does_not_append() {
        let mut output = Vec::new();
        append_bounded(&mut output, b"abcdef", 10).unwrap();
        assert!(output.capacity() <= 10);
        assert!(append_bounded(&mut output, b"ghijk", 10).is_err());
        assert_eq!(output, b"abcdef");
        assert!(output.capacity() <= 10);
    }

    #[test]
    fn writer_retains_failure_even_if_upstream_ignores_errors() {
        let mut writer = CappedWriter::new();
        writer.write_all(&vec![b'x'; MAX_RENDER_BYTES]).unwrap();
        let _ = writer.write_all(b"overflow");
        let _ = writer.write_all(b"ignored after failure");
        let (bytes, failed) = writer.finish();
        assert!(failed);
        assert_eq!(bytes.len(), MAX_RENDER_BYTES);
        assert!(bytes.capacity() <= MAX_RENDER_BYTES);
    }
}
