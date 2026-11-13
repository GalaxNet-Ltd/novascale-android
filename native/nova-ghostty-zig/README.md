# Public VT adapter and frame contract

`src/nova_vt.c` is NovaScale's adapter over the public libghostty-vt 1.0 C API.
Zig builds the pinned upstream `ghostty-vt-static` artifact and `src/png.zig`,
the Wuffs PNG callback shim. The directory name is historical. No Ghostty
terminal implementation types or private Zig modules are used by the adapter.
Dependency pins and graphics policy are in [the source record](../../third_party/ghostty/README.md).

## Ownership and updates

Rust owns each terminal and serializes writes, resize, scrolling, theme changes,
timer ticks and capture with its terminal mutex. One native render state retains
dirty information. Capture obtains row IDs, dirty flags, cells, cursor, colors
and images through public iterators. Only dirty/new rows are encoded; rows that
move without changing content retain their generation. Cleaning the render
state happens only after a complete owned capture succeeds.

The caller supplies its last successfully decoded frame and image revisions.
The adapter can send a delta relative to the latest capture or its predecessor;
older/unknown baselines receive a full row frame. Separate image revisions omit
unchanged assets. JNI copies the native buffer before releasing the mutex. Herdr
samples its target epoch under the same lock and prefixes it to the packet.

Android validates an entire packet before committing its row cache. A failed
decode clears rows/images and immediately retries once with revision zero.
Per-view GPU row caches use `(opaque identity, content generation)` and retain
commands when a row moves. Font/geometry changes and session disposal invalidate
those caches. No cell-by-cell FFI calls or second emulator are involved.

## Synchronized output

The render-hold callback captures the complete frame at hold start, before the
rest of that VT write is parsed. Rows, cursor/colors/modes and image pixels are
owned by this capture and remain frozen while held. Consecutive holds capture
the complete frame between them, even if Android did not request it.

Hold release permits another capture. A one-second monotonic deadline is armed
only while held; SSH and Herdr's native event loops tick that deadline and emit
an update without requiring another socket read. Scrolling, theme changes and
resize release holds too. There is no periodic redraw wakeup for idle terminals.

## NVTF frame v2

All integers are little-endian. The maximum packet length is 32 MiB. Revisions
are positive signed-64-bit-compatible counters; row IDs are two opaque 64-bit
words and must never be interpreted as pointers or viewport positions.

| Header offset | Type | Meaning |
| --- | --- | --- |
| 0 | u32 | `0x4654564e` (`NVTF`) |
| 4 | u32 | Version `2` |
| 8 | u64 | Frame revision |
| 16 | u64 | Required decoder base revision; `0` means full frame |
| 24, 28 | u32, u32 | Columns (1–500), rows (1–300) |
| 32, 36 | i32, i32 | Cursor column/row, or `-1` when hidden |
| 40 | u32 | Bit 0 visible, bit 1 blinking, bits 8–15 public cursor style |
| 44, 48, 52 | u32 each | Resolved foreground, background and cursor ARGB |
| 56 | u32 | Input mode flags |
| 60 | u32 | Number of included row records |
| 64 | u32 | Byte offset of NGFX image trailer |
| 68 | u32 | Reserved, must be zero |
| 72, 76 | u32, u32 | Native cell width/height in pixels |

The 80-byte header is followed by `rows` directory entries. Each entry is
`u64 id[2], u64 content_revision`. IDs must be unique within the frame. A row's
generation changes when its encoded content changes, independently of its
viewport position. Directory entries include unchanged rows.

Each included row record is `u32 row_index, u32 payload_length`, followed by
exactly `columns` cells. A cell is `u32 foreground_argb, u32 background_argb,
u32 attributes, u32 underline_argb, u32 utf8_length`, then its UTF-8 bytes.
Attributes use the public Ghostty style flags (bits 0–10); bit 31 marks a wide
character spacer with no text. Normal cells have at least one byte; blank cells
contain a space. There are no pointers or separate grapheme tables.

Full frames include every row. In a delta, omitted rows must already exist in
the decoder with the stated identity, generation and column count. Skipped,
malformed, truncated or conflicting records cannot update the decoder baseline.

Input mode bits 0–3 represent X10/normal/button/any-event mouse tracking, 4–7
SGR/UTF-8/urxvt/SGR-pixel mouse encoding, 8 bracketed paste, and 9–13 active
Kitty keyboard flags. Cursor styles use Ghostty's bar=0, block=1, underline=2,
hollow block=3 values; Android currently renders hollow as block and does not
animate cursor blinking. Existing Android cursor-shape preferences still apply.

The NGFX v1 trailer is `u32 magic=0x5846474e, u32 version=1, u64 revision,
u32 asset_count, u32 placement_count`. Assets contain `u32 id, width, height,
byte_length`, then RGBA bytes. A 48-byte placement contains `u32 id, i32 z`,
four f32 cell coordinates (left/top/width/height), four u32 source-pixel bounds,
`u32 placement_id, u32 internal_id`. Limits are 128 assets, 512 placements,
4096 pixels per image axis, 8 MiB per image and 12 MiB total pixels. Placements
are sent each frame; unchanged image revisions reuse the decoder's assets.

The legacy v1 parser and full-snapshot JNI entry points remain for test fixtures
and compatibility. Production Android native capture emits v2; the legacy
Chuchu-derived encoder is removed.

## Validation

`tests/frame_test.c` exercises full/delta rows, render holds, consecutive holds,
idle timeout and resize release against the real public C library. After
`scripts/build-rust-core.sh`, compile it with the NDK target clang, include
`src/`, and link `nova_ghostty`, `ghostty-vt-static`, `simdutf`, `highway` and `m`
from `zig-out/<abi>/lib`. Run the executable on a matching Android device.

Kotlin `TerminalFrameParserTest` checks strict bounds, truncation, missing bases,
row identity reuse, malformed images and style semantics. Device `renderer`
checks compare cached/direct pixels and moving-row GPU reuse; `graphics` checks
real public-API image handling and export limits. The local SSH retry fixture
includes a hold with no more network data, proving the native deadline emits
an update. Live Herdr and foreground/background recovery checks remain required.
