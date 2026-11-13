# NovaScale 1.1.0 — build 7

This tag is the corresponding source for the Android Play release. It includes
Herdr Remote, direct SSH hosts, multiple saved tailnets, host groups and pins,
terminal keyboard and rendering improvements, private browser favorites/history,
SFTP symbolic links, and the login-server and terminal reattachment fixes.

## Source downloads

- [Complete application source](https://github.com/GalaxNet-Ltd/novascale-android/archive/refs/tags/v1.1.0-play.7.tar.gz).
- [Matching LGPL editor and grammar source archive](https://raw.githubusercontent.com/GalaxNet-Ltd/novascale-android/v1.1.0-play.7/third_party/lgpl/editor-and-grammar-sources.tar.gz).
- [LGPL source pins](third_party/lgpl/source-pins.json) and
  [rebuilding/relinking instructions](third_party/lgpl/README.md).

The LGPL archive's SHA-256 is
`248f69018cf018315d2cefe6262aa9c4213bf855f0f1e6ce74fba807e4fd4d17`.
It contains the pinned editor and Tree-sitter sources, including grammar
submodules and their original licenses. The libraries are unmodified.
NovaScale-owned source is Apache-2.0; bundled dependencies retain their licenses.

## Release build inputs

Use the toolchain and dependency instructions in [README.md](README.md). After
checking out `v1.1.0-play.7`, set:

```sh
export NOVASCALE_VERSION_NAME=1.1.0
export NOVASCALE_VERSION_CODE=7
export NOVASCALE_RELEASE_TRACK=production
export NOVASCALE_DEFER_SOURCE_PUBLICATION=false
export NOVASCALE_BILLING_DIAGNOSTICS=false
export NOVASCALE_SOURCE_URL=https://github.com/GalaxNet-Ltd/novascale-android/tree/v1.1.0-play.7
```

Supply your own signing keystore through the four `NOVASCALE_UPLOAD_*`
variables documented in the Android build. Official private signing credentials
are not part of the source. The checked-in Play licensing key is public.

```sh
./gradlew validatePlayRelease testPlayDebugUnitTest lintPlayRelease \
  playReleaseBundle assemblePlayRelease
./scripts/verify-debug-apk.sh app/build/outputs/apk/play/release/app-play-release.apk
```

Release APKs are non-debuggable, with billing diagnostics and debug Pro/Herdr
controls disabled. Both `arm64-v8a` and `x86_64` are included. Dependency locks,
native build scripts and notices accompany this source. Google Play applies its
app-signing certificate to delivered APKs, so a local rebuild with your own key
does not replace an official installation without uninstalling it first.

Community builds include the local features without the billing SDK or Pro
limits. See [TESTING.md](TESTING.md) for validation procedures and the documented
limits of the automated/device test coverage.
