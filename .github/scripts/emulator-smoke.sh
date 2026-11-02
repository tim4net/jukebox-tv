#!/usr/bin/env bash
set -euxo pipefail

bash ./gradlew --no-daemon :smarttubetv:assembleStjukeboxDebug

APK="$(ls smarttubetv/build/outputs/apk/stjukebox/debug/Jukebox_*_universal.apk)"
adb install -r "$APK"

adb logcat -c || true
# Leanback launch (Fire TV style); fall back to the phone launcher category
adb shell monkey -p net.tfour.jukebox -c android.intent.category.LEANBACK_LAUNCHER 1 \
  || adb shell monkey -p net.tfour.jukebox -c android.intent.category.LAUNCHER 1

sleep 20
test -n "$(adb shell pidof net.tfour.jukebox)" || { echo "app process not running after launch"; adb logcat -d; exit 1; }

# Dump logcat before grepping: with pipefail, `adb logcat -d | grep -q` can fail
# via SIGPIPE when grep -q matches early and silently skip the FATAL check.
log="$(adb logcat -d)"
if grep -q "FATAL EXCEPTION" <<<"$log"; then
  echo "FATAL EXCEPTION found in logcat"; printf '%s\n' "$log"; exit 1
fi

# VIEW intent with a youtube watch URL (the share/"open with" path). -p is not
# supported by `am start` on api 24, so fall back to the explicit component
# (SplashActivity owns the VIEW intent-filter for youtube.com links).
adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=dQw4w9WgXcQ" -p net.tfour.jukebox \
  || adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=dQw4w9WgXcQ" \
       -n net.tfour.jukebox/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity
sleep 10
test -n "$(adb shell pidof net.tfour.jukebox)" || { echo "app process died after VIEW intent"; adb logcat -d; exit 1; }
log="$(adb logcat -d)"
if grep -q "FATAL EXCEPTION" <<<"$log"; then
  echo "FATAL EXCEPTION found in logcat after VIEW intent"; printf '%s\n' "$log"; exit 1
fi
echo "emulator smoke test passed"
