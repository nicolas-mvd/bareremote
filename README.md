# BareRemote

A small Android TV remote for your phone. Open it, press a button. Or give its widget an entire home-screen page.

**Prototype, version 0.1.1.** Android 8+; targets Android 15. This is separate from the [BareHome TV launcher](https://github.com/nicolas-mvd/barehome) and does not require BareHome. Compatibility checks are listed below; the release remains a prerelease.

**[Download BareRemote.apk](https://github.com/nicolas-mvd/bareremote/releases/download/v0.1.1-prototype/BareRemote.apk)** · [Release notes and checksum](https://github.com/nicolas-mvd/bareremote/releases/tag/v0.1.1-prototype)

[App preview](docs/screenshots/app.png) · [Full-page widget preview](docs/screenshots/widget.png). Screenshots are from the Android emulator, paired with a local simulated TV.

## Use it

**Upgrading from 0.1.0?** Install this APK over the existing app to preserve pairing. **Do not uninstall first.** Open BareRemote once after updating, then try the widget.

1. Open the APK download link above on each phone, download it, and open it. If Android asks, allow that browser or file manager to **install unknown apps**.
2. Put the phone and TV on the same local network. Open BareRemote → **Setup**, enter the TV’s IPv4 address, and tap **Pair**. Find the address in the TV’s network settings.
3. Enter the six-character code shown on the TV. **Pair each phone separately.** After pairing, opening the app takes you directly to the remote.

The TV must provide Android TV Remote Service v2. Pairing uses the TV’s normal remote service; no ADB or developer mode is needed for everyday use. If the TV’s address changes, pair again in Setup with its new address. **Forget TV** removes this phone’s saved TV connection locally; it does not remove the phone from the TV’s trusted-device list.

Controls: D-pad, OK, Back, Home, volume down/up, mute, play/pause, and power. Hold **OK** in the app for a long press, including BareHome’s mode editor. Button behavior depends on the TV and current app. Power can wake a TV only while its remote service remains reachable over the network.

## Full-page widget

Tap **Add widget** in the app and accept your launcher’s prompt. Alternatively, touch and hold an empty home-screen area → **Widgets** → **BareRemote**.

The widget requests a 4 × 5 cell layout and resizes in both directions. Put it on an empty page, touch and hold it, and drag the resize handles to fill the page. Available sizes depend on your launcher. Its header opens the app; its buttons send commands directly. Pair once in the app first.

Long-OK is available in the app only: home-screen launchers use widget long presses for moving and resizing. The widget does no scheduled polling and starts network work only when you press a control.

Version 0.1.1 fixes commands arriving before the TV has activated its remote, or the connection closing before the TV handles the command. It adds a brief startup wait and keeps each widget connection alive briefly after sending. The widget shows the selected control in its status, such as **Sending · Right** and **Sent · Right**. “Sent” confirms transmission, not an acknowledgment of the TV’s action.

Each widget tap uses a short connection, so rapid taps queue up. The app keeps its connection while open and responds faster when navigating repeatedly.

## Build

Requires JDK 17+, Android SDK platform **35**, build-tools **35.0.0**, `zip`, and `openssl` for the first signing-key creation. No Gradle, Compose, WebView, or third-party runtime libraries; the app uses native Java views and Android’s networking and widget APIs.

```sh
export BAREREMOTE_SDK=/path/to/android-sdk
./build.sh
```

`ANDROID_HOME` also works. Output: `build/BareRemote.apk`. The first build creates an APK signing key under `.keys/`. Keep that directory private and backed up; use the same key to build installable updates. Each installed phone creates its own separate pairing identity in Android Keystore.

Run the protocol tests with Python 3 and a JDK:

```sh
python3 -m unittest discover -s tests -p 'test_protocol.py'
```

See [protocol notes and primary implementation references](docs/PROTOCOL.md) for framing, pairing, certificate pinning, deadlines, and the optional hardware test harness.

## Prototype limits and validation

One saved TV per phone, manual IPv4 entry, no discovery, text keyboard, or voice input. There are no accounts, cloud requests, foreground services, or idle widget updates. A connection is kept only while the app is foreground; widget commands use bounded connections. Behavior on other manufacturers remains unverified.

The current protocol suite has passed **143 assertions**, including loopback mutual TLS against a mock TV.

The original 0.1.0 checks used the actual APK on an Android 15 emulator with a simulated TV: all 12 app and widget buttons, app long-OK, saved pairing, widget pinning and full-page resizing, widget use after app-process termination, offline errors, and rejection of a changed TV certificate. Widget commands kept the launcher visible; returning home and reopening the app did not crash.

Version 0.1.1 has now controlled a **physical CSV4K / KTC_MOTOROLA_4K_SA_21V2 TV running Android 11**, using the signed APK on an Android 15 emulator. App and widget navigation worked, including a widget command after confirming the app process had stopped. This verifies that TV with an emulated phone, not every phone/TV combination.

On a **Samsung Galaxy S24 Ultra**, the user reported that 0.1.0 app controls worked but its widget did not. The 0.1.1 fix still needs confirmation on that phone. No other physical phones or TV models have been tested.
