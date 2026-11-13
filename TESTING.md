# NovaScale Android MVP Test Guide

## Independent terminal renderer

The terminal uses NovaScale's own cached Android surface; see
[`terminal-ui/README.md`](terminal-ui/README.md) for cache bounds and provenance.
After building/installing the community debug app and test APK as below, run:

```bash
adb shell am instrument -w -e suite renderer \
  cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

Use an unlocked, foreground disposable test device. This suite uses synthetic
terminal text and the clipboard. It compares cached/direct software and hardware
pixels, all bundled fonts, edited-row invalidation, GPU display-list reuse,
IME composition/rebinding, Unicode selection and cache bounds. Android 9 tests
the direct-Canvas fallback; Android 10+ also checks retained RenderNodes.
Results and a fixture screenshot are in the test app's private cache as
`terminal-renderer-check.txt` and `terminal-renderer-hardware.png`. Timing is a
short row-cache on/off software-Canvas comparison; font measurement remains
cached in both cases. It does not establish end-to-end or battery performance.

Run `-e suite terminal-regressions` with the same runner to check that terminal
drawing stays inside its viewport and preserves the Compose header with the IME
open/closed. It also checks composing and hardware Ctrl/Alt shortcuts, exactly-once
delivery, pending-word ordering, Shift/CJK input, and input-method recovery after
a shortcut. The test uses synthetic input; it does not type into a user's session.

Also run `keyboard` (including gesture/mouse checks), `graphics`, and the
isolated live Herdr fixture after changing the surface or input lifecycle.
Use the normal release device/page-size matrix and sustained profiling before
public distribution; the synthetic renderer suite does not replace them.

## Billing development variants

The `billing` instrumentation suite uses a fixture store and never makes a real
purchase. Current debug IDs are `cc.galaxnet.novascale.debug` (Play) and
`cc.galaxnet.novascale.community.debug` (community); their instrumentation package
adds `.test`. The examples below use the community flavor. Play release
packaging checks the source/dependency licensing boundary. Native verification takes
the variant APK path as an argument; its default is the community APK. See the
[dependency record](third_party/play-billing/README.md) and
[LGPL source/relinking guide](third_party/lgpl/README.md) for distribution requirements.

Run from this checkout, targeting a disposable emulator/test device:

```bash
./gradlew :app:testCommunityDebugUnitTest :app:lintCommunityDebug :app:assembleCommunityDebug :app:assembleCommunityDebugAndroidTest
adb install -r app/build/outputs/apk/community/debug/app-community-debug.apk
adb install -r app/build/outputs/apk/androidTest/community/debug/app-community-debug-androidTest.apk
adb shell am instrument -w -e suite billing \
  cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

The purchase sheet keeps Later and Buy Pro outside the scrolling content. The
`billing` fixture checks their stable screen bounds, dismissal without checkout,
the open-source/fork copy and a small 320 × 360 dp viewport at 180% text scaling,
alongside cancellation, pending payment, purchase and restoration.

## Pro feature enforcement

Use Settings → Debug: Pro status to select Free, Pro or Default. The same controls
appear in debug upgrade sheets. **Debug: Herdr usage** immediately below them
shows the local quota, partial foreground seconds, retained/active attachments,
covered visits and next reset. Pro access leaves free counters visible while
explaining that new Pro visits do not use them. There is no diagnostic quota reset.
Free mode allows one tailnet, three custom groups and three meaningful Herdr
visits daily. A visit counts after 30 accumulated seconds of connected foreground
use. Check second-profile/fourth-group admission, legacy preservation and
free-profile replacement. Reconnect, Sessions reopen and rotation must not spend
another visit; explicit close/new attach may start another visit.

