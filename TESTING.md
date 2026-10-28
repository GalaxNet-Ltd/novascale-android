# NovaScale Android MVP Test Guide

This guide covers the `0.1.0-dev` owner-test build. Use a non-sensitive test tailnet and hosts for the first pass.

## 1. Build and install

From `android/`:

```bash
export JAVA_HOME="$HOME/android-studio/jbr"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./gradlew test :app:lintDebug :app:assembleDebug
./scripts/verify-debug-apk.sh
"$ANDROID_SDK_ROOT/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with the local Android debug key and must not be uploaded to Google Play.

For repeat testing on a persistent virtual device, the shorter path is:

```bash
./scripts/run-emulator.sh
```

The first run creates `NovaScale_API_33`, builds and installs the APK, and opens
the app. Authenticate once, then reuse the same command for future builds. Both
the AVD userdata and NovaScale application data are preserved. Use
`./scripts/run-emulator.sh stop` for a clean shutdown; do not delete/wipe the
AVD, uninstall the app, clear `cc.galaxnet.novascale`, or change the debug
signing key if the authenticated test identity must survive.

## 2. Tailnet

1. Open **Home** and tap **Connect tailnet**.
2. Keep `https://login.tailscale.com` for Tailscale, or enter an absolute HTTPS URL for a custom compatible server, then tap **Start and sign in**. Confirm an external browser opens; complete authentication and return to NovaScale.
3. Confirm the state becomes **Connected** and the expected peers and addresses appear.
4. Confirm **Online only** is selected by default and every displayed host is online. Clear the chip and confirm offline hosts appear immediately from the retained snapshot; selecting it again must hide them without a refresh or an empty-list flash.
5. With host cards visible, switch between **Settings** and **Home** repeatedly. Every first Home frame must contain the existing host list—there must be no empty-list flash or screen-owned reload. Confirm an online/offline peer change is reflected without leaving Home.
6. Open an online host and tap **Ping**. Confirm ten discovery-ping rounds appear with Last/Avg/Min/Max latency, a chart, and per-round results. The route badge must report **Direct connection** and a copyable endpoint, **DERP relay** and its region, or **Peer relayed connection**, consistent with `tailscale status`/`tailscale ping` from a trusted comparison device. Tap **Retry**, then immediately go back; the request must cancel without a crash or a later stale-screen update.
7. Force-stop or swipe away NovaScale, then relaunch it. Without tapping a connection control, the stored profile must automatically start the embedded node and return to **Connected** without requesting an unnecessary new login.
8. Use **Log out** under **Settings** only after the feature tests below; it invalidates the embedded node identity while retaining the isolated local profile directory for a later login.

NovaScale runs one app-scoped userspace node. It does not install a VPN and does not replace the device's existing VPN.

## 3. SSH terminal

