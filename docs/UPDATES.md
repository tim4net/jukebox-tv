# Jukebox self-update system

How the built-in SmartTube updater is repointed at our own infrastructure, and the rules that
keep in-app updates working. The updater itself is upstream code
(`SharedModules/appupdatechecker2`, parser in `AppVersionChecker.java`); we only control the URL
list and the manifest we serve.

## Where the app looks for updates

`common/src/stjukebox/res/values/update_urls.xml` (flavor-specific, so only Jukebox builds see it):

```xml
<string-array name="update_urls">
    <item>https://jukebox.tfour.net/install/latest.json</item>
</string-array>
```

The checker tries the URLs in order and uses the first one that responds; we list exactly one.
Upstream flavors (`ststable`, `stbeta`) keep pointing at the SmartTube GitHub manifests — we never
rewrite their entries.

## Manifest format (`latest.json`)

SmartTube-compatible keys plus a `jukebox` extension block. The parser
(`AppVersionChecker.triggerFromJson`) needs:

- `package.downloadUrlList` — array; the **armeabi-v7a** APK URL (default/fallback)
- `package.downloadUrlList_arm64-v8a` — array; the **arm64-v8a** APK URL (the checker prefers
  `downloadUrlList_<primary ABI of the device>` when present)
- every other top-level key is treated as a version entry: `"<versionName>"` with
  `"versionCode"` (integer, required) and optional `"changelog"` / `"changelog_<lang>"` arrays.
  The newest `versionCode` wins; the changelog shown is everything newer than the installed
  `versionCode`.

The `jukebox` key is our metadata for the https://jukebox.tfour.net/install page and tooling. The
parser happens to digest it fine as a "version" entry because it carries a `versionCode`; it is
emitted **first** so that the real `"<versionName>"` entry (same `versionCode`) overwrites it in
the checker's internal map and the dialog shows the real version name.

Example (produced by `scripts/make-update-manifest.sh`):

```json
{
  "jukebox": {
    "versionCode": 2501,
    "versionName": "32.68",
    "builtAt": "2026-10-22T12:00:00Z",
    "commit": "3edc9d5750f9a5ac31e5cd78ee1a1f6ffe5ba111",
    "apks": {
      "armeabi-v7a": {
        "url": "https://jukebox.tfour.net/install/apk/2501/Jukebox_32.68_armeabi-v7a.apk",
        "sha256": "3db46604…",
        "size": 24794675
      },
      "arm64-v8a": {
        "url": "https://jukebox.tfour.net/install/apk/2501/Jukebox_32.68_arm64-v8a.apk",
        "sha256": "0f30d17f…",
        "size": 26008340
      }
    }
  },
  "package": {
    "downloadUrlList": ["https://jukebox.tfour.net/install/apk/2501/Jukebox_32.68_armeabi-v7a.apk"],
    "downloadUrlList_arm64-v8a": ["https://jukebox.tfour.net/install/apk/2501/Jukebox_32.68_arm64-v8a.apk"]
  },
  "32.68": {
    "versionCode": 2501,
    "changelog": ["Jukebox 32.68"]
  }
}
```

## R2 layout (bucket `jukebox-apks`)

| key | content |
| --- | --- |
| `apk/<versionCode>/Jukebox_<versionName>_<abi>.apk` | the release APKs (the build output name for the stjukebox flavor: `Jukebox_<version>_<abi>.apk`, no flavor segment, so no rename on upload) |
| `install/latest.json` | the manifest above |

`https://jukebox.tfour.net/install/...` maps to `install/...`, everything else under
`/install/apk/...` maps to `apk/...`.

## Upload ordering: APKs first, manifest last

Clients of every kind (app updater, /install page, curl) must never see a `latest.json` whose APK
URLs 404 or point at half-uploaded objects. So: upload **both APKs first**, verify them
(sha256/size against the manifest), and **upload `install/latest.json` last**.
`scripts/upload-to-r2.sh` (called by `.github/workflows/build-apks.yml`) does exactly this: it
checks the local APKs against the manifest, uploads each APK, downloads it back from R2 and
re-verifies sha256/size, and only then uploads the manifest. `bash scripts/upload-to-r2.sh
--dry-run <apk_dir> <manifest>` prints the upload commands without needing credentials.

## R2 credentials

