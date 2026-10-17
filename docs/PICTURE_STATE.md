# TV-confirmed picture state

BareRemote 0.3 and BareHome 1.2 add a reply to picture requests. The standard
Android TV Remote v2 protocol only launches an app link; it cannot return
BareHome's state over that connection.

## What the icon means

Opening the app, tapping Sync, or tapping the mode button requests a fresh TV
snapshot. Sun means the TV confirmed its saved Day preset and matching picture
levels; moon means Night. A question mark means the state is unavailable,
unconfirmed, or the current picture levels differ from the selected preset.
The TV decides which preset to apply when toggling, so a stale phone icon does
not decide the next mode.

The widget retains the last confirmed snapshot while idle. There is no ongoing
polling or subscription. If another phone or the TV's own controls change the
picture, tap Sync to refresh. Confirmed settings do not measure emitted light or
guarantee uninterrupted playback in every TV app.

## One request, one reply

1. The phone opens a temporary UDP socket and generates a fresh 32-byte secret.
2. It sends a fixed BareHome state/toggle link through its existing authenticated,
   certificate-pinned TV Remote connection.
3. BareHome validates the request and callback address, reads the picture state
   or performs its guarded toggle, then sends one authenticated UDP reply.
4. The phone verifies the TV's source address, request identity and message
   authentication code before changing the icon. It closes the socket after a
   reply, cancellation or timeout. It never automatically retries a toggle.

BareHome gains Android's `INTERNET` permission for this outbound local reply.
It adds no network listener, permanent service, cloud connection or background
polling. A short, non-exported receiver completes the reply after the windowless
entry activity finishes. The callback must target a private IPv4 address on one
of the TV's local subnets, excluding the TV itself, network and broadcast addresses.

The exact URI forms are:

```text
barehome://picture/state?reply=PRIVATE_IPV4&port=PORT&token=64_LOWERCASE_HEX
barehome://picture/toggle?reply=PRIVATE_IPV4&port=PORT&token=64_LOWERCASE_HEX
```

Parameter order is fixed; unknown parameters, fragments, encoding variants and
invalid values are rejected. Ports are 1024–65535. Existing exact `day`, `night`
and `inputs` links remain compatible with older BareRemote releases.

The bounded ASCII reply has this form:

```text
BH1|REQUEST_ID|STATE|MAC
```

`REQUEST_ID` is the lowercase hexadecimal SHA-256 digest of the secret bytes.
`STATE` is `day`, `night`, `custom`, `unavailable`, or `error`. `MAC` is lowercase
hexadecimal HMAC-SHA256 of `BH1|REQUEST_ID|STATE`, keyed by the secret. The secret
is carried only in the encrypted request, not the UDP reply. The reply is
authenticated but not encrypted; it contains only this coarse picture state.

Recent toggle request identities are stored in a small bounded replay cache on
the TV and consumed before applying a change. Repeated recent requests do not
toggle twice. This cache is not unlimited historical replay protection. Phone
generation checks reject results from cancelled or replaced sessions.

This path preserves BareHome's exact hardware gate, optional picture permission,
automatic-lighting guard, original Picture-mode requirement, bounds and rollback.
It does not accept arbitrary picture levels or change native Picture modes.

Guest Wi-Fi isolation, blocked UDP, VPN routing, or an older BareHome installation
can prevent the reply. Regular remote buttons continue to use their existing
connection; a missing reply must never be treated as a confirmed mode or trigger
an automatic second toggle.
