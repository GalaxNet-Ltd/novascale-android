# NovaScale Android Design System

## Goal

NovaScale should feel like the same product on iOS and Android. The iOS 1.6
application is the interaction reference; Android uses the owner-approved monospace and high-contrast neutral visual style while keeping native
Compose navigation, typography, touch behavior, accessibility, and system
integration.

This document covers the focused Android release only: tailnet login and host
discovery, host detail, SSH setup, Terminal, Files, Settings, and open-source
notices. Features outside the Android MVP are not reproduced merely because
they exist on iOS.

## Product principles

1. **Host first.** Terminal, Files, and SSH settings always belong to a selected
   host. Home shows hosts; host detail shows the available actions.
2. **Configuration is a gate, not a launch screen.** Selecting Terminal or Files
   opens SSH Settings only when the host has no usable saved profile. Saving
   continues the pending action. A configured host goes directly to the tool.
3. **Sessions are immersive.** Connection progress, host-key verification,
   reconnect, tabs, terminal content, and the accessory keyboard belong to one
   terminal surface. Generic SSH form fields do not remain in the normal
   terminal path.
4. **Compact, calm surfaces.** Use grouped rows and restrained borders instead
   of a stack of large elevated cards. Technical values are monospaced and
   secondary metadata is quiet.
5. **Semantic color only.** Brand color indicates a primary action or selection;
   green, red, amber, and blue are reserved for status and feature semantics.
6. **Native Android behavior.** Minimum 48 dp touch targets, predictive/system
   back compatibility, scalable text, TalkBack labels, hardware keyboard input,
   and IME-safe layout take precedence over pixel-copying UIKit.

## Foundations

The semantic roles follow iOS. The palette and interface typography were revised
for Android at the owner’s request on 2026-09-22. `NovaDesignSystem.kt` is the Android source of truth.

### Color roles

| Role | Light | Dark | Use |
| --- | --- | --- | --- |
| Primary | `#3656C9` | `#99B4FF` | Primary actions, selection, Terminal icon |
| Primary shade | `#233EC7` | `#3B57E3` | Pressed/emphasized brand surface |
| Accent | `#52E099` | `#1C5C38` | Supporting accent only |
| Background | `#F3F4F6` | `#0B0F14` | Screen canvas |
| Surface | `#FFFFFF` | `#151B23` | Grouped rows and cards |
| Text primary | `#111827` | `#F3F6FA` | Primary content |
| Text secondary | `#465363` | `#B8C3D1` | Metadata and helper content |
| Border subtle | `#CBD2DC` | `#354150` | Flat surface separation |
| Connected | `#087F5B` | `#4DD89C` | Online/success state |
| Disconnected | `#D9262C` | `#E8595E` | Offline/error/destructive state |
| Warning | `#A35400` | `#FFB454` | Waiting or attention state |
| Info | `#176DA5` | `#569ED2` | Informational state |
| File | `#956600` | `#E4BE25` | Files action |

Primary text contrast against the main canvas is 16.12:1 in light mode and
17.73:1 in dark mode. Secondary text against card surfaces is 7.84:1 and 9.70:1.
Material surface-container roles are neutral too, preventing default lavender
fills from reappearing in browser controls and dialogs.

### Spacing and geometry

- Base spacing scale: 2, 4, 8, 16, 24, 32, and 48 dp.
- Screen margin: 16 dp.
- Standard row gap: 12 dp; grouped row padding: 16 dp horizontal and 12 dp vertical.
- Corners: 8 dp controls, 12 dp cards, 16 dp prominent groups, 24 dp large content cards.
- Touch targets: at least 48 dp on Android.
- Status dots: 8 dp standard, 6 dp compact.
- Host rows: at least 64 dp high.

### Typography

- Use bundled JetBrains Mono for every Material typography role and respect user
  font scaling. Android fallback covers CJK and other missing glyphs. Terminal
  preferences, editors, and website typography remain independently controlled.