1. From **Home**, tap a discovered host and open **Terminal**. With no saved profile, NovaScale must open **SSH settings** instead. Save the username, port, and authentication method; NovaScale must continue directly into the terminal without showing the SSH fields again.
2. Return to the host and open **Terminal** again. A configured host must go directly to the full-screen connection/session surface and begin connecting automatically.
3. Confirm the back button, host/status, add-tab, and disconnect controls are visible directly below the status bar; the terminal Canvas must not paint a blank band over them. With one session there is no separate tab strip; adding a second session reveals it.
4. Tap the terminal to show the software keyboard. The two-row accessory keyboard must remain directly above it, and the terminal viewport must end above the accessory rather than drawing behind it.
5. Press **Esc**, an arrow, or another accessory key. The software keyboard must remain visible and subsequent software-keyboard input must still reach the terminal.
6. While the software keyboard is visible, use the terminal header back action or **Detach**. The destination screen must appear without an IME, and Android's back gesture must not be needed to clean up a stranded keyboard.
7. Hide the software keyboard through the normal Android keyboard action or connect a hardware keyboard. The accessory keyboard must remain visible at the bottom of the terminal while the terminal viewport continues to end above it. Then press the trailing **Hide keyboard** icon: both the keyboard and complete accessory surface must disappear, the terminal must expand into their released space, and tapping the terminal must restore the appropriate software-keyboard/accessory combination.
8. Test **None / Tailscale SSH**, password, and pasted OpenSSH private-key authentication as applicable.
9. On the first connection, compare the displayed SHA-256 host-key fingerprint with a trusted source before accepting it.
10. Confirm a shell appears at a readable default size. Test typing, Enter, Backspace, Unicode, pinch sizing, rotation, and resize. Run `seq 1 200`; drag downward in the terminal to reveal older numbered lines, then type one character and confirm the viewport automatically returns to line 200 and the active prompt. Scroll again and drag upward to return manually. Repeat with a mouse or trackpad wheel where available. Scrolling must move Ghostty's retained viewport without printing arrow-key escape input or moving terminal content behind the accessory row.
11. Open **Settings → Terminal appearance**. Exercise all five built-in palettes, all five font choices, sizes from 8–32 sp, and Application/Block/Beam/Underline cursor choices; the preview must update immediately. Application must honor cursor-shape requests from terminal programs, while each fixed choice must retain its selected rendered shape without making a natively hidden cursor visible. Import a normal `.itermcolors` preset through the document picker (`testdata/terminal/NovaScale-Test.itermcolors` is a non-sensitive fixture) and confirm it becomes selected; malformed, entity-bearing, XInclude-bearing, and larger-than-512-KiB files must be rejected. Restart NovaScale and confirm the selected theme, font, size, and cursor shape persist. Reopen the shell and confirm palette changes reach ANSI output, the title/status area, multi-tab strip, accessory keyboard, and system bars rather than only the preview; light palettes must use dark system icons, dark palettes must use light icons, and pinch sizing must update the Settings value.
12. Exercise the two accessory rows: **Detach** (arrow leaving a square), **Close**, **Esc**, **Ctl**, **Alt**, **Tab**, navigation, and **Paste**; fixed **Shift** and **Insert newline**, scrolling symbols/F1–F12, and fixed **Hide keyboard** below. On a compact screen, drag the compact navigation icon in each direction and hold to repeat; returning to its center, release, cancellation, backgrounding, and switching tabs must stop repeat. Tap the navigation icon for discrete arrow buttons. At widths of at least 600 dp, separate arrows replace the pad. Confirm Ctrl/Alt/Shift are visibly selected, apply once to an accessory key or IME/hardware input, then clear. Test Shift+Tab and Shift+arrows in Codex and other remote TUIs, including negotiated Kitty keyboard mode. Insert newline is enabled only when the remote program enables bracketed paste; it must insert a line without submitting the prompt. Symbols scroll independently of Shift, newline, and Hide keyboard.
13. Review Home, Host Detail, Settings, Terminal connection/loading, Files, and file Preview in light and dark mode. Ordinary icons must use transparent or neutral surfaces instead of a same-hue translucent background; selected bottom navigation must use Primary with no selection pill; filled icon controls must use a contrasting foreground. Host platform tiles remain the only colored icon tiles in ordinary list rows.
14. Add, switch, and close terminal tabs with **+** and **×**. Android terminal tabs are free in the initial release; the MVP caps a host group at six and uses an independent SSH connection for each tab.
15. Change the server host key in a disposable environment and confirm NovaScale blocks it and identifies the saved fingerprint as changed.
16. With a live shell, tap the leading **Detach** action in the accessory row. Return from Host Detail to Home. An **Active Sessions** section above **Hosts** must show a card containing the host, start time, and tab count. Tap that card and confirm the existing screen, every tab, and each shell return without a new SSH login banner. The terminal back arrow is a second detach affordance.
17. Detach again, open the same host's detail screen, and tap **Terminal**. It must start a fresh SSH session rather than reattaching the card shown on Home. Detach the fresh session and confirm Home shows two independent Active Sessions cards for the host; closing one card must not close the other.
18. Send a recognizable command, briefly switch Home or another app to the foreground, and return. The same process and shell must remain live, with output that arrived while NovaScale was backgrounded still visible.
19. Under **Settings → Terminal**, enable **Keep sessions in background** and allow notifications. With at least one live terminal group, confirm Android shows the low-priority NovaScale terminal notification and reports `TerminalBackgroundService` as a `connectedDevice` foreground service. Disabling the setting or closing every group must stop it.
20. Tap the terminal header's **Open terminal in a new window** action, then place the two NovaScale document tasks side by side on a large-screen or multi-window device. The new activity must attach to the existing group rather than creating an SSH connection. Both views may render the session; only the currently focused window should determine PTY size.
21. Use the notification's **Close all** action only in a disposable test. It must close every SSH runtime and remove the Active Sessions cards.

