#!/usr/bin/env bash
# Instrumentation and screenshots run only in an isolated emulator.
set -euo pipefail
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "Refusing instrumentation fixture writes on a physical device" >&2
  exit 1
fi
mkdir -p app/build/reports/design-review
capture() {
  adb pull /sdcard/Android/data/com.twentyfourpi.lifelog/files/design-review/. app/build/reports/design-review/ >/dev/null 2>&1 || true
}
trap capture EXIT
./gradlew connectedDebugAndroidTest --max-workers=2 -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
capture
# A passing UI test without retrievable images cannot satisfy visual review.
find app/build/reports/design-review -name '*.png' -print -quit | grep -q .