- Use regular weight for content/list titles and medium weight for headings,
  section labels and buttons. Avoid synthesized semibold/bold from the bundled
  regular face. Ordinary toolbar titles use 18 sp; body roles use 15/14/12 sp.
  Monospace UI text uses zero added letter spacing and disables synthesized font
  weight/style. Hierarchy comes from size, spacing and semantic color; saved
  terminal fonts/sizes and ANSI bold/italic rendering remain independent.
  Keep Android font scaling and terminal/editor font preferences independent.
- Use the system monospace family for IP addresses, hostnames, ports,
  fingerprints, paths, and terminal content.
- Terminal defaults to 16 sp on compact Android screens. Pinch zoom remains
  available from 8 to 40 sp; settings persistence is a follow-up slice.

## Component patterns

### App shell

- Android has Home, Browser, and Settings. While a webpage is open, browser
  controls replace the application bars; Close/Home returns to the start page.
- A destination pushed from either tab uses an icon-only back affordance and a
  compact title. The bottom bar remains the stable top-level anchor but should
  not compete visually with a full-screen terminal.
- Use platform icons, never text glyphs such as `⌂` and `⚙`, for app navigation.
- Selected bottom-navigation icons and labels use Primary without a same-hue
  selection pill. Unselected icons and labels use Text secondary.

### Icon color treatment

- Ordinary action, navigation, status, file, and empty-state symbols are drawn
  directly on the containing surface. A colored outline must not be placed on a
  translucent fill made from the same color.
- Semantic color belongs to the symbol: Primary for Terminal and general
  actions, File for folders/files, Info for Ping and previews, Connected for
  successful state, and the theme's contrasting error foreground for destructive actions.
- Filled icon buttons are reserved for a strong primary action and must use a
  contrasting on-fill foreground, such as the white Play symbol on Primary.
- Host platform marks are the deliberate exception: their white artwork sits
  on a solid or gradient platform tile so operating-system identity remains
  recognizable at a glance.
- Terminal accessory icons use transparent key surfaces and separate semantic
  foregrounds; the navigation icon stays unboxed until a drag is active.

### Home and host rows

- Home leads with the `Hosts` title and a compact tailnet status row/chip.
- Tailnet hosts default to `Online only`; the chip changes only presentation
  state and never replaces the application-owned IPN peer snapshot.
- Each host row contains a platform tile, host name, online dot, one best
  address, optional OS metadata, and a trailing chevron.
- The whole row opens host detail. A direct Terminal shortcut can be added only
  when its touch target and session count behavior match the iOS reference.

### Host detail

- Keep the host summary above a responsive action-card grid (144 dp target
  minimum width, 120 dp minimum height, wrapping text at larger font sizes).
- Actions: Terminal (SSH), Web Access (HTTP/HTTPS), Files (SFTP), Ping (Diagnostics).
- SSH settings is an accessible toolbar action, not a separate settings group.
- Web Access prefills the selected host and lets the user choose the URL/port and
  press Go. Port presets never trigger unsolicited navigation.

### SSH settings

- Show host information as read-only metadata.
- Editable fields are Port, Username, and authentication method, followed only
  by fields required for that method.
- Save is the primary action. When settings were opened as a prerequisite,
  successful save immediately continues to Terminal or Files.
- Passwords, private keys, and passphrases keep the existing Keystore-backed
  encrypted storage boundary.

### Terminal

- Open and connect immediately from a configured host.
- During dialing and SSH negotiation, show an immersive terminal-colored loading
  surface with endpoint and status. Do not show editable credentials.
- On recoverable failure, show Retry and SSH Settings actions.
- Connected layout: compact tab strip, terminal canvas, and an accessory row
  attached above the IME.
- The header shows detach/back, host status, add-tab when applicable, and one
  overflow action. New-window and destructive close actions do not compete
  with terminal content; closing a live tab or group requires confirmation.
- Multiple tabs use stable user-visible names. Tap selects; long-press opens
  rename and close actions without permanently spending tab width on close
  icons.
