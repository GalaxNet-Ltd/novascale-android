# NovaScale for Android

NovaScale for Android is an open-source remote DevOps client built around one embedded Tailscale node, Rust-native SSH/SFTP, Ghostty terminal state, and a native Android terminal UI. NovaScale-owned source is licensed under Apache-2.0.

This repository contains the Android application and its build instructions. The Apple-platform product is separate and is not part of this repository or its license.

## Release status

NovaScale 1.0.0 (version code 1) is [available on Google Play](https://play.google.com/store/apps/details?id=cc.galaxnet.novascale). The [`v1.0.0-play.1` tag](https://github.com/GalaxNet-Ltd/novascale-android/tree/v1.0.0-play.1) identifies the source for that binary. Store submission records and signing credentials live in a separate private workspace.

The Android application includes:

- One app-scoped `tsnet.Server` built from official stable `tailscale.com v1.102.4`; no `tailscale/libtailscale` and no Android `VpnService`.
- External-browser web login, multiple saved tailnets with one active node, default/custom HTTPS control servers, isolated persisted identities and SSH settings, native profile switching, lifetime peer discovery with an online-only default, route-aware Tailscale ping, logout, and a socket-pair FD bridge for tailnet streams.
- `russh 0.62.2` SSH with Tailscale SSH none, password, and OpenSSH private-key authentication; strict shared TOFU/change host-key handling; PTY resize; and up to six terminal tabs.
- `libghostty-vt` 1.0.0 (`vt-1.0.0`), pinned to commit `0fa3d577a5614150ff8e2c152de5fb9d5c6d2dec`, exposed through a narrow Zig/Rust binary-snapshot adapter.
- Kitty graphics for direct PNG/RGB/RGBA uploads, zlib/chunking, Unicode placeholders, and Canvas rendering, with bounded native decoding and cached image assets.
- An independent Android terminal view using hardware-accelerated Canvas, cached fonts/text metrics and retained row drawing commands, with IME/hardware-key input, selection, paste, scrolling and pinch-to-resize.
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

Android Pro's direct, serverless Google Play billing manager and native purchase
sheet are under development. See [TESTING.md](TESTING.md#billing-development-variants)
for fixture tests and the [dependency record](third_party/play-billing/README.md)
for the Play SDK terms and distribution checks.
Community builds include all local features without a billing SDK. Feature
limits are enforced in Free mode; debug builds provide a persistent Free/Pro
override in Settings for testing without checkout. Community builds remain
unrestricted by default.

The Play adapter reads `NOVASCALE_PLAY_BILLING_PUBLIC_KEY` from a Gradle property
or environment variable, falling back to `app/play-billing-public-key.txt`.
The checked-in RSA public key is non-secret configuration for the official
application and accompanies its reproducible source. Forks publishing a separate
Play app must supply their own app's public key. Without a valid key, checkout stays unavailable; fixture tests and
debug Free/Pro controls need no key or Google account.

For internal billing investigations, setting `NOVASCALE_BILLING_DIAGNOSTICS=true`
and `NOVASCALE_RELEASE_TRACK=internal` enables local `NovaBilling` logcat entries:
ownership query status/count and Pro receipt state with a hashed order reference.
Tokens, receipt bodies and raw order/account IDs are excluded. The flag defaults
to false and is rejected for other release tracks; it does not change entitlement
decisions or send diagnostics to a server.

## Build

Requirements:

- Linux x86_64 host
- JDK 17
- Android SDK platform 36 and Build Tools 36.0.0
- Android NDK 28.2.13676358 (r28c)
- Go 1.26.6 (the AAR script selects the pinned toolchain automatically)
- Rust 1.88 or newer with `aarch64-linux-android` and `x86_64-linux-android` targets
- `curl`, `tar`, `unzip`, and `readelf`

Zig 0.17.0 is downloaded and checksum-verified by the native build when absent. Go mobile tools are built from the locked module graph.

From this directory:

```bash
export JAVA_HOME="$HOME/android-studio/jbr"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./gradlew :app:testCommunityDebugUnitTest :app:lintCommunityDebug :app:assembleCommunityDebug
./scripts/verify-debug-apk.sh
```

The community APK is `app/build/outputs/apk/community/debug/app-community-debug.apk`
(`cc.galaxnet.novascale.community.debug`). Local Play-development builds use
`:app:assemblePlayDebug` and `cc.galaxnet.novascale.debug`; neither debug identity
overwrites the published app.

## Release build inputs

Development builds use version `0.1.0-dev`. A Play release requires an explicit
version name and code, a public URL for the corresponding source tag, and upload
signing values. Gradle reads these from `NOVASCALE_VERSION_NAME`,
`NOVASCALE_VERSION_CODE`, `NOVASCALE_SOURCE_URL`, and the
`NOVASCALE_UPLOAD_*` environment variables. `./gradlew playReleaseBundle`
validates these inputs and writes the AAB under
`app/build/outputs/bundle/playRelease/`. Release packaging checks the documented
source/dependency licensing boundary; the LGPL source bundle and public source
tag must match the distributed artifact.
No signing key is included here.

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

The helper now defaults to the community debug identity. Set
`NOVASCALE_BUILD_FLAVOR=play` for private Play-development testing. Existing
`cc.galaxnet.novascale` installations/data are retained separately; the helper
does not migrate or clear them.

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
- `terminal-ui/`: NovaScale-owned cached Canvas renderer and Android input/selection surface.
- `native/nova-tailnet-go/`: NovaScale Go wrapper over pinned official Tailscale source.
- `native/nova-core-rust/`: JNI boundary, `russh`, `russh-sftp`, and Ghostty ownership.
- `native/nova-ghostty-zig/`: narrow `libghostty-vt` adapter.
- `third_party/`: immutable upstream pins and modification/provenance records.

## License

NovaScale-owned source is licensed under Apache-2.0. Third-party components keep
their respective licenses, including the LGPL editor libraries and Google's
Play SDK terms. See `LICENSE`, `THIRD_PARTY_NOTICES.md`, and
[LGPL source/relinking instructions](third_party/lgpl/README.md).
Historical GPL releases retain their original licenses. The former Termux-derived
view is recorded under `third_party/termux/` for those releases and is no longer
part of the current implementation.
