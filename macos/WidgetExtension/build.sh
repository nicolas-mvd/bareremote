#!/bin/sh
# Compile a static WidgetKit extension; no AppIntents metadata or shared group is needed.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

if test "$#" -lt 2 || test "$#" -gt 3; then
    echo 'Usage: ./WidgetExtension/build.sh /absolute/output.appex signing-identity [--universal]' >&2
    exit 2
fi
extension=$1
signing_identity=$2
case "$extension" in /*.appex) ;; *) echo 'Output must be an absolute .appex path.' >&2; exit 2 ;; esac
case "${3:-}" in '') architectures=$(uname -m) ;; --universal) architectures='arm64 x86_64' ;; *) echo 'Unknown architecture option.' >&2; exit 2 ;; esac
if test -z "$signing_identity"; then echo 'Signing identity must not be empty.' >&2; exit 2; fi
sdk=$(xcrun --sdk macosx --show-sdk-path)
temporary=$(mktemp -d "${TMPDIR:-/tmp}/bareremote-widget.XXXXXX")
trap 'rm -rf "$temporary"' 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$extension/Contents/MacOS"
for architecture in $architectures; do
    # Match Xcode's macOS extension bootstrap. Entering Swift's generated _main
    # directly resolves the widget type but exits before connecting its XPC host.
    xcrun swiftc -O -parse-as-library -application-extension \
        -target "$architecture-apple-macosx14.0" -sdk "$sdk" \
        -module-name BareRemoteWidget -framework SwiftUI -framework WidgetKit \
        -Xlinker -e -Xlinker _NSExtensionMain \
        BareRemoteWidget.swift -o "$temporary/BareRemoteWidget-$architecture"
done
if test "${3:-}" = --universal; then
    xcrun lipo -create "$temporary/BareRemoteWidget-arm64" "$temporary/BareRemoteWidget-x86_64" \
        -output "$extension/Contents/MacOS/BareRemoteWidget"
else
    cp "$temporary/BareRemoteWidget-$architectures" "$extension/Contents/MacOS/BareRemoteWidget"
fi
cp Info.plist "$extension/Contents/Info.plist"
# Keep extension and host versions aligned in the normal Contents/PlugIns layout.
host_info="$(dirname -- "$extension")/../Info.plist"
if test -f "$host_info"; then
    short_version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$host_info")
    bundle_version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$host_info")
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $short_version" "$extension/Contents/Info.plist"
    /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $bundle_version" "$extension/Contents/Info.plist"
fi
codesign --force --sign "$signing_identity" --entitlements Widget.entitlements \
    --identifier uy.nico.bareremote.macos.desktopwidget "$extension"
codesign --verify --strict "$extension"
