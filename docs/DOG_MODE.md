# TV-confirmed Dog Mode

Dog Mode is a TV-owned transaction. BareRemote requests it through the existing
paired, certificate-pinned Android TV Remote connection. The TV saves the exact
previous energy settings before changing them. Closing the phone or Mac remote
leaves the TV's saved Dog Mode state intact; no phone foreground service,
keepalive, repeating key, polling, alarm or wake lock maintains the mode.

A supported start transaction opens NS NOW and uses the owner's explicitly
enabled, NS NOW-only accessibility helper to select CANAL 10. The helper also
recognizes NS NOW's own three-hour “¿Seguís ahí?” confirmation while Dog Mode is
active. It stops acting when the mode ends. This keeps the phone and Mac out of
the long-running session and does not add a network listener or periodic keys.
The supported TV's energy settings are changed temporarily and journaled first.
A generic successful app launch is not proof that CANAL 10 was selected. Unsupported TV or channel
configurations must return `unavailable` rather than claim a successful start.
Ordinary remote controls continue to work independently. A selected channel and
completed setup do not measure decoded frames or guarantee the TV app or network
will continue playing indefinitely.

Stopping restores the exact saved values, including an originally absent value,
then returns Home. The TV retains its recovery journal until restoration and Home
complete. A failed or interrupted restore remains visible as `restore_required`;
it must not become a newly captured baseline on another start. Another paired
phone or Mac can inspect the TV's state and request restoration.

## One-time TV setup

Dog Mode requires compatible BareHome on the supported TV, the existing energy
settings permission, and the owner's explicit NS NOW-only accessibility setup.
Choose **Set up on TV** in BareRemote's Dog Mode section to open BareHome's
visible consent page. Follow that prompt on the TV, then choose **Check status**
in BareRemote. Opening the page does not grant consent or enable the helper.
NS NOW must already be signed in and able to play CANAL 10 normally. ADB is not
part of normal operation; NS NOW can refuse playback when debugging is enabled.
The helper must not act outside NS NOW or after Dog Mode ends.

## Settings and confirmation

Opening the remote, Settings or pressing Sync only connects; none sends a TV
app link. Settings presents Dog Mode as unknown until an explicit **Check status**
request. That request reads the journal and current settings
without launching NS NOW, altering energy settings or returning Home. There is no
extra Dog Mode request for ordinary remote connections or key presses.

State requests use a windowless TV Activity and can interrupt apps that release
their player on pause. BareRemote therefore never queries Dog Mode automatically
when its interface opens or reconnects. Explicit Check status, Start, Stop and
Set up on TV remain available; Restore settings & exit is safe to request from
unknown state.

BareRemote shows pending while a request is in progress. It changes the displayed
mode only after an authenticated TV reply. A verified `starting` reply is progress,
not successful activation; the Start request continues waiting for its final reply. Writing an app link does not confirm
that Dog Mode started or that settings were restored. A timeout or disconnect
leaves the result unknown and asks for a status check; no mutation is replayed
automatically. Cancelling a reply wait or quitting BareRemote does not send Stop.

The TV reports these fixed states:

| State | Meaning |
| --- | --- |
| `off` | No unfinished Dog Mode transaction; restoration is complete. |
| `starting` | The TV has accepted startup and is selecting the channel; it has not confirmed active. |
| `active` | The supported start transaction completed, with a retained restore snapshot. |
| `restore_required` | A saved transaction needs recovery, or its temporary energy values changed. |
| `unavailable` | The supported TV, energy permission or fixed channel route is unavailable. |
| `error` | The TV could not complete the request. Check state before another start. |
| `nsnow_access` | Startup could not access NS NOW's controls; rollback and helper removal completed. |
| `nsnow_menu` | Startup could not open TV EN VIVO; rollback and helper removal completed. |
| `nsnow_channel` | Startup could not select CANAL 10; rollback and helper removal completed. |
| `nsnow_target` | NS NOW’s channel list was reached but an exact, unique, visible and clickable CANAL 10 target could not be identified; rollback and helper removal completed. |
| `nsnow_input` | Exact CANAL 10 was identified but the TV did not accept selection, including a blocked preflight; rollback and helper removal completed. |
| `nsnow_player` | Startup could not confirm CANAL 10; rollback and helper removal completed. |
| `nsnow_debug` | NS NOW requires USB debugging to be off; rollback and helper removal completed. |

These seven `nsnow_` replies are finite failed-Start results, sent only after the TV
has confirmed successful energy restoration and helper removal. They are not
persisted diagnostic history: a later State query returns the current state,
normally `off` after rollback. Incomplete restoration returns `restore_required`
instead. The clients describe the fixed failed stage and restored settings only
for these authenticated results; there is no arbitrary diagnostic or TV content
payload. A channel confirmation does not assert decoded playback.