Saved credentials are AES-GCM encrypted with a non-exportable Android Keystore key and excluded from backup/device transfer. Known-host SHA-256 fingerprints are persisted separately because they are not secrets.

Detach is process-local session retention, not serialization. A force-stop,
process kill, reboot, or OS process reclamation destroys live sockets. Durable
shell continuity after that boundary requires reconnecting, preferably to a
server-side session such as `tmux`.

### Kitty terminal graphics

After installing both debug APKs, run the no-login fixture:

```sh
adb shell am instrument -w -e suite graphics cc.galaxnet.novascale.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

It exercises the packaged Ghostty/Wuffs decoder, fragmented APC input,
RGB/RGBA/PNG, zlib/chunking, Unicode placeholders, crop/scale/z metadata,
image-ID replacement, ED2/delete/reset, alternate-screen isolation/restoration,
scrollback visibility, malformed/oversized/local-file rejection, decompression
limits and recovery, and Android Canvas pixels including alpha and stale-image
cleanup. It also verifies unchanged native snapshots omit image payloads.

For a real SSH session, copy `testdata/terminal/kitty-smoke.py` to a disposable
host and run it with Python 3. Test its PNG, RGBA and zlib options. Then run
`clear`, enter/exit Vim, scroll history, rotate, resize and change terminal fonts.
Use the inherited `TERM=xterm-256color`; a host overriding TERM to `linux` emits
different clear/alternate-screen sequences. Repeat with `kitten icat` over SSH
using direct transfer (`--transfer-mode=stream` where supported), and with tmux
Unicode placeholders. Check a transparent PNG, repeated image IDs and a larger
image below 8 MiB decoded. Source the local terminal helper's own `--help` for
its exact available flags. Physical ARM64, the full API/page-size matrix and
live tmux/tool integration remain device acceptance checks.

## 4. SFTP

1. From the same host detail view, open **Files**. It must use the saved SSH profile and connect automatically without showing editable SSH fields.
2. Confirm the same first-use and changed-host-key policy as the terminal.
3. Navigate directories, use **Up** and **Refresh**, and open a UTF-8 text file no larger than 2 MiB. The editor must be the native Sora view with line numbers plus **Undo**, **Redo**, **Find**, and **Wrap** controls.
4. Open representative `.c`, `.cpp`, `.java`, `.json`, `.kt`, `.properties`/`.conf`, `.py`, and `.xml` files. The toolbar must identify the matching Tree-sitter grammar, syntax colors must appear, and no linker/query exception may be logged. Unknown extensions must use **Plain text**.
5. Exercise selection, software and hardware keyboard input, undo/redo, search next/previous, horizontal scrolling, wrapping, rotation, and editor reopening.
6. Edit and save a non-sensitive test file, then reopen it to verify the remote content. Do not modify a credentials, token, or environment file during editor testing.
7. Confirm a non-UTF-8 or oversized file is rejected without being shown as editable text.
8. Confirm hidden dotfiles are omitted initially. Use the action menu to show them, search the current folder, and exercise all six name/date/size sort orders. Entering a folder or going to its parent must clear the old in-folder search and dismiss its keyboard.
9. In a disposable writable directory, create a file and folder, rename each, and verify attempts to create or rename onto an existing item are rejected without changing that item.
10. Upload a file with Android's document picker. Confirm transfer progress appears for a large file, cancellation removes the hidden remote temporary, and a completed upload matches the local SHA-256 hash.
11. Download a remote file with Android's create-document picker. Confirm its SHA-256 hash, then cancel a second large download and verify the partial local document is removed.
12. Delete the test file through the destructive confirmation. Confirm a non-empty folder cannot be deleted, then empty it and delete it through the folder confirmation.
13. Interrupt upload/download by closing Files and by dropping the network in a disposable test. NovaScale must close the transferred descriptor, clean up partial state where the provider/server permits it, keep raw remote errors out of UI/logs, and reconnect on the next Files launch.
14. Start a large download, switch to **Settings**, optionally open a Settings detail, and tap **Home**. NovaScale must restore the same Files directory and transfer card rather than Hosts or Host Detail, and the transfer must continue to completion. Repeat the section switch in the opposite direction to confirm both tabs retain their own detail destination.
15. Start another disposable transfer and use the Files top/system **Back** action instead of switching tabs. This is an explicit pop: the SFTP session must close, the transfer must cancel, and its partial local document or hidden remote temporary must be cleaned up.
16. Open a file's actions and choose **Preview**. Exercise PNG/JPEG/GIF or WebP, MP3/Opus audio, MP4/WebM video, and a multi-page PDF. Images must fit initially and support pinch zoom/pan; media must expose play/pause/seek and pause when NovaScale leaves the foreground; PDF pages must scroll without loading every page bitmap at once.
17. While a large preview downloads, confirm progress is visible, tap **Cancel** or Back, and verify the preview disappears without closing the SFTP directory. Switch to **Settings** during a preview download and return through Home; the retained Files destination must resume the same preview state. Closing Preview must delete its private cache directory.
18. Open **Settings → File previews**. Confirm the default is 10 MiB, exercise 5/10/20/50/100 MiB and explicit Unlimited, and verify an oversized file is rejected before download with a useful message. Enable **Tap to preview**, return to the retained Files screen, and confirm a file row opens Preview while **Edit** remains in its action menu.
19. Preview an unsupported but non-sensitive format such as `.xlsx`. Confirm NovaScale offers **Open with another app**, Android only receives a temporary read-only `content://` grant for that file, and no broad storage permission is requested. If no compatible app is installed, the preview must remain open with an explanatory error.

