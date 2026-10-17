# BareRemote

A small Android TV remote for your phone. Open it, press a button. Or give its widget an entire home-screen page.

**Prototype, version 0.4.0.** Android 8+; targets Android 15. This is separate from the [BareHome TV launcher](https://github.com/nicolas-mvd/barehome) and does not require BareHome. Compatibility checks are listed below; the release remains a prerelease.

**[Download BareRemote.apk](https://github.com/nicolas-mvd/bareremote/releases/download/v0.4.0-prototype/BareRemote.apk)** · [Release notes and checksum](https://github.com/nicolas-mvd/bareremote/releases/tag/v0.4.0-prototype)

[App preview](docs/screenshots/app.png) · [Full-page widget preview](docs/screenshots/widget.png). These show version 0.3.0 on the Android emulator, paired with a local simulated TV.

## Use it

**Upgrading?** Install this APK over the existing app to preserve pairing. **Do not uninstall first.** Open BareRemote once after updating, then try the widget.

1. Open the APK download link above on each phone, download it, and open it. If Android asks, allow that browser or file manager to **install unknown apps**.
2. Put the phone and TV on the same local network. Open BareRemote → **Setup**, enter the TV’s IPv4 address, and tap **Pair**. Find the address in the TV’s network settings.
3. Enter the six-character code shown on the TV. **Pair each phone separately.** After pairing, opening the app takes you directly to the remote.

The TV must provide Android TV Remote Service v2. Pairing uses the TV’s normal remote service; no ADB or developer mode is needed for everyday use. If the TV’s address changes, pair again in Setup with its new address. **Forget TV** removes this phone’s saved TV connection locally; it does not remove the phone from the TV’s trusted-device list.

Controls: a circular D-pad with OK, Back, Home, Input, volume down/up, mute, and power. The PlayStation icon asks BareHome 1.1 or newer to open the TV’s native source/input menu, including its HDMI and CEC device names; it does not assume which HDMI socket holds a PS5. This requires a TV with the native `com.android.tv.action.VIEW_INPUTS` action. The standard Android input key does nothing on our tested TV, so this button uses the native picker bridge. Play/Pause and Add widget are available under the gear (Setup). Hold **OK** in the app for a long press, including BareHome’s mode editor. Button behavior depends on the TV and current app. Power can wake a TV only while its remote service remains reachable over the network.

If the connection gets stuck, tap the **Sync icon** in the centered green strip of the app or widget. It opens a fresh connection to the saved TV using the existing pairing; no TV code is needed. Sync does not search for TVs or discover a changed IP address.

## Updates

Open the gear (Setup) → **Check for updates**. Download a newer release, then
confirm installation with Android. Android may first ask you to allow BareRemote
to install unknown apps. Pairing and widget settings survive an in-place update.
There are no automatic phone checks or background downloads. This first updater
enabled APK must be installed manually; later versions can update through the app.
See [update checks, verification and release instructions](docs/UPDATES.md).

## Day/Night from the phone

One mode icon shows the TV's **last confirmed** preset: **sun for Day**, **moon for Night**. Tap it to toggle. The TV decides which mode comes next from its own saved state, so either phone can use the button. A **question mark** means the mode is unknown, unavailable, or the actual picture levels differ from the selected preset.

This requires **[BareHome 1.2 or newer](https://github.com/nicolas-mvd/barehome/releases/tag/v1.3)** on the TV, its supported picture-control hardware, and its existing one-time picture permission. It uses your saved Backlight/Picture Brightness presets; edit those by holding the Day/Night button in the launcher.

Opening the remote, tapping Sync, or changing mode requests a fresh TV snapshot. An idle widget keeps the last confirmed state. If the other phone or the TV remote changes the picture, tap Sync to refresh; there is no continuous monitoring.

The request uses the existing paired TV connection. The phone briefly listens for an authenticated local reply, and BareHome sends one reply before stopping. No permanent TV listener or service is added. A missing reply never triggers an automatic second toggle. Normal remote buttons work independently of picture-state support. See [how confirmation works and its limits](docs/PICTURE_STATE.md).

The hardware, permission, automatic-lighting and native Picture-mode guards remain unchanged. Confirmed settings are not a measurement of emitted light. The windowless TV entry point can briefly change activity lifecycle state; uninterrupted playback across all apps is not guaranteed.

## Full-page widget

Tap **Add widget** in the app and accept your launcher’s prompt. Alternatively, touch and hold an empty home-screen area → **Widgets** → **BareRemote**.

The widget requests a 4 × 5 cell layout and resizes in both directions. Put it on an empty page, touch and hold it, and drag the resize handles to fill the page. Available sizes depend on your launcher. The gear opens the app; Sync reconnects and checks picture state; its control buttons send commands directly. Pair once in the app first.

The first tap opens a connection. Further taps reuse it for faster navigation, and each tap resets a **60-second idle timer**. A temporary Android foreground service keeps this session alive, with a notification containing **Stop**. It ends automatically after a minute without taps. The app can keep its own connection while open.

On Android 13+, BareRemote asks once for notification permission after opening the paired app. This permission is optional: the widget still works if you decline, and Android still shows the foreground service in its Active apps/Task Manager view.

The widget uses icons only, with no title or connection-status text row. TalkBack still announces control names and connection status. The gear opens the app for setup or errors. A brief TV startup wait remains, but the delay after sending does not hold up later commands. Long-OK is app-only because home-screen launchers reserve widget long presses for moving and resizing.

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

One saved TV per phone, manual IPv4 entry, no discovery, text keyboard, or voice input. There are no accounts, scheduled background polling, or automatic startup. Manual update checks and downloads contact GitHub; remote commands remain local. The widget session lasts only until its idle timer expires or you stop it; it does not run indefinitely. Behavior on other manufacturers remains unverified.

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