```bash
adb shell am instrument -w -e suite pro-enforcement \
  cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

The fixture uses isolated metadata and no checkout. It tests concurrent group
admission, actual profile schema migration, grandfathering, paid-only selection,
free selection/replacement, Room usage persistence and debug controls. It also
checks the usage panel while a controlled clock advances through a partial visit,
inactive time, the 30-second charge and Free/Pro switches. The live
Herdr UI fixture additionally injects a free usage manager and controlled clock
while using the real local SSH/Herdr bridge, verifying retained visit IDs across
screen detach and transport recovery, and a new ID after explicit close.

## Multiple saved tailnets

The Home tailnet button and Settings → Tailnets open profile management. Add a
named Tailscale or custom HTTPS profile, select it, confirm the switch and sign
in. Profiles on the same control server have separate identities. Switching
preserves saved login and closes tools using the old node; direct SSH sessions
remain independent. Rename is available in each row's menu. Switch away before
removing a profile. Changing servers requires adding a new profile.

With the debug and androidTest APKs installed on a disposable emulator:

```bash
adb -s emulator-5556 shell am instrument -w -e suite tailnet-profiles \
  cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
adb -s emulator-5556 shell am instrument -w -e suite tailnet-profiles-ui \
  cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

These fixtures isolate metadata and encrypted settings from existing accounts.
They cover legacy migration, duplicate-server identities, scoped SSH/trust/proxy
credentials, active selection after reopen, failed/interrupted deletion, native
node replacement and rejection of stale-profile dials. UI checks exercise
switch cancellation, add/custom URL validation, rename and confirmed removal.
Unit tests cover application-owned switching, pending-login cancellation,
persistence/startup failure and recovery. No billing is involved.

Before public release, use two authorized non-sensitive networks (including
Headscale), verify peers and real SSH/SFTP/Herdr/browser sessions after repeated
switches, rotate/recreate activities, force-stop and reopen, and test background
and network transitions across the supported device matrix. An unauthenticated
local-node fixture cannot prove remote account restoration or live routing.

## Herdr protocol and release checks

Herdr actions, destinations and onboarding are shared by debug and release
builds. Only the diagnostic usage panel and Pro overrides are debug-only.
These focused checks do not certify the whole device/release matrix. The incoming
protocol decoder does not yet enforce a proven aggregate memory budget across
decoding, retained surfaces, rendering and concurrent sessions.

```bash
cargo test --locked --manifest-path native/nova-core-rust/Cargo.toml
NOVA_HERDR_TEST_BIN=/path/to/herdr cargo test --locked \
  --manifest-path native/nova-core-rust/Cargo.toml \
  isolated_live_bridge_first_frame_and_reattach -- --ignored
python3 scripts/test-herdr-native.py --serial emulator-5556 --herdr /path/to/herdr
python3 scripts/test-herdr-native.py --serial emulator-5556 --herdr /path/to/herdr --stress
python3 scripts/test-herdr-native.py --serial emulator-5556 --herdr /path/to/herdr --ui
```

The live fixtures use a dedicated temporary XDG namespace and a uniquely
identified disposable Herdr session. They do not attach to the user's default
session. The Android check requires installed debug and androidTest APKs,
`adb`, Python and Paramiko. Add `--flavor play` when testing the Play debug package. It starts an ephemeral local SSH server and removes
its own dynamic ADB reverse mapping; it does not test tailnet routing.
Rapid tab/workspace input goes through the focused terminal view's input
connection only after its displayed binding matches the current target. The
fixture simulates editor creation if the sheet left the keyboard hidden; it
does not bypass the view or retry a submitted command after a resize.
It also exits a named remote shell and checks that its tab disappears from both
the navigation model and the visible bar.
Herdr keyboard checks compare actual IME insets with the accessory's accessible
controls. They cover initial focus, opening from the toolbar, system Back and
Hide Keyboard, recovered terminal height, clearing one-shot modifiers, returning
from the background with input hidden, and reattaching a retained session.
On devices with an attached alphabetic keyboard, initial focus may leave the
soft keyboard hidden; the accessory must remain hidden too. On a phone without
one, also verify that initial attachment opens input automatically and that
tapping the terminal restores the keyboard and accessory after dismissal.
The UI fixture also fills a pane with history, hides its retained screen,
scrolls it through a separate fixture API client, and reopens it twice. Each
reopen must show the latest output without replacing the SSH connection. Native
checks cover reset acknowledgement/surface ordering, stale input, and preserving
manual scrollback during ordinary resizes.
The lifecycle regression then keeps one attachment active while repeatedly
closing/replacing the other across two local SSH endpoints and switching named
sessions through discovery/selection on the same endpoint. No retry or delay is
allowed between close and replacement. JNI checks also verify immediate slot
reuse while continuing to reject a third genuinely active attachment. A closing
attachment retains its reservation until all native owners have released it;
replacement admission waits at most five seconds on the IO dispatcher.
The optional stress run resizes two concurrent native attachments to 500×300,
sends 149,000 color-changing cells through one, and prints point-in-time process
PSS and the largest observed JNI snapshot extraction plus Kotlin decode time.
It does not retain two Compose screens or measure Canvas frame time. These are
not peak measurements or a substitute for long-running and physical-device tests.