LSP, completion, chmod, duplicate, recursive delete, and persistent background
transfers remain outside this focused file-tool slice.

## 5. Shared proxy and Browser

The owner-test build exposes Home, Browser, and Settings. Public release still requires the full device/network matrix and residual-risk review.

1. Connect a tailnet. Settings → Local proxy must show an automatic loopback port and masked generated credential. Test reveal/copy, rotation, disabled state, and restart persistence. Credentials must never appear in logs or notifications.
2. Apply an available preferred port, then occupy that port with another app and restart the proxy. Verify automatic fallback, the actual endpoint in Settings, and a warning with notification permission both granted and denied. Browser must use only the actual port; the occupied listener must receive no credentials.
3. Open Browser: test HTTP and HTTPS MagicDNS targets, public sites, redirects, Grafana WebSockets, and Jellyfin playback/seeking. Test IPv4/IPv6 and subnet routes. Invalid certificates must fail with no bypass. Website authentication must never receive proxy credentials.
4. Test authenticated external SOCKS5 and HTTP clients. Missing/wrong credentials must be rejected. Localhost, link-local, and DNS aliases resolving to device-local addresses must fail.
5. Switch tabs: the shared proxy remains available. Backgrounding stops it by default. Enable background sharing and verify the ongoing notification and Stop action, screen-off/Doze, network transitions, process death, and manual restart. Background lifetime is best effort, not guaranteed indefinite execution.
6. Logout, disable, rotate credentials, or change ports while HTTP, CONNECT, and WebSocket traffic is active: all old connections must close. WebView must never fall back to DIRECT. Switching profiles must clear web storage/cookies; the current implementation also clears them on the first Browser session after process restart.

