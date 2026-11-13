# Herdr source record

NovaScale's native Herdr client uses a subset of [Herdr](https://github.com/herdrdev/herdr)
by the Herdr contributors, licensed under Apache License 2.0. The complete
upstream license is in `LICENSE` and in the packaged third-party notices.

- Release: `v0.9.1`
- Commit: `065ef9d6a531c49fb8bee7e818ef837065b21ee9`
- Imported files and original SHA-256 hashes:
  `native/nova-core-rust/src/herdr/vendor/manifest.json`
- Adapted source: `native/nova-core-rust/src/herdr/vendor/`
- Reproducible import: `python3 scripts/vendor-herdr-protocol.py /path/to/herdr-v0.9.1`
- Newly introduced Rust dependency license texts: `RUST-DEPENDENCIES.md`, also
  included in the in-app third-party notices.

The subset provides protocol types, input conversion, ANSI rendering and
terminal theme support. Modifications adapt module paths, remove upstream tests
that depend on the desktop application and suppress raw input logging. The
importer also replaces the blitter's output `Vec` with a NovaScale-owned bounded
writer and exposes its overflow flag, so the caller discards truncated frames
before committing the render state. Its drawing logic remains upstream-derived.
The subset does not include the upstream desktop application or a second network
stack.

The Rust dependency lockfile records the exact dependency graph. Changes to
the upstream pin or subset require source/hash review, protocol tests, real
server compatibility checks, both Android ABI builds, 16 KiB alignment checks,
and updated packaged notices.