This guide covers the `0.1.0-dev` owner-test build. Use a non-sensitive test tailnet and hosts for the first pass.

## 1. Build and install

From the Android source checkout root:

```bash
export JAVA_HOME="$HOME/android-studio/jbr"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
./gradlew test :app:lintCommunityDebug :app:assembleCommunityDebug
./scripts/verify-debug-apk.sh
"$ANDROID_SDK_ROOT/platform-tools/adb" install -r app/build/outputs/apk/community/debug/app-community-debug.apk
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

## Host groups

On **Home → Hosts**, tap the leading **Filter and groups** toolbar icon and use
**New group** to create two disposable groups. Open one tailnet peer and one
traditional host and use **Groups** to add both hosts to the same group; add one
host to both groups. In the filter sheet, expand the group and confirm both
available members appear; opening a member must navigate to its host detail.
Tap the group name to filter; its separate disclosure button expands members.
Use **Manage hosts** to assign several hosts at once. Check **All hosts**,
**Tailnet hosts**, **Traditional hosts**, **Ungrouped**, and each named group.
The active filter appears beside the toolbar icon, and the filter action is
absent on the Sessions tab. Toggling **Online only** must affect tailnet peers
while traditional hosts remain visible in matching filters. Remove a member
from an expanded group and confirm it appears under **Ungrouped** once it has
no remaining memberships.

Use a group's options menu to rename it, restart the app, and verify its name and memberships persist.
Temporarily hide an offline peer with **Online only**, then show offline peers
again; its group membership must be retained. If a tailnet peer has no stable
identity, it must remain visible and its Groups action must explain why it cannot
be assigned. In a signed-out test environment, verify groups and traditional
hosts remain usable without connecting the tailnet. Delete an empty group and a
populated disposable group: the selected deleted filter must reset, and the hosts,
other memberships, and any live connections must remain intact. Do not log out a
working tailnet or delete existing groups just to perform this test.

Pin one tailnet peer and one traditional host from their detail screens. In
**All hosts**, both must appear in the pinned section before every unpinned
host, with an accessible pinned indicator; relative order follows the original
host list within each partition. **Online only** still hides offline peers.
Other group and host-type filters retain their original order without pinned
badges. Unpin each host and verify it returns to its usual position. Restart
the app and temporarily omit a pinned peer to verify pins persist while the peer
is absent. Pinning remains available for traditional hosts when signed out;
peers without a stable tailnet identity explain why they cannot be pinned.

## Hosts and Herdr interaction checks

The `host-refinement` suite uses synthetic mixed hosts to check address/name
search combined with group filters, one-tap group selection, multi-host editing
through a search, group creation during assignment, retained Herdr session
navigation, cancellation, and explicit selection of stopped/named sessions.
The `host-groups` suite also verifies bulk edits preserve absent peers and
other groups/profiles, and roll back if a selected host was deleted.

```sh
adb shell am instrument -w -e suite host-refinement cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

