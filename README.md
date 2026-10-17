# BareRemote

A small Android TV remote for your phone. Open it, press a button. Or give its widget an entire home-screen page.

**Prototype, version 0.1.** Android 8+; targets Android 15. Physical phones and TV compatibility have not yet been tested. This is separate from the [BareHome TV launcher](https://github.com/nicolas-mvd/barehome) and does not require BareHome.

**[Download BareRemote.apk](https://github.com/nicolas-mvd/bareremote/releases/download/v0.1.0-prototype/BareRemote.apk)** · [Release notes and checksum](https://github.com/nicolas-mvd/bareremote/releases/tag/v0.1.0-prototype)

[App preview](docs/screenshots/app.png) · [Full-page widget preview](docs/screenshots/widget.png). Screenshots are from the Android emulator, paired with a local simulated TV.

## Use it

1. Open the APK download link above on each phone, download it, and open it. If Android asks, allow that browser or file manager to **install unknown apps**.
2. Put the phone and TV on the same local network. Open BareRemote → **Setup**, enter the TV’s IPv4 address, and tap **Pair**. Find the address in the TV’s network settings.
3. Enter the six-character code shown on the TV. **Pair each phone separately.** After pairing, opening the app takes you directly to the remote.

The TV must provide Android TV Remote Service v2. Pairing uses the TV’s normal remote service; no ADB or developer mode is needed for everyday use. If the TV’s address changes, pair again in Setup with its new address. **Forget TV** removes this phone’s saved TV connection locally; it does not remove the phone from the TV’s trusted-device list.

Controls: D-pad, OK, Back, Home, volume down/up, mute, play/pause, and power. Hold **OK** in the app for a long press, including BareHome’s mode editor. Button behavior depends on the TV and current app. Power can wake a TV only while its remote service remains reachable over the network.

## Full-page widget

Tap **Add widget** in the app and accept your launcher’s prompt. Alternatively, touch and hold an empty home-screen area → **Widgets** → **BareRemote**.

The widget requests a 4 × 5 cell layout and resizes in both directions. Put it on an empty page, touch and hold it, and drag the resize handles to fill the page. Available sizes depend on your launcher. Its header opens the app; its buttons send commands directly. Pair once in the app first.

Long-OK is available in the app only: home-screen launchers use widget long presses for moving and resizing. The widget does no scheduled polling and starts network work only when you press a control.

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

One saved TV per phone, manual IPv4 entry, no discovery, text keyboard, or voice input. There are no accounts, cloud requests, foreground services, or idle widget updates. A connection is kept only while the app is foreground; widget commands use bounded connections. Manufacturer-specific behavior remains unverified.

The protocol suite has passed **108 assertions**, including loopback mutual TLS against a mock TV. On an Android 15 emulator, the actual APK paired using Android Keystore and delivered all 12 buttons from both the app and widget to a simulated TV. App long-OK, saved pairing, widget pinning, full-page resizing, and widget use after app-process termination were also checked. The launcher remained visible during widget commands. Returning home and reopening the app completed without crashes. Offline errors and rejection of a changed TV certificate were verified.

These checks do not establish physical-phone or TV compatibility; hardware testing was deferred while the TV was in use. The download is marked as a prerelease until real-device testing is complete.
