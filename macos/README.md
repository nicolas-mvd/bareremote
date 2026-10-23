# BareRemote for macOS

A small native macOS remote for Android TV. Version **0.3.1 prototype (build 12)**, macOS
**13 Ventura or newer**. SwiftUI/AppKit, Network, Security and CryptoKit; no
third-party runtime dependencies, Java runtime, accounts, telemetry, polling,
login item or background service. Includes a menu bar remote and a movable
desktop remote, native TV text entry and manual app updates.

## Quick access

Launch BareRemote from Applications or Spotlight. Its **TV icon stays in the
menu bar** when the remote window closes. Click it for navigation, volume,
power, play/pause, Sync and picture mode. **Open Remote** shows the larger window
with keyboard navigation, TV text entry and hold-to-repeat. **TV Setup** manages the same pairing.
Choose **Quit BareRemote** to remove the menu bar icon and stop the app.
The popover sizes itself before opening, using the screen containing its TV icon.
Its viewport stays fixed as status text changes; shorter displays can scroll.

The **desktop remote** opens with BareRemote and stays above the desktop, behind
regular app windows. Drag its header to move it. Its close button hides it;
choose **Show Desktop Remote** from the menu bar to bring it back. BareRemote
remembers its position and whether it was hidden when you next launch the app.
It provides navigation, volume, power, play/pause, inputs, Sync and Day/Night.
Desktop controls are single presses; use the full remote to hold keys. Its
keyboard icon opens the full typing window. This desktop remote is part of the
running app and does not need to appear in macOS's Edit Widgets gallery.

An idle desktop remote never opens a TV connection or polls. Its buttons start
the existing paired session on demand, and the connection expires 60 seconds
after the latest action when neither the full remote nor menu popover is open.
Open those surfaces to see a fresh TV-confirmed picture snapshot. Closing the
popup without sending a command releases its connection after a short delivery
allowance. No login item is installed.

## Build and open

With Xcode or Apple's Command Line Tools installed:

```sh
./macos/build.sh
open build/macos/BareRemote.app
```

The script produces `build/macos/BareRemote.app` and
`build/macos/BareRemote-macOS.zip`. Copy the app to Applications if desired.
The default build targets the current Mac; use `./macos/build.sh --universal`
to include Apple Silicon and Intel. The deployment target is macOS 13.

The separate WidgetKit extension in `WidgetExtension/` remains experimental:
macOS recognized its registration but did not list the local development build
in Edit Widgets. It is excluded from the normal app bundle. Developers can opt
in with `BAREREMOTE_INCLUDE_WIDGETKIT=1 ./macos/build.sh`; this requires macOS 14+
for the extension and does not replace the built-in desktop remote.

