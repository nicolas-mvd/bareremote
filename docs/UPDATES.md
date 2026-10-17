# App updates

BareRemote checks a small update manifest in its own public GitHub repository and downloads APKs from that repository's releases. No account or GitHub token is needed.

Open the gear (Setup), then **Check for updates**. Checking is manual; the widget does not check or download updates.

## Install an update

1. Check for updates and select **Download** when a newer version is available.
2. After verification, choose **Install**. Android may first ask you to allow BareRemote to install unknown apps. Allow this source if you want to use the updater, then return and choose Install again.
3. Confirm the update in Android's installer. You can cancel instead. The app never silently installs or reboots the device.

Updates install over the existing app. Do not uninstall first: uninstalling removes the app's local data. Pairing and widget configuration remain in place during normal updates. Open BareRemote once after updating. **Exception:** upgrading from 0.4.0 or earlier
to 0.4.1 requires one new TV-code pairing on each phone to fix the shared
certificate-name bug. The saved TV address and widgets are retained.

Network errors leave the existing app usable. A failed check is not reported as “up to date.” Downloading requires an explicit tap and can be cancelled by leaving the update screen. Manufacturer restrictions can prevent the standard Android installer from opening; the GitHub APK and ADB installation remain available.

## What is verified

The updater accepts only a fixed repository's HTTPS release-asset URL and bounded metadata/download sizes. Before installation it checks the APK's SHA-256 checksum, byte count, package name, increasing version code, and signing certificates against the currently installed app. Android performs its own package validation too. A self-built app signed with a different key cannot install the public update in place; keep using its original key or deliberately migrate outside this updater.

The update provider grants read-only access to the staged APK for Android's installer. It does not expose arbitrary private files. The app uses `REQUEST_INSTALL_PACKAGES` for the user-confirmed installation flow; this is not permission for silent installation.

Update checks contact `raw.githubusercontent.com`; downloads contact GitHub's release asset hosts. GitHub receives normal HTTPS request metadata such as the device's public IP. The apps send no pairing identity, TV address, picture settings, app list, analytics, or account data. No updater service remains running while idle.

Android references: [requesting package installs](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()), [package installer intent](https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE), and [APK signing information](https://developer.android.com/reference/android/content/pm/SigningInfo).

## Publishing future updates

Keep the existing private release signing key. Increase the Android version code, build and test the APK, and publish it with the exact existing asset filename. Only then update `update.json` on the default branch:

```json
{
  "versionCode": 123,
  "versionName": "example-version",
  "apkUrl": "https://github.com/nicolas-mvd/REPOSITORY/releases/download/TAG/APP.apk",
  "sha256": "64 lowercase hexadecimal characters",
  "size": 123456
}
```

Use the built APK's actual version, byte count and SHA-256; do not copy values from an earlier build. Keep the manifest pointed at the previous available release until the new asset is downloadable. This explicit feed selects the update channel, including any intentionally published prototype; it does not scrape “latest” or sort GitHub tags. Never replace a published APK with different bytes under an existing version code.

## Validation

The update validation suites exercise 80 assertions per app: metadata parsing, official URL/size/hash policy, update versus current/older versions, package and signer mismatch rejection on API 26 and 35, stale callbacks, prompt cancellation during blocked network cleanup, and total deadlines. Emulator checks exercised real GitHub metadata and APK downloads, verification, the unknown-app-source permission return, Android's confirmation, and a completed installation. These checks use isolated lower-version fixtures with the production updater code to make an existing published release eligible; they do not demonstrate every manufacturer's installer.
