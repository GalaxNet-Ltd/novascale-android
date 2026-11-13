# Highway SIMD support

- Upstream: https://github.com/google/highway
- Version: 1.2.0; commit `66486a10623fa0d72fe91260f96c892e41aceb06`
- Source: https://deps.files.ghostty.org/highway-66486a10623fa0d72fe91260f96c892e41aceb06.tar.gz
- Zig package hash: `N-V-__8AAGmZhABbsPJLfbqrh6JTHsXhY6qCaLAQyx25e0XE`
- License: BSD-3-Clause (upstream offers Apache-2.0 OR BSD-3-Clause).
  The selected upstream `LICENSE-BSD3` is retained verbatim and included in the
  app's third-party notices.

Built through the `pkg/highway` adapter in the pinned libghostty-vt 1.0.0 source.
Ghostty provides the CPU dispatch/runtime adaptation and uses Highway for its
accelerated terminal parsing, byte search and codepoint-width helpers. The
Android build keeps baseline CPU targets and upstream runtime feature detection.
No Highway source is copied or patched by NovaScale.
