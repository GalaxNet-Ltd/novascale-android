# Wuffs PNG decoder

- Upstream: https://github.com/google/wuffs
- Source revision: `7411f488fe2e2c205c3d3b3d28638b7356522930`
- Generated C version: `0.4.0-alpha.10+3966.20260623`
- Zig package hash: `N-V-__8AAP5JWgCGP_AD0teWpa4krRvE9VPZzvviGdbmN4jI`
- License: dual MIT / Apache-2.0, used under MIT; full upstream LICENSE retained.
- Build recipe: Ghostty's pinned `pkg/wuffs` module and its pinned generated
  `release/c/wuffs-v0.4.c`; linked by `native/nova-ghostty-zig/build.zig`.

NovaScale installs an IHDR-size-checked decoder callback into libghostty-vt.
Decoded output is bounded to 8 MiB and the entire Ghostty allocator to 64 MiB
per session. No decoder source is copied or patched. PNG decoding and bitmap
creation run off Android's main thread. Only direct protocol uploads are enabled;
the decoder never opens a remote-supplied path or shared-memory object.