- Both accessory rows remain visible while input is expanded. The primary row
  contains session actions, Esc, Ctl, Alt, Tab, navigation, and paste. Compact
  windows use a compact navigation icon with direction feedback, hold-to-repeat, and
  tap-to-open discrete arrows; windows at least 600 dp wide show separate arrows.
  The second row pins Shift and bracketed-paste newline at the leading edge and
  keyboard/accessory hide at the trailing edge. Only symbols and F1–F12 scroll
  between those fixed controls. Modifiers invert their colors while selected
  and apply once across accessory, IME, and hardware input.
  Rows are 36 dp high, key labels use 12 sp medium system text, and icons use
  16 dp artwork. The compact navigation target is 34 × 36 dp without a ring;
  discrete arrow choices retain 48 dp height. Font scaling remains enabled.
- Terminal chrome follows the active terminal palette; controls use transparent
  or subtly tinted surfaces so content remains dominant.

### Files

- Files uses the same saved-profile gate and strict known-host policy as
  Terminal.
- The browser uses compact tappable breadcrumbs for every ancestor, keeps
  in-folder search collapsed until requested, and presents item actions in a
  native bottom sheet.
- Preview and editing are immersive. A dirty editor cannot be left without an
  explicit Save, Discard, or Cancel decision.
- Directory rows use folder/file semantics, monospaced paths, and native Android
  file-picker integration for import/export.

## Feature fidelity backlog

| Slice | iOS reference behavior | Android acceptance test |
| --- | --- | --- |
| Shell/Home | Compact host cards, platform/status identity, restrained grouping | API 28/36 light and dark; large font; TalkBack order |
| Tailnet path | Online-only host default; ten-round Ping route and latency detail | Direct, DERP, peer relay, timeout, retry cancellation |
| SSH continuation | Missing config asks once; saved config continues pending action | New host → Settings → Save → Terminal; configured host → Terminal directly |
| Terminal foundation | Full-screen session, readable type, tabs, accessory keys | Real Tailscale SSH; password; key; rotation; IME; hardware keyboard |
| Terminal polish | Themes, saved font, selection, URLs, scrollback, session detach | Five themes; copy/paste; `tmux`, `vim`, Unicode/CJK/emoji stress |
| Files | Native grouped browser/editor using shared SSH policy | Browse/edit, host-key mismatch, interrupted transfer, SAF import/export |
| Settings/legal | Grouped native settings and source/notices screens | Dark mode, backup exclusions, dependency notices, source tag link |

## Review rule

Every UI slice should be reviewed side by side with the corresponding iOS
screen and against Android accessibility/system conventions. “Same feel” means
matching hierarchy, density, color semantics, state transitions, and quality;
it does not mean reproducing an Apple-only control when Android has a stronger
native convention.

### Browser

- Use a ready-to-browse start page with URL input, IME Go, and host port presets.
- Keep page content above compact bottom controls: address/edit, back, forward,
  reload/stop, tab switcher, and a menu for start page, link copy/share, desktop mode, and close.
- Address editing uses a keyboard-aware sheet. WebView stays clipped beneath UI.
- Support WebView fullscreen video with an accessible exit and Android Back;
  release the custom view on disposal/background. TLS and authenticated proxy
  requirements remain unchanged.
- Default URL drafts and schemeless input to HTTP; preserve explicitly entered HTTPS.
  Host Web Access opens a new draft using the short MagicDNS node name and waits for Go.
- The tab sheet supports creating, selecting and closing tabs. Switching retains the
  live WebView and navigation history; inactive views pause. App navigation is hidden by default while viewing a page; Show/Hide app navigation
  sits below Close tab in the More menu and toggles it without leaving the page.
  Every More-menu item has a semantic leading icon. The tab switcher is a plain icon, without a
  notification badge; its accessible label and sheet expose the tab count. Switching app sections
  detaches the browser UI but retains its pages, DOM, history and scroll state.
  Backgrounding the app or replacing the proxy destroys views and requires Go
  before loading again. Tab metadata is memory-only; closing the last tab creates
  an uncounted landing-page draft. A fresh browser starts with no open tabs;
  count labels use localized singular/plural forms. Bookmarks/history persistence and downloads remain separate work.
- Settings places App language below Files under Misc: System default, English, Simplified Chinese,
  Traditional Chinese. Android 13+ shares the system app-language setting; older
  versions use DataStore and a localized UI context without restarting sessions.