**[Download the 0.3.1 prototype](https://github.com/nicolas-mvd/bareremote/releases/download/macos-v0.3.1-prototype/BareRemote-macOS.zip)** · [Release notes and checksum](https://github.com/nicolas-mvd/bareremote/releases/tag/macos-v0.3.1-prototype).

The build uses the explicitly selected `BAREREMOTE_SIGN_IDENTITY`, or a locally
saved selection in `.keys/macos-signing-identity`. Without either it uses an ad
hoc signature and prints a warning. Apple recommends an Apple-issued signing
identity for reliable macOS Local Network permission tracking. Select an existing
Apple Development identity for local use; no signing key is included in the
repository or app. The current prototype uses Apple Development signing, is not
notarized and is not a Developer ID release. A Developer ID Application identity
is not available for this release. See [Apple's local-network guidance](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy).

Builds stage and verify a complete new bundle before replacing the old bundle,
so rebuilding never overwrites a running signed executable in place. Quit and
reopen an already running copy to use the rebuilt version.

## Pair your TV

1. Keep the Mac and TV on the same local network and turn the TV on.
2. Open **TV Setup** (the sliders button or **⌘,**) and choose **Scan for TVs**,
   or enter the TV's numeric IPv4 address.
3. Choose **Pair with TV** and enter the six hexadecimal characters shown on
   the TV you intend to control.

Allow local network access when macOS requests it. Discovery searches Bonjour
for `_androidtvremote2._tcp.` for at most 12 seconds and lists IPv4 results on
port 6466. Browsing and selecting a result never pair or send a control.
Manual address entry remains available if multicast discovery is blocked.
The TV must support Android TV Remote Service v2; no ADB or developer mode is
needed. Pair this Mac separately from every phone.

If pairing says **Local Network access is blocked**, choose **Open Local Network
Settings** and enable BareRemote under Privacy & Security → Local Network, then
explicitly try Pair or Reconnect again. Discovery results alone do not establish
that macOS permits a direct connection. If the toggle is already enabled after
changing an ad hoc build, quit the app and reopen the consistently signed build;
macOS may request access for its new signing identity. Existing TV pairing and
the Mac's Keychain identity are retained.

Version 0.1.1 keeps an initial Network-framework waiting state alive within the
existing connection deadline, allowing a pending permission decision to complete.
It reports an explicit denial only when the path identifies Local Network
policy as the reason; other network errors remain distinct. A lost established
session still closes and never reconnects or replays keys automatically.

The installation generates its own RSA2048 identity with a unique certificate
subject in macOS Keychain. Its private key is nonextractable. The saved TV
address and exact certificate pin live in
`~/Library/Application Support/BareRemote/tv.json`, atomically written with
owner-only permissions. No identity, private key or paired TV is bundled.

Opening a paired app reconnects using that identity and the exact saved TV
certificate. **Sync (⌘R)** explicitly replaces a connection and refreshes picture
state. If the TV's address changes, select it in Setup or enter its new address,
then choose **Reconnect**. The new address is saved only after authentication
and remote readiness. Failure or cancellation preserves the previous pairing.
A different or reset TV requires **Pair with a TV code**.

**Forget TV** removes this Mac's saved connection. It keeps the Mac's Keychain
identity and does not remove trust from the TV. Normal app rebuilds and updates
retain the pairing.

## Controls

The circular D-pad, OK, Back, Home, inputs, volume, mute, power and play/pause
mirror the Android app. Hold a direction or volume button to repeat: once
immediately, then after 400 ms at up to eight presses per second. Drag away,
release, switch windows or encounter a connection error to stop. Hold OK for
a long press. **Remote → Keep on Top** keeps the window handy.

In normal remote mode, keyboard navigation works only while the remote window
is active:

| Key | Control |
| --- | --- |
| Arrow keys | D-pad, hold to repeat |
| Return / keypad Enter | OK, hold for long press |
| Escape | Back |
| H | Home |
| − / + | Volume down / up, hold to repeat |
| M | Mute |
| Space | Play / Pause |
| ⌘R | Sync |
| ⌘, | TV Setup |
| ⌘K | Keyboard mode |

Power can wake the TV only while its remote service remains reachable. The
gamepad button requests BareHome's native input picker (`barehome://inputs`);
it requires BareHome 1.1+ and compatible TV input support. It does not choose a
particular HDMI socket. There are no global keyboard hooks.

The green strip's mode icon is the TV's last authenticated picture snapshot:
sun for Day, moon for Night, question mark for unknown/custom/unavailable. Tap
to let the TV toggle its own saved preset. This requires BareHome 1.2+, its
supported hardware and existing picture permission. Opening a connection and
pressing Sync query a snapshot; an idle window does not poll.

Picture confirmation uses the existing pinned connection and one temporary UDP
reply socket, bounded to four seconds. The client validates the source IPv4
address, request identity and HMAC before updating its icon. A missing reply
never automatically repeats a toggle. Normal keys remain independent. See
[`../docs/PICTURE_STATE.md`](../docs/PICTURE_STATE.md).

Connections exist only while the app is running. Closing the last window leaves
the menu bar available; invisible sessions expire as described above. **Quit**
or **Disconnect** closes the session. Lost
connections require Sync; failed or uncertain commands are never replayed.
Remote Service provides no per-key execution acknowledgment.

## Type on your TV

Select a search box or another text field on the TV. A keyboard-show request
from the paired TV switches an open remote window or menu bar remote to
**Type on your TV**. App information alone does not trigger the switch. You can
also click the **keyboard icon** on either remote, or choose **Remote → Keyboard
Mode (⌘K)**. The input waits until the TV reports a focused editor with matching
session metadata; this does not force a TV app to expose text entry.

Type or paste using the Mac's native input method. Marked/composing text stays
local until committed, then the committed chunk goes to the TV and clears from
the input pad. Use the cursor, Delete and Enter buttons, or their ordinary
keyboard keys. **Back to remote** or **Esc** returns to the normal remote.
Repeated show messages for the same field respect that choice; a new field
can switch modes again. A lost field returns to the remote and clears local
composition. D-pad keyboard shortcuts do not capture typing while in this mode.

On the desktop remote, the **keyboard icon** or **Type on TV** opens the typing
window. A TV keyboard request updates its small panel without activating another
Mac window on its own. The desktop panel itself never captures keyboard input.

TV-provided text is never copied into the input pad, saved or logged. There is
no local mirror or undo history for text already committed. Focus changes,
closing the last active input surface, Sync and disconnect invalidate queued
input; it is never transferred to another editor or retried after uncertain delivery. Paste
is limited to 2,048 UTF-16 units and 4 KiB of UTF-8 per commit. Remote Service
does not acknowledge that the TV app applied a commit. Some TV apps do not
support remote text entry; the ordinary remote remains available. No voice
input or global keyboard hook is installed. See
[keyboard protocol and privacy details](../docs/PROTOCOL.md#native-keyboard-input).

## Updates

Choose **BareRemote → Check for Updates…**, or **Check for Updates** in TV Setup.
Checking is manual. When a newer build is available, choose **Download**, then
**Install & Reopen** after verification. The updater checks the ZIP's byte count
and SHA-256, app identity and version, and the installed app's signing requirement
and Apple Team ID. It stages the new app beside the existing one, quits and
replaces it, then reopens it. A replacement failure restores the previous app.

Use a writable local Applications folder; no administrator prompt or permission
change is performed. Pairing remains in Keychain and Application Support.
There are no background checks or automatic downloads. This signature continuity
check does not notarize the prototype. See [macOS updater details and release
instructions](../docs/MACOS_UPDATES.md).

## Validation

```sh
cd macos
swift test
python3 Tests/test_loopback.py
```

The Swift suite checks bounded protobuf parsing, independent pairing hash
vectors, actual Keychain identity reuse and nonextractability, atomic saved-TV
storage, and authenticated picture messages and exact links. The loopback
harness starts the existing Java simulated TV on temporary local ports and
exercises the real macOS mutual-TLS transport. It needs Python 3 and a JDK;
neither is required by the app.

On 2026-10-22, version 0.1.0 passed **18 native unit tests and 7 TLS integration
tests**. Version **0.1.1 passes 29 native unit tests and the same 7 TLS integration
tests**, including 11 new connection-state and permission regression tests.
Version **0.2.0 passes 34 unit tests and 7 loopback TLS integration tests**;
the additional tests cover the widget's restricted URL vocabulary, malformed
commands and intentional repeated taps. Both the app and WidgetKit extension
were built as universal arm64/x86_64 bundles with the selected development
identity; the experimental WidgetKit extension is excluded from the default
installation because gallery discovery remains unresolved. The installed native
menu bar popover was rendered and checked while
the full remote window was closed; reopening the app showed the popover and
reused the saved pairing and authenticated Day snapshot.
The built-in desktop remote was rendered at 340 × 270 points, hidden with its
close button and restored through **Show Desktop Remote** in the menu bar. The
installed app had no network sockets while showing only the idle desktop remote.
No physical-TV keys or picture changes were sent during these checks.
Build 10 also verified the full menu bar remote after bounding its viewport and
measuring before presentation. Its 300 × 577 point content appeared directly
below the status icon on the active display, with the header and Quit visible.
These covered macOS Keychain and mutual-TLS loopback pairing,
persistent identity reuse, pinned reconnection, changed-certificate rejection,
single key delivery, bounded long presses, app links, pings, malformed framing,
deadlines, cancellation and disconnect notification. The universal app bundle
was built for arm64 and x86_64; only arm64 was executed. Native UI checks on the
loopback TV covered correcting a mistyped code, Bonjour discovery and selection,
pinned reconnection, all key buttons, pointer/keyboard input, hold-to-repeat,
long OK and Sync. Discovery also listed the local physical TV, but no pairing
requests or controls were sent to it. The existing Android suite passed all
967 assertions.

Real loopback UDP tests also verified a forged reply followed by an authenticated
reply, rejection of an authentic reply from the wrong source, a bounded timeout,
and cancellation within one 100 ms polling slice. The production picture client
still rejects public and loopback callback addresses.

The Android regression suite remains available from the repository root:

```sh
python3 -m unittest discover -s tests -p 'test_*.py'
```

After switching 0.1.1 to the owner's selected Apple Development signature, the
owner paired the native macOS app with the physical **CSV4K TV** on 2026-10-22.
The running 0.1.1 app reached **Connected** and displayed **Day confirmed**. This
verifies that Mac/TV pairing, pinned remote readiness and the authenticated
picture-state reply. No physical-TV key, power, input-picker or picture-toggle
command was sent by the agent during this check; those controls and other TV
models remain unverified on macOS.

Version **0.3.0** passes **71 native tests**: 63 in the normal suite (including
5 actual AppKit text-client tests and 9 updater tests with signed fixtures), plus
8 actual loopback TLS tests run by the harness. The normal suite skips those
8 integration tests until the harness provides its isolated server.

The installed universal 0.3.0 build 11 was checked against the loopback TV using
the actual native interface: manual keyboard opening without a field, automatic
opening on a new field, repeated-show suppression after returning to remote,
Unicode paste delivered once, Delete, Enter, focus loss, menu bar typing and the
desktop shortcut. Both full-window and menu bar layouts were rendered.

Version **0.3.0** adds native text commits and the manual updater. Keyboard tests
cover Unicode and UTF-16 positions, native marked-text composition, one-time
commit delivery, input teardown, field loss, manual switching, same-field show
suppression, stale target rejection, and unsupported-service fallback. The
independent IME fixture exercises the actual Keychain mutual-TLS transport with
a simulated TV. These checks do not establish keyboard compatibility with the
physical CSV4K TV. Updater tests exercise bounded metadata and downloads,
archive validation, signed fixtures, staging, replacement and rollback; the
[updater test notes](../docs/MACOS_UPDATES.md#tests) distinguish those fixtures
from an update of the installed app.

The real installed-app updater was then exercised with an isolated lower-version
fixture of the production updater code: it checked the published GitHub feed,
downloaded and verified the public 0.3.0 ZIP, installed and reopened build 11 in
Applications, retained the original pairing byte for byte, and reported up to
date on a new check. The installed executable matched the published build and
strict signature verification passed. See the [live update validation](../docs/MACOS_UPDATES.md#tests).

Version **0.3.1** fixes the same directional IME protocol mistake as Android
0.6.2: TV keyboard visibility was mistaken for input counters, and insertion
coordinates were based on the typed string rather than the actual TV cursor.
The corrected suite passes 78 tests across normal, native AppKit and actual
loopback TLS tests. The fixtures now follow Remote Service 7.00.999235495’s
actual server/client message directions; see the [protocol notes](../docs/PROTOCOL.md#native-keyboard-input).

Keyboard visibility can hide and reopen the same editor without defeating the
user’s manual Remote choice. That choice stays attached to the editor across
cursor and content updates. Vertical arrows stay local to the native commit pad.

The final public universal 0.3.1/build 12 was installed in
`/Applications/BareRemote.app` and checked against the physical CSV4K TV on
2026-10-23 using the existing Mac pairing. SmartTube Beta triggered automatic
keyboard mode, and a native AppKit commit appeared exactly once in its actual
search field. Guarded cleanup restored the original empty query, verified both
on the TV and through authenticated IME cursor/length metadata. Only arm64 was
executed. The TV’s changed address was recovered through normal discovery and
pinned reconnect; it did not require a new Mac pairing.
