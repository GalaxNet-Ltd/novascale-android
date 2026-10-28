# NovaScale for Android

NovaScale for Android is a GPLv3 remote DevOps client built around one embedded Tailscale node, Rust-native SSH/SFTP, Ghostty terminal state, and a native Android terminal UI.

This repository contains the Android application and its build instructions. The Apple-platform product is separate and is not part of this repository or its license.

## Release status

NovaScale 1.0.0 (version code 1) is [available on Google Play](https://play.google.com/store/apps/details?id=cc.galaxnet.novascale). The [`v1.0.0-play.1` tag](https://github.com/GalaxNet-Ltd/novascale-android/tree/v1.0.0-play.1) identifies the source for that binary. Store submission records and signing credentials live in a separate private workspace.

The Android application includes:

- One app-scoped `tsnet.Server` built from official stable `tailscale.com v1.102.4`; no `tailscale/libtailscale` and no Android `VpnService`.
- External-browser web login, a custom HTTPS control server, persisted node state, lifetime peer discovery with an online-only default, route-aware Tailscale ping, logout, and a socket-pair FD bridge for tailnet streams.
- `russh 0.62.2` SSH with Tailscale SSH none, password, and OpenSSH private-key authentication; strict shared TOFU/change host-key handling; PTY resize; and up to six terminal tabs.
- `libghostty-vt` pinned to commit `a746d0f7281954eb251915f4cd9fcea4924ad999`, exposed through a narrow Zig/Rust binary-snapshot adapter.
- Kitty graphics for direct PNG/RGB/RGBA uploads, zlib/chunking, Unicode placeholders, and Canvas rendering, with bounded native decoding and cached image assets.
- A GPLv3 Termux-derived Android `View` using hardware-accelerated Canvas, with IME/hardware-key input, special keys, paste, scrolling, and pinch-to-resize.
- An iOS-aligned Compose design system, compact grouped host surfaces, saved-SSH continuation, automatic Terminal/Files connection, and a two-row terminal accessory with a drag arrow pad, one-shot Ctrl/Alt/Shift, bracketed-paste newline, symbols, and F1–F12.
- English-default UI resources with system-selected Simplified Chinese (`zh-Hans`) and Traditional Chinese (`zh-Hant`) across Home, Settings, SSH, Terminal, Files, Preview, accessibility labels, runtime states, and terminal notifications.
- `russh-sftp 2.3.0` directory browsing plus UTF-8 editing for files up to 2 MiB,
  including automatic editor fallback for unsupported preview formats that
  validate as text.
- A dedicated Browser tab using an authenticated shared HTTP/CONNECT proxy over the embedded node, alongside SOCKS5 access for external clients. Settings exposes encrypted credentials, automatic/preferred ports with fallback, and optional background sharing. TLS errors are never bypassed; device-local destinations are blocked.
- A source/provenance screen with a packaged Ghostty runtime probe.

See [`TESTING.md`](TESTING.md) for the development test matrix and its recorded limits. Passing the Play review does not replace the remaining device and network checks documented there.

The Android product-fidelity rules and the iOS-to-Android review backlog are in [`DESIGN_SYSTEM.md`](DESIGN_SYSTEM.md).

The published application ID is `cc.galaxnet.novascale`.

## Build

Requirements:

- Linux x86_64 host
- JDK 17
- Android SDK platform 36 and Build Tools 36.0.0
- Android NDK 28.2.13676358 (r28c)
- Go 1.26.6 (the AAR script selects the pinned toolchain automatically)
- Rust 1.88 or newer with `aarch64-linux-android` and `x86_64-linux-android` targets
- `curl`, `tar`, `unzip`, and `readelf`

Zig 0.15.2 is downloaded and checksum-verified by the native build when absent. Go mobile tools are built from the locked module graph.

From this directory:

```bash
export JAVA_HOME="$HOME/android-studio/jbr"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./gradlew test :app:lintDebug :app:assembleDebug
./scripts/verify-debug-apk.sh
```

The installable APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Release build inputs

Development builds use version `0.1.0-dev`. A Play release requires an explicit
version name and code, a public URL for the corresponding source tag, and upload
signing values. Gradle reads these from `NOVASCALE_VERSION_NAME`,
`NOVASCALE_VERSION_CODE`, `NOVASCALE_SOURCE_URL`, and the
`NOVASCALE_UPLOAD_*` environment variables. `./gradlew playReleaseBundle`
validates these inputs and writes the AAB under
`app/build/outputs/bundle/release/`. No signing key is included here.

## Persistent test emulator

The repository includes a helper that creates a dedicated API 33 Google Play
AVD, leaves its userdata intact, builds the latest debug APK, installs it as an
update, and launches NovaScale:

```bash
./scripts/run-emulator.sh
```

Authenticate the embedded tailnet once inside that emulator. Later runs reuse
the named `NovaScale_API_33` AVD and use `adb install -r`, so app-private
Tailscale state, SSH profiles, and known-host records remain in place. The
script never uninstalls NovaScale, clears app data, or wipes/deletes the AVD.

Useful commands:

```bash
./scripts/run-emulator.sh start       # Start without rebuilding/installing
./scripts/run-emulator.sh install     # Build/install/launch on the same AVD
./scripts/run-emulator.sh --no-build  # Install the existing debug APK
./scripts/run-emulator.sh status
./scripts/run-emulator.sh stop        # Clean shutdown; userdata is preserved
```

Use `./scripts/run-emulator.sh --help` for headless/cold-boot and AVD overrides.
Authentication may still expire or be revoked by the tailnet control server.

## Source boundaries

- `app/`: Compose application, feature controllers, and Android/native adapters.
- `core-api/`: stable Kotlin contracts and Ghostty snapshot parser.
- `terminal-ui/`: GPLv3 Termux-derived Canvas/input view adapted to NovaScale snapshots.
- `native/nova-tailnet-go/`: NovaScale Go wrapper over pinned official Tailscale source.
- `native/nova-core-rust/`: JNI boundary, `russh`, `russh-sftp`, and Ghostty ownership.
- `native/nova-ghostty-zig/`: narrow `libghostty-vt` adapter.
- `third_party/`: immutable upstream pins and modification/provenance records.

## License

The NovaScale Android application is licensed under GPLv3-only. See `LICENSE` and `THIRD_PARTY_NOTICES.md`.
