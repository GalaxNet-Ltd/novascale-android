# Chuchu reference record

- Upstream: https://github.com/jossephus/chuchu
- Reference commit: `d962636e7948d870cf82828d8df4bf00b5e87ea9`
- License: MIT (retained in `LICENSE`)
- Adapted source: `zig-src/src/bridge/chuchu_snapshot.zig`

NovaScale adapted the terminal construction, Ghostty effect-handler wiring,
grapheme-aware 14-field/11-byte-cell snapshot format, and render-state cell
serialization into `native/nova-ghostty-zig/src/nova_ghostty.zig`.

The Kitty adapter also follows the same reference's screen-pin-to-viewport
conversion and Unicode placement iterator integration. NovaScale owns its
versioned image trailer, asset cache, memory limits and Canvas bitmap renderer.

Chuchu's SSH implementation, JNI bridge, image decoding, and libssh2 dependency
are not imported. NovaScale's network stream remains Tailscale -> owned file
descriptor -> Rust -> Russh, and the Rust core calls the narrow Ghostty C ABI.
