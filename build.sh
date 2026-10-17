#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

if test -n "${BAREREMOTE_SDK:-}"; then
    SDK=$BAREREMOTE_SDK
elif test -n "${ANDROID_HOME:-}"; then
    SDK=$ANDROID_HOME
elif test -n "${ANDROID_SDK_ROOT:-}"; then
    SDK=$ANDROID_SDK_ROOT
elif test -d ../.tools/android-sdk; then
    SDK=../.tools/android-sdk
else
    echo 'Android SDK not found. Set BAREREMOTE_SDK or ANDROID_HOME to an SDK containing platforms;android-35 and build-tools;35.0.0.' >&2
    exit 1
fi
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
BUILD=build

for tool in java javac jar keytool zip; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Missing build tool: $tool (JDK 17+ and zip are required)." >&2; exit 1; }
done
javac_version=$(javac -version 2>&1)
javac_major=$(printf '%s\n' "$javac_version" | awk '{ split($2, parts, /[.-]/); print parts[1] }')
case "$javac_major" in
    ''|*[!0-9]*) echo "Cannot determine JDK version: $javac_version" >&2; exit 1 ;;
esac
test "$javac_major" -ge 17 || { echo 'JDK 17 or newer is required.' >&2; exit 1; }
for tool in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do
    test -x "$tool" || { echo "Missing Android SDK tool: $tool" >&2; exit 1; }
done
test -f "$ANDROID_JAR" || { echo "Missing Android API 35: $ANDROID_JAR" >&2; exit 1; }

mkdir -p "$BUILD/generated" "$BUILD/classes" "$BUILD/dex" .keys
find "$BUILD/generated" "$BUILD/classes" "$BUILD/dex" -type f -exec rm -f {} +
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/resources.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" \
    --manifest app/src/main/AndroidManifest.xml \
    --java "$BUILD/generated" --min-sdk-version 26 --target-sdk-version 35 \
    --version-code 4 --version-name 0.2.0-prototype \
    -o "$BUILD/resources.apk" "$BUILD/resources.zip"

find app/src/main/java "$BUILD/generated" -name '*.java' > "$BUILD/sources.list"
javac --release 8 -Xlint:all,-options -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" @"$BUILD/sources.list"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" "$BUILD/classes.jar"
cp "$BUILD/resources.apk" "$BUILD/unsigned.apk"
zip -q -0 -j "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex"
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

if ! test -f .keys/bareremote.p12; then
    umask 077
    if ! test -f .keys/password; then
        command -v openssl >/dev/null 2>&1 || { echo 'openssl is required to generate the local signing-key password.' >&2; exit 1; }
        openssl rand -hex 24 > .keys/password
    fi
    keytool -genkeypair -noprompt -storetype PKCS12 -keystore .keys/bareremote.p12 \
        -storepass:file .keys/password -keypass:file .keys/password \
        -alias bareremote -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=BareRemote'
fi
test -s .keys/password || { echo 'The existing signing key needs its original .keys/password file. Restore that file; do not replace the key.' >&2; exit 1; }
"$BT/apksigner" sign --ks .keys/bareremote.p12 --ks-key-alias bareremote \
    --ks-pass file:.keys/password \
    --out "$BUILD/BareRemote.apk" "$BUILD/aligned.apk"
"$BT/apksigner" verify "$BUILD/BareRemote.apk"
wc -c "$BUILD/BareRemote.apk"
