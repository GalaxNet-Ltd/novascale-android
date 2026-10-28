# Ghostty source record

- Upstream: https://github.com/ghostty-org/ghostty
- NovaScale build pin: commit `a746d0f7281954eb251915f4cd9fcea4924ad999`
- Zig package hash: `ghostty-1.3.2-dev-5UdBC4aaEQVBspo81eyoKCFIEVCHBbbZxLh7jzc1kOum`
- License: MIT (retained in `LICENSE`)
- Module used: `ghostty-vt`

The current stable GUI release observed during integration was Ghostty v1.3.1
at commit `332b2aefc6e72d363aa93ab6ecfc86eeeeb5ed28`. Its installed public C header
exposes parsers and input helpers but not the full mutable terminal/render-state
surface required by an interactive SSH client. NovaScale therefore pins the
exact newer revision already exercised by Chuchu's Android bridge and isolates
it behind `native/nova-ghostty-zig`. The adapter is the only code allowed to
depend on Ghostty's evolving Zig API.

NovaScale does not import Ghostty's renderer or platform application. It uses
the VT terminal state, render-state snapshot, and terminal response generation.
Kitty graphics is enabled for direct RGB/RGBA/PNG uploads, including zlib and
chunked transfers, cropping, scaled placements, z ordering, and Unicode
placeholders. PNG uses the Wuffs module pinned by this Ghostty revision (see
`third_party/wuffs/`). File, temporary-file, and shared-memory media remain
disabled. Protocol parsing, queries/replies, storage, scrollback, deletion,
alternate-screen ownership and reset remain inside Ghostty. Animation and
relative placement support are limited by this upstream pin.

NovaScale caps each image and each screen's image storage at 8 MiB, dimensions
at 4096 per axis, pending encoded image data at 8 MiB, image count at 128,
and placement count at 512. A session-owned allocator caps all Ghostty memory
(including decompression and PNG work buffers) at 64 MiB; allocation failure
rejects the operation rather than allowing a decompression bomb to grow without
bound. Snapshot RGBA exports are at most 12 MiB (RGB expands to RGBA).

The existing grapheme table is followed by an `NGFX` v1 trailer when mode bit
14 is set: magic/version, 64-bit asset revision, asset count, placement count,
bounded RGBA assets, and 48-byte placement records in cell coordinates. JNI's
`nativeSnapshotWithImages` accepts the last decoded asset revision: unchanged
frames omit pixel data. The Kotlin session decoder retains assets by revision,
validates all lengths and source/destination rectangles, and requests a complete
set again after a failed decode. The old snapshot JNI method still returns a
complete frame. Image conversion to Android Bitmaps runs on a cancellable
worker; Canvas draws only the current frame's placements and clears the surface
every frame. Session close destroys Ghostty image storage; view disposal drops
its bitmap cache. All images remain in process memory and are never logged or
written to disk by the renderer.

`kitty_snapshot.zig` is the NovaScale adapter for Ghostty's parsed APC commands,
bounded execution and render placements. Its small response-encoding dispatch
follows the pinned `stream_terminal.zig` implementation; no second Kitty parser
or terminal emulator is present. Resize now carries actual cell pixel dimensions
through Kotlin/Rust to Ghostty and SSH window-change messages.

The packed snapshot's previously reserved 32-bit word at byte 52 now contains
mouse flags: bits 0–3 are X10/normal/button/any-event tracking; bits 4–7 are
SGR/UTF-8/urxvt/SGR-pixel encoding. Zero preserves old no-mouse behavior and the
56-byte header is unchanged. Mode state is read from Ghostty, not reparsed in
Kotlin. The device runtime probe checks enable, disable, and terminal reset.

Bit 8 of the same mode word carries bracketed-paste mode (DECSET 2004).
Bits 9–13 carry the active screen's negotiated Kitty keyboard flags. This
preserves the snapshot layout and follows Ghostty's keyboard push/pop, screen
switch and reset state. Android's accessory and View input share an encoder;
Shift+Tab uses CSI-u when requested and legacy back-tab otherwise. Keyboard
mode reporting and graphics share the same upstream pin.

The adapter also handles Ghostty's parsed OSC 52 clipboard-write action, retaining
at most one pending base64 payload (1 MiB decoded). Rust decodes valid UTF-8 and
emits a low-frequency `clipboard_write` event. Clipboard queries are ignored;
no device clipboard content is returned to the remote host. The focused Android
terminal consumes writes; hidden/background sessions cannot set the clipboard.