The optional Herdr `--ui` fixture drives the production Compose screen through
the isolated local SSH bridge, uses accessory/hardware input, swipes through
host-owned history and back to the bottom, checks pointer-wheel history, and
delivers wheel events to a real mouse-reporting alternate-screen child process.
It uses the main activity's edge-to-edge/IME sizing so touch gestures reach the
visible terminal. It also creates and renames a tab, types into it, creates and
switches workspaces, cancels last-tab close,
closes only its disposable extra tab, detaches and reopens, then reconnects to
the same remote session and checks local-close cancellation. Run with both
Herdr 0.9.1 and 0.9.5. The `herdr-controls` suite uses synthetic agent metadata
to verify status/attention, cross-workspace agent selection, current-workspace
tabs, stable action targets and capability gating. It needs no tailnet or agent account.
The controls fixture also measures the compact 40 dp tab strip (98 dp including
the main toolbar and progress slot). The live fixture backgrounds and resumes
the screen, checking the same native connection and metered visit are retained.
Use a non-sensitive fixture; it creates and cleans up its own named remote
session. This does not verify live tailnet routing or production resource budgets.

Add `--recovery` to the Herdr `--ui` command to exercise foreground recovery.
It leaves a healthy attachment in the background for 65 seconds, then stalls
only the fixture's Herdr data stream while SSH keepalives continue responding.
It verifies replacement of the stalled bridge, retention of a shell variable
and the same daily usage visit, input through the terminal editor, cancellation
when leaving during a probe, and recovery after reopening. The two faults and
exactly three bridge connections are checked on the host. These fixtures use
their own session and XDG directories and do not suspend an owner session.

Manual checks: use **+** in the Hosts toolbar to add a host, and the leading
filter button for groups. Tap a group name to filter; use its disclosure control
to expand members and **Manage hosts** to assign several at once. Groups can
also be created from a host's assignment dialog. Search by host name/address
without losing the selected filter. Check long names, large text, light/dark
themes, screen-reader selection states, offline hosts and missing credentials.
On either host type, verify Herdr is beside Terminal. In Herdr, inspect sessions
before pressing Connect, create a named session, refresh, and edit the executable
under Advanced. The session, workspace and agent pickers open at medium height
and can expand; short windows/large text open expanded. Connect/New workspace
must remain visible at both heights, with the list scrolling above the action.
The `host-refinement` fixture checks medium/expanded geometry, explicit selection,
dismissal without attachment and 180% text scaling. The `herdr-controls` fixture
also verifies that showing/completing navigation progress never moves the
terminal and repeatedly opens the workspace sheet after software-keyboard use,
keeping its bottom action visible. The live fixture types immediately after
repeated tab/workspace switches and selecting an already focused tab, checking
the destination and absence of retry notices. Native tests ensure the new projection/frame is
published before action completion; stale input is still rejected.
Reconnect must keep the selected session/executable. Detach must
leave a resumable entry under **Sessions**; Close must request confirmation and
leave remote processes running. Navigation detach/reattach is supported within
the current activity; Herdr attachment ownership across activity recreation,
multiple windows and process death remains a separate acceptance gate.

In a disposable Herdr session, use the native tab strip and Workspaces button
to navigate and create remote tabs/workspaces. Tab options offer Rename and
Close; Close terminates remote processes and must show a confirmation, including
the last-tab/workspace consequence. Tap the focused-agent area for the grouped
Agents picker and attention statuses. Only methods advertised by the server
are enabled. A refused action must keep the connection open and show the server
message; an unknown/timed-out result must offer reconnection without replaying
the mutation. Verify input goes to the newly displayed pane after each switch.

## Traditional SSH hosts

Use a disposable SSH server and a signed-out test environment; do not log out an
existing owner tailnet just for this check. From **Home → Hosts → Add Host**, save
a name, hostname, port, username and authentication. **Direct** connects over the
device network without a tailnet login; **Via Tailscale** requires the shared node
and reaches the destination through it. Verify an unavailable selected route fails
without silently retrying through the other route.

- Test password and encrypted OpenSSH private-key authentication, including a
  passphrase. Unknown keys require explicit trust; changed keys must warn and
  require an explicit decision. Rejecting either must close the attempt.
- Edit the host name, endpoint, credentials and route; verify they persist after
  reopening the app. Delete a disposable host and verify its saved credentials
  are removed while other hosts remain. No password or private key should appear
  in logs or backup/device transfer.