The upload needs a **scoped R2 API token from Bou** (Object Read & Write on the `jukebox-apks`
bucket only), stored as three repo secrets (landed 2026-11-19, verified via `gh secret list`):

| secret | value |
| --- | --- |
| `JUKEBOX_R2_ACCOUNT_ID` | Cloudflare account ID (builds the S3 endpoint `https://<account>.r2.cloudflarestorage.com`) |
| `JUKEBOX_R2_ACCESS_KEY_ID` | the token's access key ID |
| `JUKEBOX_R2_SECRET_ACCESS_KEY` | the token's secret access key |

Until those exist, publish runs **warn and skip the R2 upload instead of failing** — the GitHub
Release and `latest.json` artifact below still publish, so merges are never blocked on the token
(that fallback also covers the secrets ever being removed). But note the TV itself reads
`latest.json` from R2 (`jukebox.tfour.net`), not from GitHub, so
**if the token is ever missing, no update reaches the Fire TV**; the release artifact is the
stopgap (manual upload from the release page).

## GitHub Releases

- `v*` tags publish a release on that tag (APKs + `latest.json` attached).
- Every push to `jukebox-p0-tv` publishes the rolling **`p0-tv-latest`** release (same files
  attached). The workflow force-moves the `p0-tv-latest` tag to the new merge first, then
  updates the release — the release page always shows the newest merged TV build, and the
  manifest's `commit` field says exactly which commit it is.

## Signing rules (critical)

- **Every published build must be signed with the single Jukebox release key.** Android refuses
  to install an update over an existing app unless the signatures match, so a differently-signed
  "update" bricks in-app updates (users would have to uninstall first and lose settings).
- The key lives in Bitwarden, item **"Jukebox TV APK signing key (jukebox-tv)"**; the box's copy
  is `~/.config/jukebox-tv/keystore.properties` (gitignored; a symlink at the repo root exists on
  dev boxes). CI gets it via the `JUKEBOX_KEYSTORE_*` secrets; the workflow **fails on tags,
  master and jukebox-p0-tv when the secrets are missing** and only produces a publishable
  `latest.json` on those runs. Branch builds without secrets fall back to the debug key and are marked
  `jukebox-apks-unsigned-dev` — they must never be published.
- **`versionCode` must strictly increase** for every published manifest. The updater compares the
  installed `versionCode` to the newest entry and offers the update only when the manifest's is
  higher; equal or lower is silently ignored ("Living in the future…"). The base `versionCode`
  lives in `gradle.properties` (`jukeboxVersionCode`, currently 2501) and `smarttubetv/build.gradle`
  reads it (overridable with `-PjukeboxVersionCode=<n>`). On every `jukebox-p0-tv` publish run CI
  publishes `max(base, live + 1)`, where *live* is the `versionCode` in the currently published
  `install/latest.json` — so two merges from the same upstream code publish 2501 then 2502
  automatically, with no manual bump. `scripts/upload-to-r2.sh` independently refuses to upload
  unless the new `versionCode` is strictly greater than the live one (a missing live manifest —
  first publish — proceeds with a warning). Tags and `master` publish the `versionCode` from the
  tree; the same guard stops a tag from ever downgrading the live manifest. On upstream merges,
  carry the `-PjukeboxVersionCode` form forward in `build.gradle` and raise `jukeboxVersionCode`
  in `gradle.properties` if upstream's new `versionCode` is higher.

## What the Fire TV user sees

Android does not allow silent installs for sideloaded apps. The updater downloads the APK with
the system `DownloadManager`, then the app fires the install intent and **Fire TV shows the
one-tap system install prompt** ("Install unknown apps" must have been allowed for Jukebox once —
the prompt usually offers to take you to the setting). So "automatic update" means
auto-download + one confirm tap, not zero-touch.

## Does the updater verify sha256?

No. As of the parser in `SharedModules/appupdatechecker2` (`AppVersionChecker.java`), the
downloaded APK is handed straight to the system installer; neither `sha256` nor `size` from the
manifest is checked (they are our extensions under `jukebox`, which the parser ignores). The
`sha256`/`size` fields are for the `/install` page and a possible future integrity check in our
fork. Signature verification *is* effectively enforced by Android itself at install time (see
above), which is why the single-key rule matters more than the hash.
