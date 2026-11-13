# Ghostty source record

- Upstream: https://github.com/ghostty-org/ghostty
- Release: `vt-1.0.0` (libghostty-vt 1.0.0), released 2026-11-06
- NovaScale build pin: commit `0fa3d577a5614150ff8e2c152de5fb9d5c6d2dec`
- Signed tag object: `6b38d55f04e66ebecc472d8be19946edcc6d2e9d`
- Zig package hash: `ghostty-1.4.1-dev-5UdBC58_nQWN3FHzqvvLtKzhOKptGc_uH9t7ejdTBGd3`
- License: MIT (retained in `LICENSE`)
- Artifact used: upstream `ghostty-vt-static` public C ABI; SIMD and Kitty graphics enabled
- Compiler: Zig 0.17.0, Linux x86_64 archive SHA-256
  `1cbe9df9f27e6b78d14ccbca43b6703a404ef79ef1c463de901d7f088d4e2026`
- Android targets: ARM64 and x86_64, API 28, NDK r28c; static adapter linked
  into Rust with 16 KiB ELF maximum page size.

This is the stable **VT library** release, independent of the desktop app's
release cycle. The package's `1.4.1-dev` version is desktop metadata at that
immutable release commit, not a prerelease libghostty-vt dependency.

NovaScale uses the stable **public C ABI** through its independently written
`native/nova-ghostty-zig/src/nova_vt.c` adapter. The historical directory name
remains because Zig builds the official static library and the small Wuffs PNG
callback shim. No Ghostty Zig terminal internals are imported by the adapter,
and no upstream package is patched. The previous Chuchu-derived Zig terminal
and snapshot implementation has been removed; its provenance remains in
`../chuchu/` for historical source releases.

`simd=true` enables Highway/simdutf on both Android ABIs. CPU targets remain
baseline, with upstream runtime feature detection. Rust links the adapter,
`ghostty-vt-static`, simdutf and Highway archives explicitly, and tracks all four
archives so rebuilt native code cannot leave a stale Rust shared library.

## State and rendering

The public API owns terminal parsing, scrollback, colors, modes, replies and
Kitty storage. One retained render state per terminal is captured while Rust
holds the terminal mutex. Public global/per-row dirty flags avoid repeated cell
extraction, and opaque row identities plus owned content generations preserve
Kotlin row objects and GPU row commands across scrolling. Dirty flags are cleared
only after an owned capture succeeds. No borrowed Ghostty pointer crosses JNI.

[The frame v2 contract](../../native/nova-ghostty-zig/README.md) carries a complete
row directory, only changed row payloads, cursor/colors/input modes, and an NGFX
v1 image trailer. Missing baselines receive full frames; invalid packets clear
both decoder caches and trigger one immediate full-frame retry. The legacy v1
parser remains for synthetic fixtures and the non-Ghostty host test fallback;
Android's production native path emits v2.

Render holds preserve the last complete owned rows and image pixels. Hold start
captures completed output even when JNI has not yet requested it. Release makes
new output available. A monotonic one-second deadline in the SSH and Herdr event
loops releases forgotten holds without requiring new network bytes or polling
an idle terminal. Resize, viewport scrolling and theme changes end a hold.

The engine's default TinyIo and disabled external effects are retained. SSH has
up to 10,000 scrollback lines, subject to its allocator budget; Herdr explicitly
has zero local scrollback. Clipboard reads, desktop notifications, host-requested
window resizing and Glyph Protocol are disabled. There is no terminal payload
logging. The public clipboard callback receives decoded text; Rust validates
UTF-8, and only a focused Android terminal may apply its bounded write event.
PNG hook installation is synchronized across concurrent session creation.

## Kitty graphics policy

Direct RGB/RGBA/PNG, zlib/chunking, crop/scale/z ordering, Unicode placeholders,
relative placements, deletion, alternate screens and scrollback use upstream
semantics and the public image iterator. Wuffs remains checksum-pinned. File,
temporary-file and shared-memory transfer remain disabled.

The owner approved upstream command behavior with resource caps on 2026-11-11.
The public ABI has no parsed-command policy hook, so the retired adapter's exact
command rejection rules are not preserved. In particular, oversized source
crops can be safely clipped by Ghostty and explicit animation-frame selection
can change the displayed pixels. Automatic animation playback is not scheduled
by the Android renderer. There is no private Ghostty patch or second Kitty parser.

Limits are enforced at these boundaries:

- Each terminal's VT allocator: 64 MiB, including render state, decompression
  and PNG scratch storage; separate auxiliary buffers: 64 MiB in total.
- Upstream Kitty storage: 8 MiB per screen; pending encoded APC: 8 MiB.
- PNG decode and each exported RGBA asset: at most 4096 pixels per axis and
  8 MiB decoded. Raw protocol commands follow upstream admission rules, while
  unsupported export dimensions are omitted from rendering.
- Per exported frame: at most 128 image assets, 512 placements and 12 MiB of
  RGBA pixels. These count limits apply to visible export, not upstream storage
  admission. Resolved source/destination rectangles are validated before export.
- Frame packet: 32 MiB; pending PTY replies and clipboard text: 1 MiB each.

Image generations control asset reuse. Android converts new assets on a
cancellable worker and draws current placements with Canvas. Holds also freeze
owned image pixels. Session close frees VT storage; view disposal frees bitmap
and row caches. Rendering does not write images or terminal data to disk.

## Transitive source and license records

`upstream-dependencies.json` is the release's full dependency inventory. The
packages used by this adapter are:

| Package | Pin | Use / retained notice |
| --- | --- | --- |
| Highway | `66486a10623fa0d72fe91260f96c892e41aceb06` | SIMD helpers and CPU dispatch, `../highway/` |
| simdutf | `9.0.0`, vendored in the pinned Ghostty source | Base64 acceleration, `../simdutf/` |
| Wuffs | `7411f488fe2e2c205c3d3b3d28638b7356522930` | PNG decoder, `../wuffs/` |
| uucode | `1fb73433bba5d93366c57f23ff2e9d7939746500` | Unicode data/code, `../uucode/` |
| translate_c | `02ff0c523fb92a38939b04d0145c4a9602a74d56` | C binding generation and runtime helpers, `../zig-translate-c/` |
| aro | `d0c8c4d9c55daa7ef6e40cf0f630a5b5e900989b` | Build-time C parser only, `build-tools/aro-LICENSE` and `build-tools/aro-LICENSE-UNICODE` |

The upstream source and dependency hashes stay in the immutable package graph;
no dependency source is copied or patched. Zig 0.17's extracted `zig-pkg/` is a
build cache, excluded from Git and Gradle source inputs. The toolchain download
is checksum-verified by `scripts/ensure-zig.sh`.