`unknown` and pending are client presentation states, never authenticated wire
states. A start requires a confirmed `off` snapshot. **Restore settings & exit**
is also available while startup is reported, when state is unknown, or after an
error or one of these startup failures was reported: it is an
explicit, idempotent journal restore, never an automatic retry. This permits
recovery when a state reply is blocked. The client still needs the existing
paired connection and never claims restoration without a valid reply. A failed
restore never silently resets the saved baseline.

## Fixed authenticated one-shot protocol

The exact static setup link is `barehome://dog/setup`. It accepts no query or
fragment and only opens the visible TV consent page. It uses the existing pinned
connection as a bounded seven-second command, with no callback or automatic retry.

The exact state and mutation request forms are:

```text
barehome://dog/state?reply=PRIVATE_IPV4&port=PORT&token=64_LOWERCASE_HEX
barehome://dog/start?reply=PRIVATE_IPV4&port=PORT&token=64_LOWERCASE_HEX
barehome://dog/stop?reply=PRIVATE_IPV4&port=PORT&token=64_LOWERCASE_HEX
```

No arbitrary app, channel, energy value or callback URL is accepted. Parameter
order is fixed; unknown parameters, fragments, alternate encodings and malformed
values are rejected. Callback ports are 1024–65535. The production client binds
a temporary RFC 1918 IPv4 interface; the TV additionally checks its local subnet
and excludes itself, network and broadcast addresses.

For each request the client generates a new 32-byte random secret and sends it
only inside the existing encrypted TV connection. The bounded ASCII UDP reply is:

```text
BH2|REQUEST_ID|STATE|MAC
```

`REQUEST_ID` is lowercase hexadecimal SHA-256 of the secret bytes. `MAC` is
lowercase hexadecimal HMAC-SHA256 of `BH2|REQUEST_ID|STATE`, keyed by the secret.
The client validates the exact TV source IPv4 address, request identity and MAC
with constant-time digest comparison. A reply from another source, an old
request, a changed state or an oversized datagram cannot confirm the mode. The
reply is authenticated, not encrypted, and contains only the coarse state.

State and Stop each wait at most eight seconds for one reply. Start waits at
most 35 seconds on the same callback socket: the TV replies `starting` within
seven seconds, then its bounded startup coordinator sends a final authenticated
state within 30 seconds. Each reply uses the same request ID and secret. A
`starting` reply never extends the absolute deadline. A read-only State query
may return `starting` immediately so another paired client can inspect or cancel
startup. The socket closes after final confirmation, cancellation or timeout.
The short bridge does not block for channel navigation. Connecting and discovering a changed TV address use the existing pinned
connection recovery, before the mutation is written; uncertain writes are never
sent again. Only one Dog Mode request may be outstanding per remote instance.
Lifecycle and generation checks reject results from cancelled or replaced
connections.

The TV consumes recent mutation request identities before acting, in a small
bounded replay cache. Start and Stop also follow the persisted transaction state
so a new request cannot overwrite the original restore snapshot. This is not
unlimited historical replay protection. The TV adds no permanent network
listener. The bridge sends the initial reply; a bounded in-memory callback may
send startup's final state. Stop invalidates the startup generation before
cancelling the selector and restoring settings, so a late completion cannot
reactivate the mode.

## Validation limits

The Java and Swift wire tests use independent SHA-256/HMAC vectors and real
loopback UDP sockets. They cover forged and malformed replies, wrong source
addresses, old request secrets, cancellation, socket cleanup and bounded waits.
Engine tests cover TV-only confirmation, double taps, restoration-required
states, authenticated intermediate/final startup replies, lifecycle cancellation
and no replay after uncertain delivery. Finite startup failure vectors and engine
tests verify that failure permits explicit recovery but requires a fresh `off`
before another start. Mock and
loopback results establish client protocol behavior, not compatibility with a
particular NS NOW release or a physical TV's energy controls. Hardware validation
must identify the exact TV, BareHome and NS NOW versions tested.

## Physical validation — 2026-10-23

The signed BareHome 1.4 / code 12 candidate was installed in place on the CSV4K
(Android 11 / API 30) with NS NOW 1.1.266 / code 409. The exact signed universal
BareRemote macOS 0.4.0 / build 14 was launched from Applications on arm64 with its
existing pairing. The TV setup link opened the visible consent screen without
changing energy settings or enabling the helper. Developer options and USB
debugging were then disabled; the debugging listener was confirmed closed before
NS NOW launched.

Start returned authenticated `active`, and the owner confirmed CANAL 10 was
playing. End returned authenticated `off` only after the TV's exact energy
readbacks and helper removal passed; the owner confirmed BareHome was showing.
The private baseline contained sleep 86400000, attentive 14100000 and no-signal 3;
startup-failure checks also confirmed those original values were restored. The
display timeout and picture controls were left unchanged. Debugging remains off.

This was a short start/exit check. The actual three-hour inactivity prompt,
long-duration playback, TV reboot recovery and other TV/NS NOW versions were not
physically tested. Prompt handling has source-backed selector fixtures and a
final exact-prompt check before its one Back action. Android's Dog Mode client
was checked with host/loopback tests; no physical phone was used for this check.
