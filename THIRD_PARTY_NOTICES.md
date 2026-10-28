# Third-party notices

This inventory is maintained from the first implementation slice. Exact source pins and modification records are release-blocking requirements.

| Component | Intended use | License | Current source status |
| --- | --- | --- | --- |
| AndroidX and Jetpack Compose | Native Android application UI | Apache-2.0 | Maven dependencies pinned by the version catalog |
| AndroidX Media3 | Local audio/video playback for SFTP previews | Apache-2.0 | Maven `1.10.1`, commit and AAR hashes under `third_party/media3/` |
| Tailscale | Embedded tailnet node in the NovaScale Go AAR | BSD-3-Clause | `tailscale.com v1.102.4`; source and Android adaptation record under `third_party/tailscale/` |
| Russh and russh-sftp | SSH and SFTP in the Rust core | Apache-2.0 | `russh 0.62.2`, `russh-sftp 2.3.0`; source record under `third_party/russh/` |
| Ghostty `libghostty-vt` | Terminal state engine | MIT | Commit `a746d0f7281954eb251915f4cd9fcea4924ad999`; isolated Zig adapter and source record under `third_party/ghostty/` |
| Wuffs | Kitty PNG decoding | MIT (dual MIT / Apache-2.0 upstream) | Revision `a14745aa458fd2b2785034efa04eab3c7b5b91e0`, generated C `0.4.0-alpha.9`; exact Ghostty dependency hash, build recipe and license under `third_party/wuffs/` |
| Termux terminal UI | Adapted Android Canvas rendering and interaction | GPLv3 with inherited notices | Adapted from commit `3df69d1da197dd9bd71a3bafd902dffd720576b4`; record under `third_party/termux/` |
| Chuchu | Ghostty Android snapshot and grapheme implementation reference | MIT | Adapted from commit `d962636e7948d870cf82828d8df4bf00b5e87ea9`; record under `third_party/chuchu/` |
| Sora Editor | Native remote-file editing surface and Tree-sitter adapter | LGPL-2.1-or-later | Maven `0.24.4`, commit `1e199e11dcbf9e6526cdcbdf1bdb25b74140496a`; source, AAR hashes, license, and replacement terms under `third_party/sora-editor/` |
| Android Tree-sitter | Android Java/JNI Tree-sitter binding | LGPL-2.1 | Maven `4.3.2`, commit `797150222de664181e31be53e9a5b253056ba9b0`; source and native verification record under `third_party/android-tree-sitter/` |
| Tree-sitter and language grammars | Incremental parsing and highlighting for C, C++, Java, JSON, Kotlin, Properties, Python, and XML | MIT and LGPL-2.1 | Exact submodule commits, AAR hashes, query modifications, and grammar notices under `third_party/android-tree-sitter/` |
| Terminal fonts | System-selectable JetBrains Mono, JetBrains Mono Nerd Font, Fira Code, and Source Code Pro | SIL OFL-1.1; Nerd Fonts tooling MIT | Immutable source commits/releases, file hashes, copyright notices, and licenses under `third_party/terminal-fonts/`; binaries are fetched by a checksum-enforcing build script |
| Terminal themes | Built-in Solarized Dark, Dracula, and Nord palettes | MIT | Immutable palette sources and retained notices under `third_party/terminal-themes/`; Nova Dark and Nova Light are NovaScale-owned |
| Simple Icons and Devicon | Android, Apple, and Windows host identity vectors | CC0-1.0 and MIT | Exact source commits, imported paths, modifications, and license texts under `third_party/os-icons/` |

Before code from any additional upstream project is added, record the immutable commit, source URL, license text, retained copyright notices, imported files, and NovaScale modifications under `third_party/`.

## Wuffs MIT license

Copyright 2023 The Wuffs Authors

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
