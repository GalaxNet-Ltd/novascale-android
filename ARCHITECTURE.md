# Android architecture

NovaScale uses one in-process Tailscale node for its Android features. Kotlin
coordinates the application lifecycle and exposes the node through a
`TailnetBackend` interface. The Go package in `native/nova-tailnet-go/` owns
the `tsnet.Server` and provides login, peer discovery, dialing, and local
proxy operations. Its versioned Tailscale dependency is pinned in `go.mod`.

SSH and SFTP run in the Rust library at `native/nova-core-rust/`. Kotlin hands
each dialed full-duplex file descriptor to Rust once; network payloads do not
pass repeatedly through Kotlin or JNI. The Rust core uses `russh` and
`russh-sftp`, applies the same strict host-key policy to terminal and file
connections, and owns terminal state through the public C API adapter for
`libghostty-vt` in `native/nova-ghostty-zig/`.

The Android `app/` module uses Compose for application screens. `terminal-ui/`
contains NovaScale's independent Apache-2.0 Canvas renderer, input handling and
selection UI. Per-view font/measurement caches and retained GPU row commands
reuse unchanged content. It renders batched Ghostty snapshots rather than maintaining a
second terminal emulator. `core-api/` defines the boundaries between the app,
terminal UI, and native protocols.

The retained native render state exports versioned incremental frames: an
opaque row identity directory plus changed row content. Unchanged Kotlin rows
and GPU commands survive scrolling. A failed decode retries a complete frame;
Ghostty pointers never escape the terminal lock. Synchronized-output holds
retain owned rows and image pixels, with a one-second native deadline that
releases forgotten holds even if the SSH stream is idle. See the
[adapter contract](native/nova-ghostty-zig/README.md) for wire format and limits.

Session owners live in the application; navigation lives in each activity's
ViewModel. The launcher activity uses `singleTop` so reopening the app preserves
the current screen and selected terminal tab. Explicit SSH document windows use
a separate, non-exported `TerminalWindowActivity` with standard launch mode,
sharing the same session registry while keeping independent navigation. Brief
backgrounding does not mean detach; explicit Back/Detach still returns to the
host or session list. This does not promise recovery after process death.

Herdr checks the retained endpoint after returning to a visible screen. A
matching health pong and coherent display must arrive within five seconds;
otherwise it replaces the client SSH connection and attaches to the same named
remote session, with a twenty-second reconnect deadline. The existing terminal
stays visible with a restoring status. Input remains gated until the display is
current, and recovery time does not accrue usage. Leaving the screen cancels
recovery; it neither restarts remote shells nor replays pending input/actions.

The browser and optional external clients use an authenticated local proxy
bound to the device loopback interface. Tailscale destinations use the shared
node; public internet destinations use the node's ordinary network dial path.
Browser proxy credentials and saved SSH secrets are encrypted using keys
protected by Android Keystore. The app keeps its tailnet state in private
storage and disables upstream remote diagnostic log uploads.

The repository includes build scripts and dependency locks for the Go, Rust,
Zig, Gradle, and Android components. `third_party/` records upstream pins and
adaptations, and `THIRD_PARTY_NOTICES.md` lists bundled licenses. Play
submission records, service-account credentials, and upload signing material
are maintained outside this public source repository.
