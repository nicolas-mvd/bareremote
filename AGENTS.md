# BareRemote

Native Java Android phone remote and app widget. Keep the application small: no third-party runtime libraries, content feeds, account system, telemetry, polling, or persistent background service. The signed APK is built with `./build.sh` using SDK 35/build-tools 35.0.0 and JDK 17+.

Read `README.md` and `docs/PROTOCOL.md` before changing pairing, trust, or lifecycle handling. Each installed phone generates its own Android Keystore identity. Never bundle credentials or signing keys. `.keys/`, `build/`, and local diagnostics are private, ignored artifacts.

Use the loopback fake TV and an explicitly selected Android emulator for development. User hardware testing is currently deferred; do not send pairing requests, keys, or change the physical TV without arranging a viewing break with the user. Distinguish emulator/mock evidence from physical-device compatibility.

Run `python3 -m unittest discover -s tests -p 'test_protocol.py'` for protocol changes. Exercise the actual Android Keystore path after cryptography changes: JVM-only TLS tests cannot validate Android key authorization. Keep certificate pinning, bounded operations, cancellation, and single-delivery semantics. Do not silently retry keys whose delivery is uncertain.