- On the host detail screen, exercise Terminal, Files and **Herdr Remote** in
  both debug and release builds. Use an explicitly named disposable remote
  session. Confirm attachment, keyboard input, native controls and Sessions
  reattachment with the release APK; the production action must not depend on
  `BuildConfig.DEBUG`. A tailnet host without saved SSH settings must continue
  to Herdr after saving those settings.
- In the release APK, verify Settings and the Pro sheet omit the detailed Herdr
  accounting panel and debug Pro overrides. Normal free-allowance messaging and
  durable session accounting remain enabled. The aggregate memory/performance
  and device-matrix checks described above remain separate release validation.

The `traditional-host` instrumentation suite verifies disposable host CRUD,
encrypted credential storage, route persistence, interrupted save/delete replay,
and a version-1-to-2 migration in a separate temporary database. The `home` suite verifies
that Add Host is available in a simulated signed-out UI. Both run in the test
activity without logging out the application or deleting existing hosts:

```sh
adb shell am instrument -w -e suite traditional-host cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
adb shell am instrument -w -e suite home cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

The automated checks do not establish the full device, network-transition,
host-key, key-algorithm, SFTP or release matrix. Record the device, route and
server for each live check before public distribution.

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
12. Exercise the two accessory rows: **Detach** (arrow leaving a square), **Close**, **Esc**, **Ctl**, **Alt**, **Tab**, navigation, and **Paste**; fixed **Shift** and **Insert newline**, scrolling symbols/F1–F12, and fixed **Hide keyboard** below. On a compact screen, drag the compact navigation icon in each direction and hold to repeat; returning to its center, release, cancellation, backgrounding, and switching tabs must stop repeat. Tap the navigation icon for discrete arrow buttons. At widths of at least 600 dp, separate arrows replace the pad. Confirm Ctrl/Alt/Shift are visibly selected, apply once to an accessory key or IME/hardware input, then clear. Test Shift+Tab and Shift+arrows in Codex and other remote TUIs, including negotiated Kitty keyboard mode. Insert newline is enabled only when the remote program enables bracketed paste; it must insert a line without submitting the prompt. The first row must spread from Detach at the leading edge to Paste at the trailing edge instead of leaving large centered margins; retain horizontal scrolling when the controls do not fit. Symbols scroll independently of Shift, newline, and Hide keyboard.
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
adb shell am instrument -w -e suite graphics cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

It exercises the packaged Ghostty/Wuffs decoder, fragmented APC input,
RGB/RGBA/PNG, zlib/chunking, Unicode placeholders, crop/scale/z metadata,
image-ID replacement, ED2/delete/reset, alternate-screen isolation/restoration,
scrollback visibility, malformed/oversized/local-file rejection, decompression
limits and recovery, bounded image/placement export, and Android Canvas pixels including alpha and stale-image
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

Symbolic links: open relative/absolute/chained links to both directories and files.
Folder links must sort with folders; all links show an arrow and an accessible
link label. Preview/download and displayed file sizes use the target. Edit/save
through a link, then verify that the link still exists and only its target content
changed. Rename/delete must affect the link itself; deleting a folder link must
leave its target directory and children intact. Missing, looping and inaccessible
targets must remain listed with an unavailable-target message. Creating/uploading
or renaming onto a dangling link must fail without replacing it.

For a repeatable check, start `python3 testdata/sftp/symlink-server.py` on the host
(requires Paramiko), note its ephemeral port/fingerprint, and reverse a disposable
ADB port to it. Run `ProxyInstrumentation` with `-e suite sftp-links -e sshPort
<device-port> -e sshFingerprint <printed-fingerprint>` against a disposable debug
package. The fixture is loopback-only, refuses shell access, and contains only
synthetic data in a temporary directory. Stop it and remove the ADB reverse after
testing. The fixture additionally checks a server whose REALPATH leaves the final
link unresolved: saving must fail without replacing that link or losing the edit.

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
./gradlew :app:assembleCommunityDebug :app:assembleCommunityDebugAndroidTest
adb install -r app/build/outputs/apk/community/debug/app-community-debug.apk
adb install -r app/build/outputs/apk/androidTest/community/debug/app-community-debug-androidTest.apk
adb shell am instrument -w cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
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
adb shell am instrument -w -e suite mouse cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
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
connection. It also checks confirmed shell exits (including nonzero status),
selected/background tab removal, preserving Retry after a transport drop, and
navigation away when the last shell exits. The fixture uses synthetic
credentials and restores its known-host entry on exit.
It transfers a generated 1254×1254 RGB PNG larger than the SSH receive window,
in 4096-byte base64 chunks with outer zlib compression. Upload and placement
acknowledgements must both arrive, the RGBA asset must decode completely, and
subsequent shell input must remain responsive. The fast local fixture has a
20-second test deadline; a real cellular/relay transfer can take longer. No user
image is included in this fixture.
It also starts synchronized output and leaves the server completely idle without
releasing the hold. The app must retain the completed frame briefly, then show
the pending frame after the one-second native deadline without another input,
resize or network read.
It also checks that a Home/Recents round trip preserves the live connection and
that showing the tab strip uses 40 dp of vertical space at normal font scale.

For an explicitly authorized owner-device check only, `-e suite ssh-retry-live
-e host HOST_NAME` uses the existing shared tailnet and in-process saved password.
It deliberately tries one wrong password, then uses the saved correct profile
in the same runtime. It requires an already trusted host key and never changes
saved credentials or logs them. App installation/instrumentation restarts active
terminal connections; use a disposable shell. The `accessory` suite checks only
the compact keyboard, at 339 dp and 1.15 font scale, without changing clipboard
contents. It verifies every primary key fits, compact height, Shift, arrow
navigation/repeat, cancellation and lifecycle suspension.

### Session screen resume

Run the `session-resume` instrumentation suite on a disposable debug package.
It uses the real launcher activity and an application-owned two-tab SSH group,
without credentials or a network connection. A separate activity in the test
APK sends the same MAIN/LAUNCHER intent as a Home icon; a direct `am start` alone
can bring the task forward without exercising duplicate-activity behavior.
The suite checks repeated launcher return, notification return, Recents, activity recreation,
independent document windows sharing the runtime, and explicit detach remaining
detached. Pair it with `ssh-retry` and the Herdr live UI fixture for transport
and usage-ledger continuity. These checks do not simulate process death or Doze.

```sh
adb shell am instrument -w -e suite session-resume cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```


## libghostty-vt dependency updates

The Android build pins `vt-1.0.0` and Zig 0.17.0. On an engine update, rebuild
both native ABIs and run `scripts/verify-debug-apk.sh` on the resulting APK
(use an absolute APK path). Run the `keyboard`, `mouse` and `graphics`
instrumentation suites against that APK; these exercise the packaged Ghostty
library and Canvas, not a host-side fallback terminal.

The native probe reached by `keyboard` checks exact color replies for fragmented
queries, a set/query within one read, theme resets and an unset Android theme;
cell-size reporting after resize; SSH history exceeding the upstream byte-limit
default; and Herdr's zero local history. Graphics includes Unicode and relative
placements, parent deletion, clipped oversized source crops, explicit animation-frame selection,
image-generation replacement, resource caps and software/hardware pixel checks.
The isolated `scripts/test-herdr-native.py --ui --flavor community` fixture
checks actual Herdr input, detach/reconnect and close/replace across host entries.
These complement, rather than replace, the physical device/page-size matrix.

SIMD is enabled on both Android ABIs. The packaged native probe compares one
large read against 1/7/15/16/17/31/32/33/63/64/65/255-byte fragments containing
ASCII, CJK, combining marks, joined emoji, malformed UTF-8 and escape sequences.
Screens, cursor state and replies must match. Run `keyboard` or `mouse` on x86_64
and a physical ARM64 device, plus `graphics` for SIMD Base64/Kitty decoding.
The Rust/NDK link rejects unresolved symbols; the Zig adapter installs and Rust
links the pinned simdutf and Highway static libraries explicitly. Verify the APK
still has 16 KiB alignment and packages both dependency license notices.

Herdr scrolling deliberately keeps Ghostty's local history disabled: the remote
pane owns that history. Rust tests cover pane hit-testing, application/popup
wheel routing, stale geometry rejection, and ordered relative input during
rapid gestures, direction reversals and delayed surfaces. History wheel events
use the negotiated semantic input codec; they do not derive absolute destinations
from displayed scroll metrics or wait for endpoint acknowledgements. The live UI
fixture checks exact accumulated distances for rapid wheel bursts and reversals.
The separate Sessions-reopen operation still restores the bottom with `pane.scroll`. For a scroll change, also run the existing `mouse` and
`keyboard` instrumentation suites to check shared Canvas behavior for SSH.

Herdr's **Improve Text Contrast** switch is under **Settings → Terminal
Appearance → Herdr Remote** and defaults on. The live `--ui` fixture reproduces
a TUI retaining a dark explicit background while using the default foreground,
then changes the physical terminal theme. It checks normal text at 4.5:1 and
faint text at 3:1 using Canvas's actual two-thirds channel dimming, preserves
explicit text/background colors, and toggles the local correction without new
remote output. A real settings-screen check verifies the toggle persists and
restores the prior preference afterward. This deterministic fixture does not
launch Codex or submit an agent prompt.

Native appearance tests also cover named/indexed colors, hidden/reversed cells,
continuations, popups, patches, bounded cache storage, raw-frame preservation and
focus-aware forwarding of the physical theme's complete 256-color palette.
Theme and contrast settings apply to retained attachments; hidden attachments
do not claim foreground ownership from another Herdr client. A correction only
changes the local default foreground where needed; it preserves a remote app's
cached background. Toggle the safeguard off when exact original colors matter.


## Final UI and browser library

With the Community debug and androidTest APKs installed on a disposable emulator:

```bash
adb shell am instrument -w -e suite browser-library \
  cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
