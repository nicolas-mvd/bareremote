#!/bin/sh
# Build only: test APK uses the existing local signing key; never ships in the app.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
test -d build/classes || { echo 'Build the main APK first.' >&2; exit 1; }
SDK=${BAREREMOTE_SDK:-${ANDROID_HOME:-../.tools/android-sdk}}
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
TEST_BUILD=build/android-keyboard-tests
mkdir -p "$TEST_BUILD/classes" "$TEST_BUILD/dex"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest tests/android/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 35 -o "$TEST_BUILD/resources.apk"
javac --release 8 -classpath "$ANDROID_JAR:build/classes" -d "$TEST_BUILD/classes" tests/android/KeyboardInstrumentation.java
jar cf "$TEST_BUILD/classes.jar" -C "$TEST_BUILD/classes" .
"$BT/d8" --release --min-api 26 --lib "$ANDROID_JAR" --classpath build/classes.jar \
    --output "$TEST_BUILD/dex" "$TEST_BUILD/classes.jar"
cp "$TEST_BUILD/resources.apk" "$TEST_BUILD/unsigned.apk"
zip -q -0 -j "$TEST_BUILD/unsigned.apk" "$TEST_BUILD/dex/classes.dex"
"$BT/zipalign" -f -p 4 "$TEST_BUILD/unsigned.apk" "$TEST_BUILD/aligned.apk"
"$BT/apksigner" sign --ks .keys/bareremote.p12 --ks-key-alias bareremote --ks-pass file:.keys/password \
    --out "$TEST_BUILD/KeyboardTests.apk" "$TEST_BUILD/aligned.apk"
printf '%s\n' "$TEST_BUILD/KeyboardTests.apk"