### Launcher and launch screen

- Preserve the NovaScale browser-and-terminal mark. Android owns its vector
  rendition: light sky-blue background, translucent rounded browser pane,
  raised terminal pane, white/cyan edge highlights and restrained shadows.
- Use separate 108 dp adaptive foreground/background layers rather than a
  flattened square PNG. Keep the recognizable mark inside the central safe
  area; the launcher supplies its preferred mask. API 33+ includes a dedicated
  monochrome mark for themed icons.
- The launch screen uses only the transparent foreground on graphite `#0B0F14`,
  with matching system bars. Never display the opaque square launcher background
  here. Android 12+ receives the vector directly so the system can scale/mask it;
  older Android uses a centered 288 dp starting-window drawable. MainActivity
  switches to the ordinary app theme before creating its content.
- Native vector resources are the editable source; no macOS export or generated
  raster assets are required. The iOS Icon Composer files remain reference-only.


### System and keyboard insets

- MainActivity opts into edge-to-edge consistently across Android versions.
  The custom top bar consumes top/horizontal safe-drawing insets before its
  56 dp content height; Scaffold content consumes the provided padding so child
  keyboard insets do not double-count the app or system navigation areas.
- SSH settings applies IME padding outside its scrolling form. Focusing a
  password/passphrase must relocate the complete field above the keyboard;
  the Save action remains reachable by scrolling.


### Compact cards and main navigation

- Host action cards reserve the same two-line title slot and one-line subtitle
  slot, and fill their row height. Wrapped labels must not make an individual
  card taller than its neighbors.
- Main navigation uses a 64 dp content height (previously 80 dp), plus Android's
  navigation/gesture inset. Keep labels on one line and preserve touch targets.


### Settings menu alignment

- Every Settings menu row uses NovaActionRow, including language selection and
  the background-session switch. Keep the same horizontal padding, fixed icon
  slot, 20 dp symbol size, and text column across sections. Navigation rows end
  in a chevron; the background-session row retains its switch.
- Use semantic icons for proxy routing, touch gestures, background sessions and
  language. Allow the longer switch title/description to wrap without shifting
  its leading icon/text alignment. Status summaries and form inputs are distinct
  from menu rows.

### Tailnet details and versions

- Settings has one Tailnet details menu entry instead of inline status,
  control-server and logout rows. Details presents live status, local node name,
  DNS name, all tailnet addresses and its account. Values wrap and can be copied;
  an absent user profile is explicitly unavailable, never inferred from peers.
- Control server and logout/connect actions live inside Tailnet details and use
  the shared icon/menu layout. Back from Control server returns to details.
- About includes Version, showing app version/build from BuildConfig and the
  upstream Tailscale version derived at build time from the pinned Go module.

Home uses Hosts/Sessions inner tabs. Connection details live in the top-right toolbar, using a progress indicator while starting and a semantic status tint with an accessible connection label. Signed-out Hosts provides a login introduction. Live peer counts use “Online: N Nodes”; there is no manual Refresh button.

First-run onboarding uses a soft accent wash, large feature symbols, readable monospace headings, page indicators, and fixed bottom controls. Content scrolls independently on compact screens. Completion is stored in DataStore; authentication remains an explicit action.

Browser address editing places Clear above the field at the right. Clear produces an empty, focused field without changing the loaded page. More-menu and Settings leading symbols use the primary accent for contrast.

Onboarding icons now use a solid blue 80 dp tile, centered above the heading, with a white 40 dp symbol in both themes. The promotional footer is removed. Debug builds provide a replay action in More.

Host Web Access uses a bottom sheet with an editable URL, quick ports, and an explicit Open in browser action. The Home tailnet status toolbar button opens a details sheet so dismissing it preserves the current host pane.

Tailnet authentication replaces the setup form with a dedicated waiting view while sign-in completes. Browser sign-in offers Reopen sign-in browser; both methods offer Back to setup and a delayed troubleshooting hint. Controller-required device approval has its own heading and instructions. The setup form offers Browser sign-in and Auth key chips; key entry is masked, not saved, and cleared on submission.