adb shell am instrument -w -e suite ui-polish \
  cc.galaxnet.novascale.community.debug.test/cc.galaxnet.novascale.browser.ProxyInstrumentation
```

`browser-library` uses isolated Room/Keystore storage to check encrypted URLs and
titles, persistence after reopen, concurrent/repeated visits, profile isolation,
200-address history retention, favorite editing, history clearing, invalid URLs,
profile removal/late writes, and corrupt ciphertext. `ui-polish` captures the real
browser start page and group controls in both themes and at 200% text size for
visual inspection; captures alone are not an accessibility certification.
The default proxy fixture also exercises Add favorite and Find in Page through
the live WebView, alongside authentication, tabs and retained foreground page state.

Manually verify the leading filter half-sheet, group expansion and management,
selected backgrounds, filtered-header height, and restoring All hosts. Filtering
must not reconnect a tailnet or interrupt an SSH/Herdr session. Review filled
primary actions, outlined secondary actions, neutral destructive actions with red
text, and feature-colored Settings icons, including
large fonts, keyboard focus and TalkBack. Typography keeps the chosen terminal
font/size separate from the interface's tighter monospace spacing.

For browser acceptance, save/edit/remove a favorite; search/delete/clear history;
switch between two profiles containing the same hostname; change only a port or
scheme on a URL with encoded path/query/fragment; cancel address editing; use
Find next/previous/close; return to a live tab after visiting another app section;
and return after the foreground proxy owner was destroyed. The latter requires
explicit navigation and does not promise DOM/form restoration after process death.
Exercise real HTTP/HTTPS/WebSocket services, certificate rejection, custom video,
network changes and supported API/page-size devices before public release.

The `review-fixes` instrumentation suite exercises disabled-proxy confirmation,
cancel/enable/retry, profile mismatch and recovery, tailnet management and hidden
browser re-entry. It captures the real cursor selectors and destructive dialogs
in both themes plus tailnet/Herdr onboarding. The browser fixture constructs its
session through the production identity factory with distinct profile/node IDs;
the active profile is supplied independently to catch identity-scope regressions.
Run it with `-e suite review-fixes` using the same runner above, alongside the
default `browser` suite for authenticated HTTP/CONNECT and lifecycle behavior.
Manually check Go after connecting a real tailnet, disable Local proxy in the
global Settings page and enter Browser, then cancel and explicitly enable it.
Existing external-client proxy credentials must survive the identity fix.