A no-login device fixture exercises production WebView authentication and a separate-UID app's rejection by the native proxy:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w cc.galaxnet.novascale.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

On 2026-09-22 this passed on API 33 x86_64. The CONNECT fixture proves authentication only and intentionally returns 502 afterward; it does not prove HTTPS content, certificate handling, or live tailnet routing. Go race tests cover HTTP range/WS forwarding, SOCKS/CONNECT, cancellation, address policy, credential stripping, and preferred-port fallback. Repeat on API 28/35/36 and physical ARM64 before release.

## 6. Source/runtime check

Open **Settings** and tap **Open source**. Confirm it shows the pinned Rust/Russh/Ghostty versions and:

```text
Ghostty VT runtime probe: Ghostty
```

That line proves the packaged Android process loaded the Rust library, initialized the pinned Ghostty terminal engine, serialized its native terminal state, and parsed it in Kotlin.

The same screen must identify Sora Editor `0.24.4`, Android Tree-sitter
`4.3.2`, the supported highlight grammars, the deliberate absence of LSP,
the bundled terminal themes, the SIL OFL terminal fonts, and Media3 `1.10.1`
plus the framework image/PDF preview path.

## 7. Report a test result

Record:

- Device model, Android/API version, ABI, and 4 KiB or 16 KiB page size.
- Official Tailscale or custom control server.
- Direct/DERP path, MagicDNS/IP/subnet destination, and authentication type.
- The screen, action, expected result, actual result, and whether it reproduces.
- A redacted log excerpt if useful; never include passwords, private keys, terminal content, auth URLs, or sensitive internal URLs.

Known pre-release gaps are physical ARM64 coverage, API 28/35/36 coverage,
Doze/process-pressure and multi-window stress, foreground-service Play Console
declaration review, accessibility review, terminal selection and URL
recognition, durable reconnect UX, expanded SFTP operations, and Play release
preparation.

## Terminal mouse reporting and touch gestures (2026-09-22)

Run the no-login native/Android gesture fixture after installing both debug APKs:

```sh
adb shell am instrument -w -e suite mouse cc.galaxnet.novascale.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

For the accessory keyboard, run `-e suite keyboard` with the same runner. This
includes the mouse/clipboard regression fixture and checks native keyboard-mode
push/pop, alternate-screen isolation and reset, legacy/enhanced Shift+Tab,
bracketed newline, discrete arrows, drag direction/repeat/dead-zone, cancellation,
tab disposal, simulated lifecycle suspension, and one-shot Shift through IME and hardware input. It uses synthetic
text with no SSH login. Live Codex interaction and physical touch ergonomics
remain device acceptance checks.

This checks actual Ghostty DECSET/DECRST/reset mode snapshots, Android tap-to-SGR
click, one-finger swipe-to-wheel, hold-and-drag with cancellation/release, two-finger right-click,
and local scrollback with reporting disabled. Pure unit tests cover SGR button,
release, motion, modifier, coordinate, legacy byte and mode filtering behavior.

Owner acceptance on a live SSH session:

- In tmux, run `set -g mouse on`. Tap panes, hold then drag pane borders,
  scroll history with a vertical swipe, and confirm lifting/cancelling releases the
  button. Repeat in herdr and other mouse-aware TUIs.
- Disable tmux mouse mode or exit the application. One-finger swipes must return
  to local history scrolling, with no escape sequences entered at the prompt.
- Double tap and copy text in both modes; pinch to resize without leaving a
  button held. Repeat with the IME visible, changed font size, landscape, and
  session/tab/window switches.
- Test external left/middle/right buttons, wheel, Shift-local scrolling/selection,
  and 1003 hover with a physical mouse/trackpad. Check 1006 positions beyond
  column 223, wide glyphs, modifiers, and the screen edges.
- Open Terminal → More → Terminal gestures and Settings → Terminal gestures;
  verify English, Simplified Chinese, and Traditional Chinese text and TalkBack.

Live tmux/herdr behavior and physical touch/mouse ergonomics still require owner
acceptance; synthetic emulator checks do not replace those tests.

### 1.6.0 gesture and browser corrections

The current iOS guide supersedes the earlier pan-handler-only mapping: tap sends
a left click; double tap selects a local word; hold then drag holds the remote
left button; vertical swipe sends wheel events; two-finger tap/hold sends the
right button and releases when lifted. Pinch ends any held button before zoom.
Confirm tmux menus remain open during a two-finger hold and close on release.

Opening the login screen must not launch an external browser. Edit the control
server first, then press Start/sign-in. Opening or re-entering Browser must show
the address field and Go without navigating; only Go creates/loads WebView.
After navigation, address/navigation controls must remain visible and usable
above the clipped WebView. The proxy fixture now exercises the full Compose
browser screen, verifies no request before Go, clicks Go, checks HTTP auth and
the retained address field, and saves a screenshot for visual inspection.
Repeat the rendering check on the owner's Samsung/WebView 152 device.

### Remote clipboard and accessory paste

The native device probe checks fragmented OSC 52 writes, invalid base64, one-shot
delivery, ignored clipboard-read queries, and bracketed-paste mode. The `suite
mouse` instrumentation also writes a fixture to the actual Android clipboard and
calls the same paste method used by the first-row button, checking plain and
bracketed paste and ensuring latched Ctrl/Alt do not alter pasted text.

On a foreground SSH terminal, run:

```sh
printf '\033]52;c;Y2xpcGJvYXJkLXRlc3Q=\007'
```

Press the first-row paste button: `clipboard-test` must appear without executing
it. Repeat with tmux/herdr copy, Unicode, multiline text, and bracketed-paste mode.
Switch away or background the app and confirm remote output cannot overwrite the
phone clipboard. Content is not logged, persisted, or returned via OSC 52 reads.

### Host/browser UI and monospace styling

Run the instrumentation with `-e suite ui` and `-e suite ui-dark` to verify host
cards and the SSH toolbar action. Fixtures save `host-cards-check.png` and
`host-cards-dark-check.png` in app cache for visual review. The normal proxy
fixture now also enters/exits a synthetic WebView fullscreen custom view with
Android Back; this verifies view ownership, not actual Jellyfin playback.

Check on the owner device: Terminal/Web Access/Files/Ping cards; SSH toolbar;
host URL/port prefilling with no automatic request; browser start screen, bottom
controls, address editing with keyboard Go, copy/share URL, mobile/desktop mode,
back/forward/reload/stop, and returning to app navigation. Play a Jellyfin video,
enter fullscreen, rotate, exit/back, and background/return. Verify no detached
video view or hidden controls. Test both themes, Chinese fallback, 1.5–2x system
font size, TalkBack, and a narrow/split-screen window. App text uses JetBrains
Mono; terminal/editor preferences and website typography retain their own fonts.


### SSH password retry regression

The `ssh-retry` instrumentation suite exercises actual Rust SSH authentication
through a local-only fixture. Start `python3 testdata/terminal/ssh-retry-server.py`
(Paramiko is needed on the development host), reverse an unused device TCP port
to the reported loopback port with `adb reverse`, then pass `-e sshPort PORT` and
`-e sshFingerprint SHA256:...` to the existing instrumentation runner with
`-e suite ssh-retry`. It checks wrong-password failure, repeated Retry, corrected
credentials in the same group/runtime, and healthy reattachment without a new
connection. The fixture uses synthetic credentials and restores its known-host
entry on exit.

For an explicitly authorized owner-device check only, `-e suite ssh-retry-live
-e host HOST_NAME` uses the existing shared tailnet and in-process saved password.
It deliberately tries one wrong password, then uses the saved correct profile
in the same runtime. It requires an already trusted host key and never changes
saved credentials or logs them. App installation/instrumentation restarts active
terminal connections; use a disposable shell. The `accessory` suite checks only
the compact keyboard, at 339 dp and 1.15 font scale, without changing clipboard
contents. It verifies every primary key fits, compact height, Shift, arrow
navigation/repeat, cancellation and lifecycle suspension.
