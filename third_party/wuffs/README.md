# Wuffs PNG decoder

- Upstream: https://github.com/google/wuffs
- Source revision: `a14745aa458fd2b2785034efa04eab3c7b5b91e0`
- Generated C version: `0.4.0-alpha.9+3837.20240914`
- Zig package hash: `N-V-__8AAAzZywE3s51XfsLbP9eyEw57ae9swYB9aGB6fCMs`
- License: dual MIT / Apache-2.0, used under MIT; full upstream LICENSE retained.
- Build recipe: Ghostty's pinned `pkg/wuffs` module and its pinned generated
  `release/c/wuffs-v0.4.c`; linked by `native/nova-ghostty-zig/build.zig`.

NovaScale installs an IHDR-size-checked decoder callback into libghostty-vt.
Decoded output is bounded to 8 MiB and the entire Ghostty allocator to 64 MiB
per session. No decoder source is copied or patched. PNG decoding and bitmap
creation run off Android's main thread. Only direct protocol uploads are enabled;
the decoder never opens a remote-supplied path or shared-memory object.
