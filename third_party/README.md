# Upstream source records

Each adapted upstream component receives its own directory containing:

- an immutable upstream repository and commit reference;
- the applicable license and copyright notices;
- a file-level import inventory;
- a patch or written modification log; and
- the update and verification procedure.

The Tailscale module pin and Android interface adaptation are recorded in
`tailscale/`. Russh and russh-sftp are recorded in `russh/`. Ghostty, Sora Editor, and Android Tree-sitter have matching records in their
component directories. AndroidX Media3's playback integration is recorded in
`media3/`. Shared Apache-2.0, LGPL-2.1, and Tree-sitter MIT license texts are
stored at this directory's top level.

`termux/` records the removed GPL-derived terminal view, and `chuchu/` records
the removed MIT-derived Ghostty adapter. Both retain historical attribution.
Current NovaScale terminal rendering and interaction are independently implemented.
`lgpl/` describes source availability and replacing the editor dependencies in a
modified application build.