## Final UI refinement (2026-11-11)

Primary actions use `NovaPrimaryButton`: the theme’s primary/onPrimary pair,
12 dp corners, and a minimum 48 dp touch target. Alternatives use
`NovaSecondaryButton`; delete and session-close confirmations use
`NovaDestructiveButton`, with a neutral outlined surface and red text. Never put
red text on a primary-color fill. Error foregrounds are `#B42329` in light mode
and `#FF8A8E` in dark mode so labels contrast with neutral cards and dialogs.
Navigation icons, list links and quiet dismissal controls retain their native
roles. Loading labels/icons inherit the same foreground color as their button.

Filter presets and custom groups share neutral rounded surfaces. Selection uses
primaryContainer/onPrimaryContainer plus a checkmark. The leading Hosts filter
entry stays in place. A filtered Hosts header omits the full control-server
subtitle, retains the active filter and compact profile/status control, and
returns to its normal presentation for All hosts. It never switches profiles.
The group sheet supports partial and expanded presentation, with a fixed heading
and scrolling content.

Add Host and Pro use a shared rounded modal sheet with a drag handle and a
large height bounded to 90% of the screen, leaving the background visible.
Add Host keeps Cancel at the left and Save at the right of a padded fixed footer;
only its form scrolls. Sheet dragging is disabled for this editor so edge scrolls
cannot pull the modal and rebound. Cancel, Back and backdrop dismissal remain.
Pro keeps its scrolling content above fixed footer actions and retains sheet dragging.
The keyboard can expand the host sheet within the remaining available space.

Settings uses stable feature colors: network green/teal, terminal blue,
appearance violet, Files amber, support gold, and informational/neutral About
icons. Light and dark roles are paired; status colors still indicate real state.

Browser starts with a compact address card, explicit Go, recent sites and
Favorites/History entries. Its address editor provides scheme selection
and local suggestions; typing never navigates. Favorite and history repositories
are profile-scoped, encrypted, and excluded from backup. History retains the most
recent 200 distinct addresses; favorites survive history clearing. No external
favicon lookup or suggestion service is used. Loaded favicons are bounded to
32 × 32 pixels per tab.

Live browsing keeps a compact address/navigation area, an explicit app-navigation
reveal control, tabs and Find in Page. Existing WebView/proxy lifecycle boundaries
remain authoritative. Returning after a foreground proxy owner ends requires an
explicit Go; returning to a still-live foreground page retains its DOM and history.
Downloads, certificate exceptions and new upstream proxies are outside this UI
release.

The browser always uses the application's shared authenticated local proxy.
There is no separate browser proxy setting. If the user disables Local proxy in
Settings, entering Browser explains the requirement and offers an explicit
Turn on local proxy action. Cancelling leaves the setting off. Starting, failed,
disconnected and unsupported states have specific feedback; Go is enabled only
after the active profile's proxy and WebView routing are ready.

Cursor-shape selectors use onSurface or onPrimaryContainer with a clear selected
border/checkmark. Their shape samples must stay legible in either app theme;
the full terminal preview retains the selected terminal theme's actual colors.

Herdr's accessory row follows the soft keyboard's visibility and terminal focus.
It has no independent expanded/collapsed state. A new terminal presentation
requests input once, respecting hardware keyboards; later background returns or
native navigation do not undo a deliberate dismissal. Back and Hide Keyboard
hide both, clear one-shot modifiers and return the full area to the terminal.
The top keyboard action reopens input when needed.

Onboarding introduces saved tailnets and switching (one connected at a time),
traditional SSH/SFTP hosts, groups/pins, Herdr attachments and native controls,
and the shared-proxy browser. The Herdr page follows the current debug-only host
action gate until that feature's release gate is cleared. Copy is localized in
English, Simplified Chinese and Traditional Chinese.

Enabled action text contrast after this refinement: primary light 6.30:1,
primary dark 9.07:1; selected row light 8.57:1, dark 9.57:1; destructive text on
the neutral dialog surface light 5.41:1, dark 6.15:1. Ratios use the actual paired
theme colors.
