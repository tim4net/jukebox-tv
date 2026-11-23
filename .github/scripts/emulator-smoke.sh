#!/usr/bin/env bash
set -euxo pipefail

# adb calls can wedge against a busy emulator: run 44760700925 hung 30+ min
# inside the second `adb logcat -d` (after the VIEW intent had the app
# loading a video), because the dump is the whole unbounded buffer and the
# `adb logcat -c` before launch had failed to clear it. Fail fast instead:
# every adb call gets a timeout, and logcat dumps are bounded to the last
# 2000 lines — a crash's FATAL EXCEPTION is always in the recent log.
ADB_TIMEOUT=120
adb_t() { timeout "$ADB_TIMEOUT" adb "$@"; }

bash ./gradlew --no-daemon :smarttubetv:assembleStjukeboxDebug

shopt -s nullglob
APK_FILES=(smarttubetv/build/outputs/apk/stjukebox/debug/Jukebox_*_universal.apk)
if [ "${#APK_FILES[@]}" -ne 1 ]; then
  echo "expected exactly one Jukebox_*_universal.apk, found ${#APK_FILES[@]}: ${APK_FILES[*]}"
  exit 1
fi
APK="${APK_FILES[0]}"
# The AVD cache restores userdata, so a previous run's install of the app may
# still be present — signed with that runner's throwaway debug keystore, which
# differs from this run's. Uninstall first so the reinstall can't die with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE (signature mismatch).
adb_t uninstall net.tfour.jukebox || true
adb_t install -r "$APK"

adb_t logcat -c || true
# Leanback launch (Fire TV style); fall back to the phone launcher category
adb_t shell monkey -p net.tfour.jukebox -c android.intent.category.LEANBACK_LAUNCHER 1 \
  || adb_t shell monkey -p net.tfour.jukebox -c android.intent.category.LAUNCHER 1

sleep 20
test -n "$(adb_t shell pidof net.tfour.jukebox)" || { echo "app process not running after launch"; adb_t logcat -d -t 2000; exit 1; }

# Dump logcat (bounded) before grepping: with pipefail, `adb logcat -d | grep -q`
# can fail via SIGPIPE when grep -q matches early and silently skip the FATAL check.
log="$(adb_t logcat -d -t 2000)"
if grep -q "FATAL EXCEPTION" <<<"$log"; then
  echo "FATAL EXCEPTION found in logcat"; printf '%s\n' "$log"; exit 1
fi

# VIEW intent with a youtube watch URL (the share/"open with" path). -p is not
# supported by `am start` on api 24, so fall back to the explicit component
# (SplashActivity owns the VIEW intent-filter for youtube.com links).
adb_t shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=dQw4w9WgXcQ" -p net.tfour.jukebox \
  || adb_t shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=dQw4w9WgXcQ" \
       -n net.tfour.jukebox/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity
sleep 10
test -n "$(adb_t shell pidof net.tfour.jukebox)" || { echo "app process died after VIEW intent"; adb_t logcat -d -t 2000; exit 1; }
log="$(adb_t logcat -d -t 2000)"
if grep -q "FATAL EXCEPTION" <<<"$log"; then
  echo "FATAL EXCEPTION found in logcat after VIEW intent"; printf '%s\n' "$log"; exit 1
fi
echo "emulator smoke test passed"
