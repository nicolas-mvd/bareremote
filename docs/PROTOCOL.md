# Protocol implementation notes

BareRemote implements the small subset of Android TV Remote v2 needed for D-pad, navigation, volume, power and playback keys. It has no protobuf or third-party networking runtime. `Proto.java` is an independently written bounded encoder/parser; `TvProtocol.java` implements the messages against the references below.

## Primary references

- [tronikos/androidtvremote2 pairing implementation](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/pairing.py)
- [Remote v2 implementation](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remote.py)
- [Pairing wire schema](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/polo.proto)
- [Remote wire schema](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remotemessage.proto)
- [Google's original pairing implementation](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/+/master/java/src/com/google/polo/pairing/PairingSession.java)
- [Google's original challenge calculation](https://android.googlesource.com/platform/external/google-tv-pairing-protocol/+/master/java/src/com/google/polo/pairing/PoloChallengeResponse.java)
- [Android network service discovery API](https://developer.android.com/reference/android/net/nsd/NsdManager)
- [Home Assistant's Android TV Remote discovery declaration](https://github.com/home-assistant/core/blob/dev/homeassistant/components/androidtv_remote/manifest.json)

The pairing and control references were consulted on 2026-10-17; the discovery
references on 2026-10-20. Their source and generated protobuf code are not bundled. The original Google pairing project and androidtvremote2 use Apache-2.0; the schema also credits louis49/androidtv-remote. Protocol field numbers and enum values are implemented directly in BareRemote's own code.

## Pairing and trust

Setup's explicit **Scan for TVs** action uses Android's native `NsdManager` to
browse `_androidtvremote2._tcp.` over local multicast DNS. It resolves candidates
serially, accepts only IPv4 addresses advertising remote port 6466, and bounds
the scan to 12 seconds, 64 candidates and 32 result addresses. Discovery names are
untrusted labels, not identity or certificate evidence. The existing TV-code
check and certificate pin remain the trust boundary. Browsing alone does not
change the saved host or pin.

From 0.5.1, selecting a result with a current saved pairing explicitly checks
the candidate on port 6466 using the existing Android Keystore identity and exact
saved TV certificate. Manual address entry followed by **Reconnect** uses the
same check. Only a successful, ready pinned connection may commit the new host;
the certificate and pairing identity version are retained. Failure, cancellation,
leaving the activity, a stale completion or failed persistence must preserve the
previous host and trust. Candidate verification has a seven-second operation
deadline and blocks other control work until it completes. No keys or pairing
requests are sent and no uncertain command is replayed. The verified connection
then becomes the shared app/widget session; later widget sessions use the new
saved address.

An unpaired phone or one awaiting the legacy identity migration still uses the
TV-code pairing flow. **Pair with a TV code** is a separate deliberate action for
a different or reset TV. A failed saved-TV check never falls back to pairing or
accepts an advertised name as proof of identity.

The scan holds a temporary Wi-Fi multicast lock for compatibility with older
Android versions. Stop, timeout, dialog dismissal and activity pause release it
and stop browsing. Generation checks suppress late callbacks, including older
Android resolver operations that cannot be directly cancelled. There is no
subnet port sweep, service, polling, or automatic discovery on launch or Sync.
TVs must advertise their remote service on the same multicast-capable network;
manual IPv4 entry remains available.

Pairing uses mutual TLS on port 6467, followed by version-2 request, hexadecimal-code options, configuration and secret messages. The six-character TV code contains a check byte and two nonce bytes. The SHA-256 input is the unsigned big-endian RSA modulus and exponent from the phone certificate, then those from the TV certificate, then the two nonce bytes. The first digest byte must match the code's check byte before a secret is sent.

Initial pairing allows an untrusted TV certificate because the TV has not been paired yet. The certificate is returned for storage only after an OK secret acknowledgment. A nonempty echoed secret must match. Missing or empty echoes are allowed: Google's original implementation documents servers that do not echo the secret. Codes, hashes and private keys are never logged by the protocol layer.

Every later connection on port 6466 requires the exact saved TV certificate. The TLS identity adapter must enforce this pin, and the protocol layer verifies it again before processing messages. A changed TV certificate requires deliberate re-pairing.

From 0.4.1, the Android identity uses the stable `bareremote.client.v3` alias and
a certificate subject containing a newly generated random UUID. This UUID is
created only when the key is generated; it is not a hardware ID, account ID, or
phone name. It remains part of the stored certificate across sessions and app
updates. The TV remote service examined on our CSV4K deduplicates trusted clients
by certificate subject, so unique keys alone were insufficient when all subjects
were `CN=BareRemote`. Multiple live connections are otherwise supported.

A legacy saved pairing is retained but cannot send controls until the user pairs
once with the new identity. The TV address stays prefilled, and successful pairing
atomically saves the current identity version with the TV certificate pin. Cancel
or failure must not complete this migration. The old Keystore entry is retained;
no TV trust entries are deleted by BareRemote. Later launches reuse the v3 key.

On Android, the RSA identity stays in Android Keystore with signing/verification purposes. Its configuration includes `ENCRYPTION_PADDING_NONE` for Conscrypt interoperability: Conscrypt can apply RSA-PSS padding before invoking the Keystore private-key operation. Removing this setting can produce `INCOMPATIBLE_PADDING_MODE` during the client-certificate handshake. This setting does not add encryption/decryption purposes to the identity. See [the Conscrypt maintainers' explanation](https://github.com/google/conscrypt/issues/1201).

The cancellable `Pairing.start` overload checks before starting and between handshake steps, so leaving setup suppresses subsequent pairing requests. An already blocked TLS/read operation remains subject to its timeout; a published pairing object can be closed directly.

Session `close()` is safe for an Android lifecycle callback. It closes the underlying TCP socket immediately with socket linger disabled, then queues TLS wrapper cleanup on a bounded daemon worker that expires after one idle second. This matters because Conscrypt's TLS `close()` can write a shutdown notification and throw `NetworkOnMainThreadException` when invoked directly from `onPause`. Aborting the transport also releases any blocked reader. The cleanup worker is capped at one thread and 32 queued wrappers; the TCP transport is already closed even if native TLS cleanup stalls.

Closing immediately after a write can race the TV's asynchronous input dispatch even when the bytes arrived. BareRemote allows **750 ms after a widget key write** before reporting completion. In 0.1.2, `RemoteEngine` schedules that completion on the main handler and immediately frees its serial network worker for the next command. The widget service retains the connection during this window; commands no longer wait behind another key's 750 ms grace period. Connection loss or cancellation prevents a successful completion, and no key is resent. This is a delivery allowance, not an acknowledgment that the TV performed the action. One-shot protocol callers and the test harness can still use the blocking `Remote.awaitCommandDeliveryWindow()` helper on their worker before disposing of a session.

There is also a separate startup race: the TV's `RemoteStart` message can arrive before Android InputReader has registered its virtual remote device. A key sent immediately can be discarded; waiting afterward does not recover it. `Remote.connect()` therefore starts its ping reader and allows 250 ms for input registration before returning. This wait shares the five-second connection budget and aborts on disconnect or interruption. Both waits are bounded compatibility allowances; the protocol exposes no acknowledgment of individual input delivery.

## Android session lifetime in 0.1.2

The activity and widget service share the engine's saved TV connection. The first widget control opens a session; later controls reuse it. Each widget tap resets a **60-second idle timeout**. A temporary foreground service of type `connectedDevice` owns the widget session and shows a notification with a **Stop** action. Stop or idle expiry releases that ownership and closes the connection when the foreground activity does not also need it. The service does not start at boot or poll in the background; its active protocol reader only answers TV pings. There is no indefinitely retained widget connection.

On Android 13+, the activity requests `POST_NOTIFICATIONS` once after pairing or opening an already paired app. Denial does not disable widget commands or the foreground service. Android still exposes the running service in its Active apps/Task Manager view even when its notification is absent from the drawer.

**Sync** in the app or widget explicitly replaces the current connection using the saved IP address, phone Keystore identity, and pinned TV certificate. It does not send a TV key, request a pairing code, or discover another address. The engine invalidates queued work from the old session; an old completion cannot close its replacement. Requests have individual seven-second deadlines, and only an operation actively writing may have its connection closed by that operation's watchdog. The protocol itself does not silently resend failed commands.

## Picture requests in 0.2.0

The source button sends the fixed app link `barehome://inputs`. BareHome 1.1 dispatches the native `com.android.tv.action.VIEW_INPUTS` action, with no HDMI assumptions or automated menu sequence. The standard Android input key (178) did not open the selector on our CSV4K TV, so the UI uses this bridge. TVs without that activity receive an honest unsupported message from BareHome. The protocol still supports standard key 178 for standalone callers.

Day/Night uses the Remote v2 app-link capability (512), envelope field 90, with a UTF-8 string in nested field 1. The picture API accepts only a boolean and emits exactly `barehome://picture/day` or `barehome://picture/night`; the input API emits only `barehome://inputs`. It exposes no arbitrary URL or brightness input. These requests share the engine’s connection ownership, queue, deadlines, cancellation, and widget delivery allowance. The connection is still pinned and authenticated with the saved phone identity.

BareHome 1.1 accepts only the fixed picture/input links and independently validates them before opening the input menu or applying a saved profile. This remote protocol has no application-level acknowledgment of profile application or current mode. UI labels therefore describe a request, not observed TV state. Other apps on the TV can also invoke an exported deep link; the activity does not confer settings permission on callers and retains BareHome’s hardware and picture guards.

## Small scope and limits

Version 0.3 adds optional TV-confirmed state/toggle requests. See
[the picture-state protocol](PICTURE_STATE.md) for its strict app-link format,
one-shot UDP reply, authentication, replay bounds and icon semantics. The TCP
connection remains the same pinned Remote v2 session. Reply waits use a separate
bounded worker; regular key dispatch does not wait for them. Sync reports the
remote connection first and refreshes the mode asynchronously. Query failures
leave normal controls usable, and toggles are never automatically repeated.

Ping, key, keyboard/IME, power, volume and app-link features are negotiated.
Voice is not requested. Each requested feature is intersected with the server's
advertised capabilities; a server without IME or app links can still receive
normal keys. The remote becomes ready only after configuration and a start
message; standby still permits a wake key. A single reader answers server pings
while a session is open. The Android engine and service manage connection
ownership; the standalone protocol layer does not start Android services.

## Native keyboard input

Android 0.6.0 and macOS 0.3.0 request the Remote v2 IME feature (4), using the
[current wire schema](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remotemessage.proto)
and [the reference client's text commits](https://github.com/tronikos/androidtvremote2/blob/main/src/androidtvremote2/remote.py),
consulted on 2026-10-22. Envelope field 20 supplies app/field context, field 21
supplies the IME and field counters, and field 22 explicitly requests keyboard
display. App information alone is not a show request. A valid field-22 message
switches an open remote to keyboard mode; repeated messages for that same field
do not undo the user's **Remote** choice. A new field can request keyboard mode
again. **Keyboard** also opens the mode manually. When the service has not
provided a focused editor with matching counters, the UI waits for a TV text
field instead of guessing a destination or sending input to another app.

Text is a stream of native input-method commits, not a local mirror or whole-field
replacement. Marked/composing text stays on the phone or Mac until its input
method commits. Each committed chunk uses field 21 with one edit-info entry:
insert=1 and a RemoteImeObject containing the exact Unicode string. Its two
positions are the final inserted UTF-16 unit's index (UTF-16 length minus one),
following the established reference client's commit convention while using
Android's UTF-16 indexing for non-BMP text. BareRemote does not invent unverified
replacement or selection opcodes. Backspace, forward delete, Enter, Tab and
cursor movement use the corresponding short Android key commands. The remote
service does not acknowledge that a TV application applied a commit or key.

Each input captures the authenticated connection and focused editor. Immediately
before writing, the protocol rejects a changed connection, app, field, expired
deadline or cancelled session. A newer IME counter for the same editor is used
at the write, so a server acknowledgment does not discard later queued
characters; it never redirects captured input to a different editor. IME
operations share the existing serialized command writer, bounded queue and
connection lifetime. Failed or uncertain input is never retried or carried into
a replacement connection. Close, Sync, field loss and stale lifecycle
completions invalidate the old editor. The Mac callback queue coalesces state
updates and preserves a show identifier only for the current explicitly shown
field, so an older show event cannot open an unrelated editor.

TV-provided contents are parsed with frame/string/UTF-16 bounds and then
discarded. They are never mirrored in the UI, logged or saved; this also avoids
revealing passwords supplied by a TV service. A Mac commit is bounded to 4 KiB of
UTF-8 and 2,048 UTF-16 units; Android allows 16 KiB and 4,096 UTF-16 units.
Native composition is cleared on editor/lifecycle changes, and committed text
has no local undo history. There is no clipboard
monitor, keyboard service, idle polling or physical-TV probe.

The macOS pure-state tests cover IME negotiation, zero counters, both message
orders, explicit show versus app-only context, repeated show/manual override,
stale app/field/counter messages, same-editor ACK advances, connection-scoped
tokens, Unicode vectors, limits, malformed fields and close. The independent
`NativeImeTv` loopback fixture validates exact Unicode frames and UTF-16
positions over real Keychain mutual TLS, sequential commits across an ACK,
single-delivery keyboard keys, deadline/stale-target rejection without a frame,
field loss, unsupported IME with usable normal keys, and malformed IME shutdown.
Run `python3 macos/Tests/test_loopback.py`. These mock results do not establish
keyboard compatibility with the physical CSV4K TV.

Frames are varint length-delimited, limited to 64 KiB and 4096 fields. The parser rejects truncation, integer overflow, invalid tags and deprecated group wire types. Unknown ordinary fields can be skipped. A remote connection has a shared five-second network deadline; incomplete frames also have a deadline. Connections require a numeric IP address so DNS cannot exceed that budget. An idle remote read expires after sixteen seconds. Long keys send start, wait 650 ms, and send end even when the calling worker is interrupted. A command is not retried by this layer.

Run `python3 -m unittest discover -s tests -p 'test_*.py'`. The protocol suite has passed **156 assertions** covering independent wire/hash vectors, malformed and fragmented frames, actual loopback mutual TLS pairing, server pinning, ping responses, standby readiness, bounded long presses and the connect deadline. An input-registration regression drops keys received before the simulated device is ready, even if the connection remains open afterward; the startup wait lets exactly one key apply. A separate deferred-dispatch regression reproduces a received key being lost by immediate disconnect, then verifies that the delivery window applies exactly one key while answering pings. Cancellation and interruption remain prompt. Loopback tests do not establish compatibility with a physical TV or validate Android service lifecycle behavior. The engine suite also passes **55 assertions** against deterministic scheduling and network test doubles, covering rapid keys, shared ownership, Sync, timeouts, and cancellation. [The README](../README.md#prototype-limits-and-validation) distinguishes Android integration and hardware results.

`tests/ProtocolCli.java` is an optional manual hardware harness. Compile it with
`Proto.java`, `TvProtocol.java`, `PictureStateWire.java`, `PictureStateClient.java`,
`KeyboardState.java` and `KeyboardSession.java` using a Java 8+ JDK. Supply a
test-only RSA PKCS12 client identity and its password through
`BAREREMOTE_STORE_PASSWORD`. The `pair TV_IP client.p12 tv-pin.der` command waits
for the TV code on standard input; `check` verifies readiness without sending a
key. Only explicit `key ... KEYCODE` or `long ... KEYCODE` commands send input.
Keep the identity and saved pin outside the source tree and do not distribute a
paired identity with an APK.

For emulator integration, run `python3 tests/run_fake_tv.py` and enter `10.0.2.2` in the app. The fake server listens only on host loopback ports 6467/6466, prints a test pairing code, and requires a real mutual-TLS client certificate. `KEY` records receipt. Keys arriving within 200 ms after `RemoteStart` are discarded with `KEY_IGNORED_NOT_READY`; accepted keys produce `KEY_APPLIED` after a further 250 ms simulated dispatch delay only if the session remains alive. Disconnect cancels queued keys, so receipt alone is not a passing widget check. Its temporary certificate and in-memory pairing list disappear when it stops, so each restart requires pairing again. It never contacts a physical TV. This exercises the installed Android app, its Keystore identity, and widget command path without interrupting TV viewing; it does not establish physical-TV compatibility.

The 0.3 suites add authenticated callback and real UDP tests: **156 protocol,
108 engine and 40 callback assertions** pass. These include forged/malformed or
wrong-source replies, timeouts, close cancellation, stale generations and
revisions, saved-state identity scoping, and normal key dispatch during a reply
wait. Hardware coverage is recorded separately in the README.

For opt-in emulator callback testing, set `BAREREMOTE_ADB` to the SDK adb path
and `BAREREMOTE_EMULATOR_SERIAL` to the explicitly selected emulator serial
before starting the fake TV. It creates short-lived local UDP console
redirections and removes them afterward. This mock path accepts the emulator's
Ethernet address `10.0.2.15`; disable its simulated Wi-Fi if routing selects
`10.0.2.16`. It never forwards callbacks to LAN devices and does not log request
secrets. Production phones use their actual local address without redirection.

The 0.4.1 multi-client suite adds 39 assertions using loopback mutual TLS and an
independent fake trust-store model that replaces certificates with matching
subjects. It reproduces the legacy pairing collision, then verifies two unique
identities can connect, answer their own pings, send commands, and reconnect
without replacing each other. The engine suite now has 172 assertions, including
legacy migration gates, explicit pairing, cancellation, failed persistence, and
restart. Total across the five host suites: 487 assertions.
