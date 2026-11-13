# simdutf acceleration

- Upstream: https://github.com/simdutf/simdutf
- Version: 9.0.0, vendored by libghostty-vt 1.0.0 at
  `0fa3d577a5614150ff8e2c152de5fb9d5c6d2dec`, `pkg/simdutf/vendor/`.
- The vendored source is covered by Ghostty's immutable package hash
  `ghostty-1.4.1-dev-5UdBC58_nQWN3FHzqvvLtKzhOKptGc_uH9t7ejdTBGd3`.
- License: MIT (upstream offers Apache-2.0 OR MIT). The selected upstream
  `LICENSE-MIT` is retained verbatim and included in the app's third-party notices.

Ghostty's pinned `pkg/simdutf` build enables `SIMDUTF_NO_LIBCXX` for its Base64
helpers, including Kitty image payload decoding. NovaScale uses that build
unchanged, linked statically into the native core. No simdutf source is copied
or patched by NovaScale.
