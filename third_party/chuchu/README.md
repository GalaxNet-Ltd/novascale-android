# Historical Chuchu reference record

- Upstream: https://github.com/jossephus/chuchu
- Reference commit: `d962636e7948d870cf82828d8df4bf00b5e87ea9`
- License: MIT (retained unchanged in `LICENSE`)
- Former adapted source: `zig-src/src/bridge/chuchu_snapshot.zig`

Earlier NovaScale versions adapted terminal setup, effect wiring, the legacy
14-field/11-byte-cell snapshot format, and cell/grapheme serialization in
`nova_ghostty.zig`. The former `kitty_snapshot.zig` also cited the viewport and
Unicode placement reference.

The owner-approved public C API migration on 2026-11-11 removed those two source
files and their unused allocator helper. Current terminal setup and frame v2
serialization are independently implemented against libghostty-vt's public C
headers. There is no Chuchu runtime dependency or adapted source in the current
build. The legacy wire decoder is retained only for synthetic fixtures and the
host test fallback, not as an Android terminal implementation.

Keep this pin and MIT notice with historical source; removal does not erase
attribution or alter the license of prior adapted releases. Chuchu's SSH, JNI,
image decoder and libssh2 were never imported.
