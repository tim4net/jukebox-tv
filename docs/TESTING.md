# Testing Jukebox

Test command (the one CI and the merge script use):

```bash
JAVA_HOME=$HOME/jdk/jdk-17.0.21+9 ANDROID_HOME=$HOME/android-sdk \
  bash ./gradlew --no-daemon :smarttubetv:testStjukeboxDebugUnitTest :common:testStjukeboxDebugUnitTest
```

All Jukebox tests live in `src/testStjukebox/` source sets, so they run only for our flavor and
never affect upstream flavors. There is also an instrumented-ish `emulator-smoke` job in
`.github/workflows/build-apks.yml` (see bottom).

## Flavor quirks needed to make tests run (JDK 17)

- Robolectric is pinned to 4.6.1 in `SharedModules/constants.gradle` (submodule, cannot edit).
  On JDK 17 its bundled ASM fails with `Unsupported class file major version 61` on *every*
  test (including upstream's). Fix: `testStjukeboxImplementation
  'org.robolectric:robolectric:4.11.1'` in `smarttubetv/build.gradle` and `common/build.gradle` —
  only our flavor's unit tests get the newer Robolectric.
- `smarttubetv` depends on `conscrypt-android`, whose JNI library cannot load on a JVM. Robolectric
  installs `org.conscrypt.OpenSSLProvider` during setup, so the `conscrypt-android` artifacts are
  filtered out of the stjukebox unit-test classpath (`testOptions.unitTests.all` in
  `smarttubetv/build.gradle`); Robolectric's own JVM-friendly `conscrypt-openjdk-uber` remains.
- No upstream tests had to be excluded: with the two fixes above, the existing upstream tests in
  `common/src/test` (SuggestionsControllerTest, ScreensaverManagerTest) pass.

## Unit tests

### smarttubetv/src/testStjukebox/java/net/tfour/jukebox/

| Test | Covers |
| --- | --- |
| `JukeboxFlavorTest.applicationIdIsJukebox` | the stjukebox flavor really builds with applicationId `net.tfour.jukebox` (guards against flavor regressions that would break updates/launcher intents) |
| `JukeboxFlavorTest.appNameIsJukebox` | the merged resources resolve `app_name` to "Jukebox" (flavor res overlay works) |
| `PlaybackStartSmokeTest.openVideoStartsPlaybackActivity` | player start path without network: `PlaybackPresenter.instance()` is constructible, `openVideo(videoId)` does not throw with no player view (video is queued as pending in `VideoLoaderController`), the current video is set, and `ViewManager` dispatches a launch intent for the **real** `PlaybackActivity` (the same registration `MainApplication` performs). Static singletons (`ViewManager`, `PlaybackPresenter`) are reset first because Robolectric gives each test a fresh Application while singletons keep the stale one. |

### common/src/testStjukebox/java/

| Test | Covers |
| --- | --- |
| `net.tfour.jukebox.UpdateUrlsTest.updateUrlsContainOnlyJukeboxManifest` | the self-updater URL list contains exactly `https://jukebox.tfour.net/install/latest.json` — no upstream SmartTube manifests can leak into Jukebox builds |
| `...utils.IntentExtractorTest` (10 tests) | playback URL handling: youtube `watch?v=`, `m.youtube.com`, `youtu.be/<id>`, `shorts/<id>`, `vnd.youtube://` scheme, `watch_videos?video_ids=` (first id), `ACTION_SEND` share with the URL in `EXTRA_TEXT`, negative cases (search/channel/playlist URLs, null intent yield no video id), and search-query extraction. This is the helper the app uses for all `ACTION_VIEW`/`ACTION_SEND` youtube links (same code path the emulator VIEW-intent test exercises end-to-end). |

### Upstream tests that run in the same task (kept green)

| Test | Covers |
| --- | --- |
| `common/src/test/.../SuggestionsControllerTest.mixAutoplayPrefersInMixItemOverSection` | autoplay queue prefers the in-mix next item over section suggestions |
| `common/src/test/.../ScreensaverManagerTest` (10 tests) | screensaver dim/timeout state machine against the real `MotherActivity` lifecycle |

## CI emulator smoke test (`.github/workflows/build-apks.yml`, job `emulator-smoke`)

Android 7.0 (api-level 24, x86_64) emulator — same Android generation as Fire OS 6:

1. `assembleStjukeboxDebug` (splits include `x86`) and `adb install -r` the x86 APK
2. cold launch via `monkey -p net.tfour.jukebox -c android.intent.category.LEANBACK_LAUNCHER`
   (falls back to `LAUNCHER`)
3. wait 20 s, assert `adb shell pidof net.tfour.jukebox` is alive and logcat has no
   `FATAL EXCEPTION`
4. `am start -a android.intent.action.VIEW -d https://www.youtube.com/watch?v=dQw4w9WgXcQ`
   targeting the app (explicit `SplashActivity` component on api 24, which lacks `am start -p`),
   assert the process is still alive (no network assertion — this only checks the intent path
   does not crash the app)

## What is NOT covered (honest list)

- **Actual YouTube playback / any network**: no unit test hits YouTube. Stream resolution,
  buffering, format selection, and the whole `youtubeapi`/MediaServiceCore stack are untested
  here (upstream does not ship unit tests for it either).
- **Fire TV hardware**: no Fire OS device in CI. The emulator is stock Android 7 x86_64 — no
  Amazon leanback quirks, no Fire TV remote, DIAL, or HDMI/CEC behavior; `auto frame rate`,
  `PIP`, and background playback paths are untested.
- **ARM ABIs (armeabi-v7a, arm64-v8a)**: built by every release build (and both APKs must exist
  for the update manifest), but never executed anywhere — CI runs x86 only.
- **UI flows**: no test inflates the leanback browse/search/playback fragments or drives focus
  navigation; Robolectric tests here stop at presenters/intents. The emulator job only proves
  "launches and stays alive".
- **Self-update E2E**: the manifest generator is covered only by manual/deterministic runs
  (`scripts/make-update-manifest.sh` has no unit test), and no test downloads an APK and installs
  it over a previous version (signature-continuity and `versionCode`-increase rules are enforced
  by process, see docs/UPDATES.md).
- **The Jukebox room protocol** (see docs/JUKEBOX-MODE.md): not implemented yet, so obviously
  untested.

## Weekly upstream merge cron (box)

`scripts/upstream-merge-cron.sh` is the cron entry point for the weekly gated upstream merge
(`scripts/upstream-merge.sh`). It sets PATH/JAVA_HOME/ANDROID_HOME (cron has no environment),
cds to the repo root, prevents overlapping runs with `flock` on
`.git/upstream-merge.lock` (the same lock file `scripts/upstream-merge.sh` uses,
so cron and manual runs exclude each other), and appends the whole run (with a
date header) to `upstream-merge.log` in the repo root.

Box crontab — runs Mondays 03:00 America/Chicago:

```cron
CRON_TZ=America/Chicago
0 3 * * 1 $HOME/src/jukebox-tv/scripts/upstream-merge-cron.sh
```
