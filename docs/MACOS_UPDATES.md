# Manual macOS updates

Open **BareRemote → Check for Updates…** (also available from TV Setup). The
updater contacts GitHub only after **Check for Updates**. A newer version offers
**Download**, then **Install & Reopen** after verification. BareRemote quits,
replaces its app bundle, and opens the new version. Pairing stays in the existing
Keychain and Application Support locations; the updater does not read or transmit it.

Closing the update window or choosing Cancel cancels an unfinished operation.
A failed request is shown as an error, never as “up to date.” There is no background
check, timer, automatic download, login item, notification or update service.

The app must be installed in a writable folder on a local disk, such as
`/Applications` or your own `~/Applications`. The updater never invokes sudo,
requests administrator credentials or changes permissions. A protected install
location produces an error before BareRemote quits. A self-built app signed with
another identity cannot install a public release through this updater.

## Validation and installation

The metadata URL is fixed:
`https://raw.githubusercontent.com/nicolas-mvd/bareremote/main/macos-update.json`.
Only BareRemote's exact HTTPS GitHub release asset path is accepted. Redirects
are limited to four and GitHub's HTTPS release asset hosts. Metadata is capped at
16 KiB, the ZIP at 32 MiB, and expanded ordinary files at 128 MiB / 4,096 entries.
HTTP requests have an eight-second inactivity timeout and total check/download
deadlines of 25/90 seconds. Cancel invalidates the active ephemeral URLSession;
late callbacks cannot update a cancelled screen.

The ZIP byte count and SHA-256 must match the manifest. Both ZIP directories are
checked for path traversal, absolute paths, duplicate aliases, links, encryption,
unsupported compression, overlapping entries and mismatched headers. Files are
then decoded directly into a new private directory, with each decoder's output
buffer capped to the declared size. CRC-32 is checked. Extraction never creates
links, devices, resource forks, or files outside the single app bundle.

Before offering installation, BareRemote checks the bundle identifier, exact
release version name/code, an increasing build number, and strict code signatures
for all architectures and nested code. The candidate must satisfy the currently
installed app's designated signing requirement and have the same Apple Team ID.
Ad hoc signing cannot pass this check. This is signature continuity verification;
it does not add Apple notarization to a release. Version 0.3.0 is an Apple
Development-signed prototype, not a notarized Developer ID application. No
Developer ID Application identity is available for this release.

Installation copies the verified app into a private staging directory beside the
current app, then verifies that actual copy again. Only after the user chooses
**Install & Reopen** does a short-lived helper wait for BareRemote to exit. It
rechecks the versions and signing requirement, uses same-volume renames, and
reopens the app. A failure during replacement restores the previous bundle and reopens it with
a visible update failure message.
The old app is not overwritten while it is running. A helper that cannot observe
app termination stops after 30 seconds without replacing anything. Failed helper
work is retained in its private `.BareRemote-update-UUID` staging folder with an
`install.log` / `result.txt` for diagnosis; successful staging is removed.

GitHub receives ordinary HTTPS request metadata such as the Mac's public IP.
No TV address, TV text, certificate, pairing key, settings or account data is sent.

## Publishing

Build and test a new signed universal app with a larger `CFBundleVersion`, keeping
its Apple signing identity compatible with the existing release. Publish the
immutable `BareRemote-macOS.zip` asset first. Check that the asset is downloadable,
then update `macos-update.json` on the default branch with the actual artifact:

```json
{
  "versionCode": 11,
  "versionName": "0.3.0",
  "zipUrl": "https://github.com/nicolas-mvd/bareremote/releases/download/macos-v0.3.0-prototype/BareRemote-macOS.zip",
  "sha256": "64 lowercase hexadecimal characters",
  "size": 123456
}
```

Keep the previous feed until the new asset exists. Never change published bytes
under an existing build number. This explicit manifest selects the channel; the
updater does not scrape the latest GitHub tag.

## Tests

`swift test --package-path macos --filter MacUpdateTests` covers strict metadata,
repository/origin policy, traversal and link rejection, decoder bounds, malformed
ZIP headers, real built-archive extraction, bounded HTTP responses, cancellation
and total deadlines. The signed-fixture test additionally runs when
`BAREREMOTE_UPDATE_SIGN_IDENTITY` names an available local Apple signing identity.
It signs isolated fixtures, validates signature continuity and versions, rejects
tampered/ad hoc candidates, and checks staging leaves the previous app intact. An isolated helper test also
exercises successful replacement and rollback after a forced rename failure; only
app launching is stubbed so it cannot register or open a duplicate app.
It does not update the real `/Applications/BareRemote.app` or control a TV.

Apple references: [static signature verification](https://developer.apple.com/documentation/security/secstaticcodecheckvalidity(_:_:_:)),
[designated requirements](https://developer.apple.com/documentation/security/seccodecopydesignatedrequirement(_:_:_:)),
and [Compression decoding](https://developer.apple.com/documentation/compression/compression_stream_process(_:_:)).
