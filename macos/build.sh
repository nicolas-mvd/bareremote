#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

case "${1:-}" in
    '') swift build -c release ;;
    --universal) swift build -c release --arch arm64 --arch x86_64 ;;
    *) echo 'Usage: ./build.sh [--universal]' >&2; exit 2 ;;
esac
if test "${1:-}" = --universal; then
    binary_dir=$(swift build -c release --arch arm64 --arch x86_64 --show-bin-path)
else
    binary_dir=$(swift build -c release --show-bin-path)
fi
output="$(pwd)/../build/macos"
app="$output/BareRemote.app"
archive="$output/BareRemote-macOS.zip"
mkdir -p "$output"
staging=$(mktemp -d "$output/.bareremote-build.XXXXXX")
staged_app="$staging/BareRemote.app"
staged_archive="$staging/BareRemote-macOS.zip"
install_started=0
completed=0
had_app=0
had_archive=0

restore_previous() {
    destination=$1
    staged=$2
    backup=$3
    failed=$4
    had_previous=$5
    if test "$had_previous" -eq 1; then
        # No backup means the original destination was never moved.
        if ! test -e "$backup" && ! test -L "$backup"; then return 0; fi
    elif test -e "$staged" || test -L "$staged"; then
        # The new item was never installed.
        return 0
    fi
    if test -e "$destination" || test -L "$destination"; then
        mv "$destination" "$failed" || return 1
    fi
    if test "$had_previous" -eq 1; then
        mv "$backup" "$destination" || return 1
    fi
}

cleanup() {
    status=$?
    trap - 0 HUP INT TERM
    rollback_failed=0
    if test "$install_started" -eq 1 && test "$completed" -eq 0; then
        if ! restore_previous "$app" "$staged_app" "$staging/previous.app" "$staging/failed.app" "$had_app"; then
            rollback_failed=1
        fi
        if ! restore_previous "$archive" "$staged_archive" "$staging/previous.zip" "$staging/failed.zip" "$had_archive"; then
            rollback_failed=1
        fi
    fi
    if test "$rollback_failed" -eq 1; then
        echo "Build failed; recover the previous output from $staging" >&2
        status=1
    elif ! rm -rf "$staging"; then
        echo "Could not remove build staging directory: $staging" >&2
        status=1
    fi
    exit "$status"
}
trap cleanup 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$staged_app/Contents/MacOS" "$staged_app/Contents/Resources"
cp "$binary_dir/BareRemote" "$staged_app/Contents/MacOS/BareRemote"
cp Resources/Info.plist "$staged_app/Contents/Info.plist"
cp ../LICENSE "$staged_app/Contents/Resources/LICENSE.txt"
xcrun swift Resources/make-icon.swift "$staging/AppIcon.iconset"
iconutil -c icns "$staging/AppIcon.iconset" -o "$staged_app/Contents/Resources/AppIcon.icns"
if test "${BAREREMOTE_SIGN_IDENTITY+x}" = x; then
    signing_identity=$BAREREMOTE_SIGN_IDENTITY
elif test -f ../.keys/macos-signing-identity; then
    signing_identity=$(cat ../.keys/macos-signing-identity)
else
    signing_identity=-
fi
if test -z "$signing_identity"; then
    echo 'Signing identity must not be empty.' >&2
    exit 2
fi
if test "$signing_identity" = -; then
    echo 'Warning: ad hoc signing can make macOS local-network permissions unreliable; set BAREREMOTE_SIGN_IDENTITY to an Apple-issued identity.' >&2
fi
# The built-in desktop remote needs no extension. WidgetKit is experimental
# until the gallery reliably discovers local development installations.
case "${BAREREMOTE_INCLUDE_WIDGETKIT:-0}" in
    0) ;;
    1)
        if test "${1:-}" = --universal; then
            ./WidgetExtension/build.sh "$staged_app/Contents/PlugIns/BareRemoteWidget.appex" "$signing_identity" --universal
        else
            ./WidgetExtension/build.sh "$staged_app/Contents/PlugIns/BareRemoteWidget.appex" "$signing_identity"
        fi
        ;;
    *) echo 'BAREREMOTE_INCLUDE_WIDGETKIT must be 0 or 1.' >&2; exit 2 ;;
esac
codesign --force --sign "$signing_identity" --identifier uy.nico.bareremote.macos "$staged_app"
codesign --verify --strict "$staged_app"
ditto -c -k --sequesterRsrc --keepParent "$staged_app" "$staged_archive"

# Replace signed code with new inodes; never overwrite a running app's files.
if test -e "$app" || test -L "$app"; then had_app=1; fi
if test -e "$archive" || test -L "$archive"; then had_archive=1; fi
install_started=1
if test "$had_app" -eq 1; then mv "$app" "$staging/previous.app"; fi
if test "$had_archive" -eq 1; then mv "$archive" "$staging/previous.zip"; fi
mv "$staged_app" "$app"
mv "$staged_archive" "$archive"
completed=1
echo "$app"
