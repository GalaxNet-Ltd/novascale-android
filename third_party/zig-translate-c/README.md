# Zig translate-c source record

- Upstream: https://github.com/ziglang/translate-c
- Commit: `02ff0c523fb92a38939b04d0145c4a9602a74d56`
- Version: 2.0.0
- Zig package hash: `translate_c-2.0.0-Q_BUWltOBwBYABF84EUL208pIFeUIwbBs29FmVBaafN5`
- License: MIT (retained in `LICENSE`).

Ghostty's unmodified package generates its C header and Wuffs bindings using
this tool. The generated code imports its `lib/c_builtins.zig` and
`lib/helpers.zig`, so the notice is included with the Android app. Its build-time
aro parser is recorded in `../ghostty/upstream-dependencies.json` with its MIT
notice in `../ghostty/build-tools/aro-LICENSE`.
