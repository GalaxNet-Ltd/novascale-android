#!/usr/bin/env python3
"""Reproduce the reviewed Herdr source subset from the pinned upstream checkout."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

REVISION = '065ef9d6a531c49fb8bee7e818ef837065b21ee9'
FILES = ['protocol/wire.rs', 'protocol/endpoint.rs', 'protocol/render_ansi.rs',
         'input/model.rs', 'input/parse.rs', 'input/encode.rs', 'raw_input.rs',
         'terminal_theme.rs']
if len(sys.argv) != 2:
    raise SystemExit('usage: vendor-herdr-protocol.py /path/to/herdr-checkout')
source = Path(sys.argv[1]).resolve()
actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
if actual != REVISION:
    raise SystemExit(f'expected {REVISION}, got {actual}')
if subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--untracked-files=no'], text=True).strip():
    raise SystemExit('upstream checkout must have no tracked changes')
root = Path(__file__).resolve().parents[1]
out = root / 'native/nova-core-rust/src/herdr/vendor'
manifest = {}
for name in FILES:
    data = (source / 'src' / name).read_text()
    manifest[name] = hashlib.sha256(data.encode()).hexdigest()
    if name == 'protocol/endpoint.rs':
        data = data[:data.index('pub fn snapshot_message')]
    else:
        marker = '#[cfg(test)]\nmod tests'
        if marker in data:
            data = data[:data.index(marker)]
    for module in ['protocol', 'input', 'raw_input', 'terminal_theme', 'api', 'config', 'render_prof']:
        data = data.replace(f'crate::{module}::', f'crate::herdr::{module}::')
    data = data.replace('crate::platform::capabilities().preserve_legacy_doubled_escape_input', 'false')
    data = re.sub(r'tracing::(?:debug|trace|warn)!\(.*?\);', '// Input content is not logged.', data, flags=re.S)
    if name == 'raw_input.rs':
        needle = 'impl RawInputFramer {'
        assert data.count(needle) == 1
        data = data.replace(needle, needle + '\n    pub(crate) fn pending_len(&self) -> usize { self.byte_framer.buffer.len() }')
    if name == 'protocol/render_ansi.rs':
        # The upstream renderer intentionally ignores Write errors. Keep its
        # drawing logic, but cap the writer and expose failure so callers never
        # commit or display truncated ANSI from an adversarial surface.
        assert data.count('use std::io::Write;') == 1
        data = data.replace('use std::io::Write;', 'use std::io::Write;\nuse crate::herdr::capped_writer::CappedWriter;')
        assert data.count('    pub(crate) bytes: Vec<u8>,') == 1
        data = data.replace('    pub(crate) bytes: Vec<u8>,', '    pub(crate) bytes: Vec<u8>,\n    pub(crate) overflowed: bool,')
        assert data.count('let mut bytes = Vec::new();') == 2
        data = data.replace('let mut bytes = Vec::new();', 'let mut bytes = CappedWriter::new();')
        needle = '        EncodedBlit {\n            bytes,\n'
        assert data.count(needle) == 1
        data = data.replace(needle, '        let (bytes, overflowed) = bytes.finish();\n        EncodedBlit {\n            bytes,\n            overflowed,\n')
        needle = '        Some(EncodedBlit {\n            bytes,\n'
        assert data.count(needle) == 1
        data = data.replace(needle, '        let (bytes, overflowed) = bytes.finish();\n        Some(EncodedBlit {\n            bytes,\n            overflowed,\n')
        data += r'''
// NovaScale: test-only measurement without allocating the generated frame.
#[cfg(test)]
pub(super) fn nova_count_blit_for_test(frame: &FrameData) -> usize {
    struct Counter(usize);
    impl Write for Counter {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0 = self.0.saturating_add(bytes.len()); Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> { Ok(()) }
    }
    let mut counter = Counter(0);
    let mut visible_cursor = None;
    let mut cursor_shape = 0;
    blit_frame_to_with_cursor_memory_and_clear_policy(
        &mut counter, frame, None, &mut visible_cursor, &mut cursor_shape,
        repeat_ime_anchor_after_sync(), true, false,
    );
    counter.0
}
'''
    target = out / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('// Herdr v0.9.1, Apache-2.0; see third_party/herdr.\n' + data)
(out / 'manifest.json').write_text(json.dumps({'revision': REVISION, 'sha256': manifest}, indent=2) + '\n')
