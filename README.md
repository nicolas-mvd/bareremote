# BareRemote

A small Android TV remote for your phone. Open it, press a button. Or give its widget an entire home-screen page.

**macOS:** A native desktop companion is now available in [`macos/`](macos/README.md).
Build it with `./macos/build.sh` and open `build/macos/BareRemote.app`. It uses its
own Keychain pairing identity and supports the same TV controls, local discovery,
TV text entry, keyboard navigation, hold-to-repeat, and BareHome picture
confirmation, plus a menu bar remote, movable desktop remote and manual
**Check for Updates**. Version 0.3.1 (build 12) requires macOS 13+.
The desktop prototype has separate validation and versioning from
the Android releases below.

**[Download macOS 0.3.1](https://github.com/nicolas-mvd/bareremote/releases/download/macos-v0.3.1-prototype/BareRemote-macOS.zip)** · [Mac release notes and checksum](https://github.com/nicolas-mvd/bareremote/releases/tag/macos-v0.3.1-prototype). Apple Development-signed prototype; not notarized. See the [installation and signing notes](macos/README.md#build-and-open).

**Prototype, version 0.6.2 (code 13).** Android 8+; targets Android 15. This is separate from the [BareHome TV launcher](https://github.com/nicolas-mvd/barehome) and does not require BareHome. Compatibility checks are listed below; releases remain prereleases.

**[Download BareRemote.apk](https://github.com/nicolas-mvd/bareremote/releases/download/v0.6.2-prototype/BareRemote.apk)** · [Release notes and checksum](https://github.com/nicolas-mvd/bareremote/releases/tag/v0.6.2-prototype)

[App preview](docs/screenshots/app.png) · [Full-page widget preview](docs/screenshots/widget.png). These show version 0.3.0 on the Android emulator, paired with a local simulated TV.

## Use it

**Upgrading?** Install over the existing app. Updating from 0.4.1 or newer keeps your pairing and widgets. **Do not uninstall first.** Version 0.4.1 fixes multiple-phone pairing: when upgrading from 0.4.0 or earlier, open Setup and **pair each phone once more** using the TV code. Your saved TV address and widget remain in place. Later updates retain this new pairing.

1. Open the APK download link above on each phone, download it, and open it. If Android asks, allow that browser or file manager to **install unknown apps**.
2. Put the phone and TV on the same local network. Open BareRemote → **Setup** → **Scan for TVs**, select your TV, then tap **Pair**. You can also enter its IPv4 address from the TV’s network settings.
3. Enter the six-character code shown on the TV. **Pair each phone separately.** After pairing, opening the app takes you directly to the remote.

The TV must provide Android TV Remote Service v2. Pairing uses the TV’s normal remote service; no ADB or developer mode is needed for everyday use. If the TV’s address changes, scan and select it again to reconnect using your saved pairing. **Forget TV** removes this phone’s saved TV connection locally; it does not remove the phone from the TV’s trusted-device list.

## Find your TV

**Scan for TVs** searches the local network for up to 12 seconds and lists the
names and IPv4 addresses of TVs advertising Android TV Remote Service. When
already paired, select your TV to reconnect using the saved pairing, even if its
IP address changed. BareRemote verifies the TV's saved certificate before saving
the new address. No pairing code is needed, and the existing pairing and saved
address survive a failed or cancelled check. You can also enter the new IPv4
address in Setup and tap **Reconnect**. The widget uses the new address after a
successful check.

For a first pairing, select a result and tap **Pair**. To pair a different or reset
TV, deliberately choose **Pair with a TV code** in Setup. Selecting a scan result
never automatically falls back to requesting a new code. Scanning and checking a
saved TV do not send remote keys. You can stop early, select a result while
scanning, or scan again.

Turn the TV on before scanning. Guest Wi-Fi, client isolation, separate subnets,
or networks that block multicast can hide TVs. Some TV services do not advertise
themselves, and IPv6-only TVs are not listed. In these cases, enter the TV’s IPv4
address manually. A discovered name is an unauthenticated network label. Saved-TV
reconnection verifies the existing certificate; a new pairing requires confirming
the code on the TV you intend to pair. Scanning stops when its dialog closes or
the app leaves the screen. There are no background scans or subnet port sweeps.

Controls: a circular D-pad with OK, Back, Home, Input, volume down/up, mute, and power. Hold a **direction or volume button** in the app to repeat presses: the first press sends immediately, then repetition starts after 400 ms, up to roughly eight presses per second. Releasing, dragging off the pad or volume button, leaving the app, or a connection error stops repetition. A held button never reconnects automatically after a failure. Hold **OK** for a long press, including BareHome’s mode editor. The PlayStation icon asks BareHome 1.1 or newer to open the TV’s native source/input menu, including its HDMI and CEC device names; it does not assume which HDMI socket holds a PS5. This requires a TV with the native `com.android.tv.action.VIEW_INPUTS` action. The standard Android input key does nothing on our tested TV, so this button uses the native picker bridge. Play/Pause and Add widget are available under the gear (Setup). Button behavior depends on the TV and current app. Power can wake a TV only while its remote service remains reachable over the network.

If the connection gets stuck, tap the **Sync icon** in the centered green strip of the app or widget. It opens a fresh connection to the saved TV using the existing pairing; no TV code is needed. Sync does not search for TVs or discover a changed IP address.

## Type on your TV

Version 0.6.2 corrects TV keyboard compatibility (keyboard mode was introduced in 0.6.0). Select a search box or another text
field on the TV. When Android TV Remote Service asks to show its keyboard,
the open BareRemote app switches automatically and opens your phone's keyboard.
You can also tap the **keyboard icon** between the down arrow and Home to open
the mode manually. The TV must still provide a focused text field; opening this mode
does not select a field or force an unsupported TV app to accept text.

Type or paste to send committed text to the TV. Unicode and native input-method
composition are supported, with cursor left/right, Delete and Enter controls.
Tap **Remote**, or use the phone's Back button, to return to the D-pad. Repeated
show messages for the same TV field respect that choice; selecting a new field
can open keyboard mode again. If the TV stops reporting an active field, the
app returns to the remote.

The local input is a temporary commit pad: composing text remains local until
committed, then it clears. Existing TV text is never mirrored, saved or logged,
and there is no local edit or undo history for text already sent. Input is bound
to the current paired connection and field; a focus change or disconnect drops
stale input instead of sending it later. A failed or uncertain commit is never
retried. The TV service does not confirm that its app applied a commit.

Support depends on the TV's remote service and the current TV app. Use the
normal remote when a field does not expose remote text input. Text entry is
available in the full Android app; the home-screen widget opens that app.
The Mac companion provides the same mode in its window and menu bar, with a
desktop shortcut to open the typing window. See [macOS keyboard controls](macos/README.md#type-on-your-tv)
and the [protocol and privacy details](docs/PROTOCOL.md#native-keyboard-input).

## Multiple phones

Each phone has its own permanent pairing identity, so both can stay paired and
control the TV at the same time. Pair each phone separately; neither needs to
forget the TV when the other connects. Their commands share the same TV: pressing
different directions at the same time still sends both commands to it.

Version 0.4.0 and earlier used the same certificate name on every phone. The
remote service on our CSV4K TV replaces an older pairing with the same name,
even when the keys are different. Version 0.4.1 generates a random name once per
installation and retains it in Android Keystore. Both phones need one explicit
pairing after this upgrade. Sync cannot repair an old pairing the TV has replaced.
No TV reset, developer mode, or BareHome update is needed.

## Updates

Open the gear (Setup) → **Check for updates**. Download a newer release, then
confirm installation with Android. Android may first ask you to allow BareRemote
to install unknown apps. Widget settings survive an in-place update. Pairing also
survives normal updates; the one-time 0.4.1 identity migration above is an exception.
There are no automatic phone checks or background downloads. If you already have
0.4.0, use its updater to install this version. Older versions need one manual APK
installation to gain the updater.
See [update checks, verification and release instructions](docs/UPDATES.md).

On macOS, choose **BareRemote → Check for Updates…** or the same option in
**TV Setup**, then **Download → Install & Reopen**. The Mac updater verifies the
archive, version and signing identity before replacing the app. See
[macOS update checks and installation](docs/MACOS_UPDATES.md).

## Day/Night from the phone

One mode icon shows the TV's **last confirmed** preset: **sun for Day**, **moon for Night**. Tap it to toggle. The TV decides which mode comes next from its own saved state, so either phone can use the button. A **question mark** means the mode is unknown, unavailable, or the actual picture levels differ from the selected preset.

This requires **[BareHome 1.2 or newer](https://github.com/nicolas-mvd/barehome/releases/tag/v1.3)** on the TV, its supported picture-control hardware, and its existing one-time picture permission. It uses your saved Backlight/Picture Brightness presets; edit those by holding the Day/Night button in the launcher.

Opening the remote, tapping Sync, or changing mode requests a fresh TV snapshot. An idle widget keeps the last confirmed state. If the other phone or the TV remote changes the picture, tap Sync to refresh; there is no continuous monitoring.

The request uses the existing paired TV connection. The phone briefly listens for an authenticated local reply, and BareHome sends one reply before stopping. No permanent TV listener or service is added. A missing reply never triggers an automatic second toggle. Normal remote buttons work independently of picture-state support. See [how confirmation works and its limits](docs/PICTURE_STATE.md).

The hardware, permission, automatic-lighting and native Picture-mode guards remain unchanged. Confirmed settings are not a measurement of emitted light. The windowless TV entry point can briefly change activity lifecycle state; uninterrupted playback across all apps is not guaranteed.

## Full-page widget

Tap **Add widget** in the app and accept your launcher’s prompt. Alternatively, touch and hold an empty home-screen area → **Widgets** → **BareRemote**.

The widget requests a 4 × 5 cell layout and resizes in both directions. Put it on an empty page, touch and hold it, and drag the resize handles to fill the page. Available sizes depend on your launcher. The **BareRemote logo beside mute opens the full remote**, including hold-to-repeat controls. The gear also opens the app; Sync reconnects and checks picture state; its control buttons send commands directly. Pair once in the app first.

The first tap opens a connection. Further taps reuse it for faster navigation, and each tap resets a **60-second idle timer**. A temporary Android foreground service keeps this session alive, with a notification containing **Stop**. It ends automatically after a minute without taps. The app can keep its own connection while open.

On Android 13+, BareRemote asks once for notification permission after opening the paired app. This permission is optional: the widget still works if you decline, and Android still shows the foreground service in its Active apps/Task Manager view.

The widget uses icons only, with no title or connection-status text row. TalkBack still announces control names and connection status. The gear opens the app for setup or errors. A brief TV startup wait remains, but the delay after sending does not hold up later commands. Hold-to-repeat and long-OK are app-only because home-screen launchers reserve widget long presses for moving and resizing.

## Build

Requires JDK 17+, Android SDK platform **35**, build-tools **35.0.0**, `zip`, and `openssl` for the first signing-key creation. No Gradle, Compose, WebView, or third-party runtime libraries; the app uses native Java views and Android’s networking and widget APIs.

```sh
export BAREREMOTE_SDK=/path/to/android-sdk
./build.sh
```

`ANDROID_HOME` also works. Output: `build/BareRemote.apk`. The first build creates an APK signing key under `.keys/`. Keep that directory private and backed up; use the same key to build installable updates. Each installed phone creates its own separate pairing identity in Android Keystore.

Run the protocol tests with Python 3 and a JDK:

```sh
python3 -m unittest discover -s tests -p 'test_*.py'
```

See [protocol notes and primary implementation references](docs/PROTOCOL.md) for framing, pairing, certificate pinning, deadlines, and the optional hardware test harness.

## Prototype limits and validation

One saved TV per installation, IPv4 discovery or manual entry, and no voice
input. Text entry requires a compatible TV service and focused editor; it does
not read back or replace a whole TV field. There are no accounts, scheduled
background polling, or automatic startup. Manual update checks and downloads
contact GitHub; remote commands and discovery remain local. The widget session
lasts only until its idle timer expires or you stop it; it does not run
indefinitely. Behavior on other manufacturers remains unverified.

The 0.2.0 suites passed **156 protocol assertions** (including loopback mutual TLS) and **55 engine assertions** covering shared sessions, rapid command queues, Sync, deadlines, and cancellation across app/widget lifecycle changes.

The original 0.1.0 checks used the actual APK on an Android 15 emulator with a simulated TV: all 12 app and widget buttons, app long-OK, saved pairing, widget pinning and full-page resizing, widget use after app-process termination, offline errors, and rejection of a changed TV certificate. Widget commands kept the launcher visible; returning home and reopening the app did not crash.

Version 0.1.1 controlled a **physical CSV4K / KTC_MOTOROLA_4K_SA_21V2 TV running Android 11**, using the signed APK on an Android 15 emulator. App and widget navigation worked, including a widget command after confirming the app process had stopped.

The user also confirmed that the **Samsung Galaxy S24 Ultra** widget works with 0.1.1; app controls already worked with 0.1.0. No other physical phones or TV models have been tested. Version **0.1.2** was checked on an Android 15 emulator with the loopback TV: Sync reused the saved pairing after Wi-Fi was switched off and on; four rapid widget keys arrived on one session within 103 ms and each applied once; a tap after 30 seconds reused that session and reset its timer; the connection and foreground service stopped after 60 seconds of inactivity. The notification’s Stop action also closed the session. A widget tap started the service and delivered a key after the app process was stopped, with notification permission denied. These are emulator/mock results, not measured Samsung input latency; confirmation of 0.1.2 on physical phones and the TV is pending.

Version **0.2.0** adds the circular reference-style layout to the app and widget, with Sync, explicit sun/moon presets, and a gamepad source button. On the same physical CSV4K TV, the signed APK running on an Android 15 emulator successfully requested Night (Backlight 5 / Picture Brightness 20) and Day (60 / 50) from both app and widget. The owner confirmed a clear visible change during a repeated app test. The same SmartTube playback activity resumed after the initial mode requests; uninterrupted playback in all apps is not established. Both source buttons opened the TV’s native Inputs list, including PlayStation 5. No source was selected during this test. Final settings were restored to 60 / 50, Movie mode 9 unchanged.

That TV check used BareHome **1.1-personal** (code 9), with the owner’s existing app ordering; the public 1.1 APK contains the same bridge and picture controller with default alphabetical app ordering. Version 0.2.0 has not yet been checked on the physical Samsung phone.

Layout checks on the Android 15 emulator covered the 320dp-wide phone layout, all 11 visible key controls in both app and widget, and app long-OK. A 33-control widget sequence using 50 ms presses with 200 ms gaps arrived in order and applied exactly once per press. The connection and service stopped after 60 seconds idle; the next tap reconnected and delivered once. Widget status uses partial updates after one second without a new status, so status rendering does not rebuild touch targets during a burst; this does not delay commands. The foreground notification is created once per session.

Very fast synthetic widget bursts with almost no gap occasionally missed a click before BareRemote received it; every received command was delivered once. The cause of that launcher-side test result remains unresolved. Rapid tapping with the new layout still needs checking on physical phones.

Version **0.3.0** adds the PlayStation source icon, centered Sync + single mode
strip, and an icon-only widget. Its 304 host assertions pass (156 protocol,
108 engine, 40 callback). Installed Android 15 emulator checks against a local
simulated TV confirmed app and widget Day → Night → Day, Sync, and all 11 key
controls plus source once in order. This exercises the actual signed APK and
Android Keystore connection, with temporary emulator UDP routing for replies.

The matching **BareHome 1.2** was subsequently installed in place on the same
CSV4K (`1.2-personal`, code 10). Two authenticated state queries returned Day
without changing picture levels. The owner then confirmed on their phone that
Sync showed the sun and tapping switched the TV to Night with the moon icon.
This verifies that particular phone/TV path; other models remain untested.

The 0.3 app also fits the 320dp phone layout with all controls visible. Widget
controls have at least 48dp targets, with no visible text row. The installed
padding update was rechecked for confirmed toggling and a normal key. The widget
connection stopped after 60 seconds idle, and the next tap reconnected once.
The published APK matches the installed, tested APK byte-for-byte.

Version **0.4.0** adds the manual update option in Setup. All four host suites
pass, including 80 updater assertions (384 total assertions across the suites).
A lower-version fixture using the production updater downloaded the published
0.4 APK from GitHub and completed Android's installer on the Android 15 emulator.
The installed release reported up to date and its update screen fit a 320dp-wide
phone layout. The phone updater has not yet been tested on a physical Samsung.

Version **0.4.1** fixes pairing on multiple phones. Five host suites pass **487
assertions**, including same-subject trust replacement, simultaneous mutual-TLS
clients, reconnection, pin rejection, and migration failure/cancellation.
Two separate Android 15 emulator installations reproduced the 0.4.0 bug using
their actual Android Keystore keys, then upgraded in place to 0.4.1. Both retained
the TV address and displayed the one-time pairing prompt. Each received a distinct
persistent identity after pairing. Both sent controls on simultaneous connections;
Sync and a process restart reused the first identity while the second stayed
connected. This is emulator evidence against a local simulated TV; physical-phone
confirmation of 0.4.1 is pending.

The published **0.4.1** APK was also tested with **two separate Android 15
emulators simultaneously controlling the physical CSV4K /
KTC_MOTOROLA_4K_SA_21V2 TV (Android 11, Android TV Remote Service
7.00.999235495)** on 2026-10-17. Both stayed connected and independently moved
the launcher selection. Syncing the first remote preserved the second connection;
restarting the second app preserved the first. Both controlled the TV afterward
without another pairing code. The TV's per-client input counters and ping replies
confirmed the separate live connections. No apps were launched or picture levels
changed, and the test sessions were closed afterward. This tests the actual TV
with emulator clients; confirmation on two physical phones is still pending.

Version **0.4.2** adds app hold-to-repeat for directions and volume,
plus a widget button to open the app. Six host suites pass **605 assertions**,
including release/gesture cancellation, bounded repeat scheduling, stale callbacks,
foreground ownership, and stopping across Sync or connection failure. The signed
APK on an Android 15 emulator paired with the loopback simulated TV delivered
10–11 presses per 1.5-second hold for each of the six repeatable controls, stopped
after release, delivered a short tap once, and preserved OK’s long press. A native
Android widget host at 288 × 476 dp verified widget rendering and that its new
app button launches the remote. The emulator launcher rejected the existing
full-page minimum size, so this does not validate launcher pinning. These
are emulator/mock results; 0.4.2 has not been tested on a physical phone or TV.

Version **0.5.0** adds explicit local-network TV discovery in Setup. Seven host
suites pass **819 assertions**, including 214 discovery assertions covering
IPv4/service validation, Android's resolved service-type variants, serial
resolution, bounded results, device loss, cancellation, timeouts and stale
callbacks. The signed APK on an Android 15 emulator discovered a locally
advertised simulated TV and the physical CSV4K TV over mDNS. Selecting the
simulated TV filled its address; Stop, Scan again, 12-second completion and
leaving the app released the scan's multicast lock. The discovery dialog fits a
320 × 640 dp screen. The discovered simulated TV completed pairing using the
installed app's Android Keystore identity, applied one navigation key exactly
once, and reconnected after an app-process restart without another code.
Discovery did not pair with or send controls to the physical TV. Scanning on a
physical phone remains unverified. The published APK matches the installed,
tested APK byte-for-byte.

Version **0.5.1** lets an already paired phone scan and reconnect to the same TV
at a changed IPv4 address without another TV code. Seven host suites pass
**967 assertions**, including 357 engine assertions covering pinned identity
reuse, address persistence and rollback, deadlines, cancellation, stale callbacks,
widget ownership and blocked controls during verification. The signed APK upgraded
in place from 0.5.0 on an Android 15 emulator, retained its pairing, and reconnected
from `10.0.2.2` to the same simulated TV through a native-discovered `10.0.2.16`
proxy. It then reconnected after an app-process restart without another pairing
request. A second backend presenting a different certificate was rejected; Sync
still connected to the original saved TV and a navigation key applied once. The
test used two addresses for the same simulated TV, rather than a physical DHCP
lease change. The 320 × 640 dp Setup layout keeps Cancel and Reconnect visible.
No physical-TV pairing or controls were sent. Physical-phone reconnection remains
unverified. The published APK matches the installed, tested APK byte-for-byte.

Version **0.6.0** passes **1,042 host assertions and 50 native Android/app
assertions**. It adds keyboard mode, automatic switching on an authenticated TV
keyboard-show request, and explicit Keyboard/Remote controls. Host tests cover
IME negotiation, field and counter changes, Unicode framing, bounds and stale
input rejection. Native Android instrumentation against a local simulated TV
checks automatic and manual mode changes, repeated-show suppression, new-field
switching, Japanese composition, Unicode commits, clipboard paste, Delete,
Enter, focus loss, and continued normal remote controls. These are
emulator/mock results; keyboard input in 0.6.0 has not been checked on a physical
phone or the CSV4K TV.

Version **0.6.1** moves the keyboard shortcut between the down arrow and Home,
and recenters the top green Sync/mode pill. This small layout patch passed a
signed build and 38 focused native layout/mode assertions at 411dp and 320dp
emulator widths. Pairing, protocol and widget behavior are unchanged; no
physical-TV actions were sent during validation.

Version **0.6.2** fixes automatic keyboard switching and manual typing with the
TV service’s actual direction-specific IME messages. Versions 0.6.0/0.6.1 used
a symmetric wire schema and their simulated tests repeated that assumption;
the corrected tests reproduce both reported failures in the released client.
The fix separates editor identity from content revision, uses the TV’s actual
cursor for Unicode insertion, handles visibility and content acknowledgments
separately, and preserves stale-input cancellation. The corrected host suite
passes 1,054 assertions and the actual Android app passes 63 native assertions
against the independent loopback service.

On 2026-10-23, the exact public 0.6.2/code 13 APK was checked against the
physical CSV4K TV running Remote Service 7.00.999235495. The selected Android
emulator used its own Android Keystore pairing. Both Stremio and SmartTube
Beta triggered automatic native keyboard mode, and native commits reached each
real TV field exactly once. SmartTube’s 14 native Delete actions restored its
original empty query with authenticated cursor feedback. All known test words
were confirmed absent afterward. These are emulator-to-physical-TV checks; no
physical phone was connected.
