#!/bin/sh
set -eu

# Supply your own signing identity. Never print or commit credentials.
: "${TWENTYFOURPI_KEYSTORE_PATH:?Set the path to your own keystore}"
: "${TWENTYFOURPI_STORE_PASSWORD:?Set the keystore password}"
: "${TWENTYFOURPI_KEY_ALIAS:?Set the signing alias}"
: "${TWENTYFOURPI_KEY_PASSWORD:?Set the signing key password}"
test -f "$TWENTYFOURPI_KEYSTORE_PATH"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${sdk_root:?Set ANDROID_HOME or ANDROID_SDK_ROOT}"
build_tools="$sdk_root/build-tools/35.0.0"
test -x "$build_tools/apksigner"

# Keep tests separate from memory-intensive lint/R8 tasks.
./gradlew testDebugUnitTest --max-workers=2
./gradlew compileDebugAndroidTestKotlin lintDebug assembleRelease assembleObserve --max-workers=2

for apk in app/build/outputs/apk/release/app-release.apk app/build/outputs/apk/observe/app-observe.apk; do
  "$build_tools/apksigner" verify --verbose --print-certs "$apk"
done
