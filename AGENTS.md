# BareRemote

Native Java Android phone remote and app widget. Keep the application small: no third-party runtime libraries, content feeds, account system, telemetry, polling, or permanently running service. Widget taps may start the user-requested foreground remote session; it must stop 60 seconds after the last widget action, with an immediate Stop action in its notification. Never autostart it or automatically replay uncertain keys. The signed APK is built with `./build.sh` using SDK 35/build-tools 35.0.0 and JDK 17+.

Read `README.md` and `docs/PROTOCOL.md` before changing pairing, trust, or lifecycle handling. Each installed phone generates its own Android Keystore identity. Never bundle credentials or signing keys. `.keys/`, `build/`, and local diagnostics are private, ignored artifacts.

Read `docs/PICTURE_STATE.md` before changing mode confirmation. Picture requests use the existing pinned connection and one temporary authenticated UDP reply. Keep exact URI validation, source-address checks, constant-time MAC comparison, bounded waits, cancellation and generation checks. A mode icon is the last TV-confirmed snapshot; never substitute the phone's last requested mode. Idle widgets do not poll. Never automatically retry a toggle whose result is uncertain.

Use the loopback fake TV and an explicitly selected Android emulator for development. Do not send pairing requests, keys, or change the physical TV without arranging a viewing break with the user. Distinguish emulator/mock evidence from physical-device compatibility, and name the version actually tested on hardware.

Run `python3 -m unittest discover -s tests -p 'test_*.py'` for protocol changes. Exercise the actual Android Keystore path after cryptography changes: JVM-only TLS tests cannot validate Android key authorization. Keep certificate pinning, bounded operations, cancellation, and single-delivery semantics. Do not silently retry keys whose delivery is uncertain.
