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
    "versionCode": 2458,
    "versionName": "32.68",
    "builtAt": "2026-10-22T12:00:00Z",
    "commit": "3edc9d5750f9a5ac31e5cd78ee1a1f6ffe5ba111",
    "apks": {
      "armeabi-v7a": {
        "url": "https://jukebox.tfour.net/install/apk/2458/Jukebox_32.68_armeabi-v7a.apk",
        "sha256": "3db46604…",
        "size": 24794675
      },
      "arm64-v8a": {
        "url": "https://jukebox.tfour.net/install/apk/2458/Jukebox_32.68_arm64-v8a.apk",
        "sha256": "0f30d17f…",
        "size": 26008340
      }
    }
  },
  "package": {
    "downloadUrlList": ["https://jukebox.tfour.net/install/apk/2458/Jukebox_32.68_armeabi-v7a.apk"],
    "downloadUrlList_arm64-v8a": ["https://jukebox.tfour.net/install/apk/2458/Jukebox_32.68_arm64-v8a.apk"]
  },
  "32.68": {
    "versionCode": 2458,
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
(sha256/size against the manifest), and **upload `install/latest.json` last**. The R2 upload in
`.github/workflows/build-apks.yml` is a commented placeholder that follows this order once a
scoped token exists.

## Signing rules (critical)

- **Every published build must be signed with the single Jukebox release key.** Android refuses
  to install an update over an existing app unless the signatures match, so a differently-signed
  "update" bricks in-app updates (users would have to uninstall first and lose settings).
- The key lives in Bitwarden, item **"Jukebox TV APK signing key (jukebox-tv)"**; the box's copy
  is `~/.config/jukebox-tv/keystore.properties` (gitignored; a symlink at the repo root exists on
  dev boxes). CI gets it via the `JUKEBOX_KEYSTORE_*` secrets; the workflow **fails on tags and
  master when the secrets are missing** and only produces a publishable `latest.json` on those
  runs. Branch builds without secrets fall back to the debug key and are marked
  `jukebox-apks-unsigned-dev` — they must never be published.
- **`versionCode` must strictly increase** for every published manifest. The updater compares the
  installed `versionCode` to the newest entry and offers the update only when the manifest's is
  higher; equal or lower is silently ignored ("Living in the future…"). The `versionCode` is
  taken from upstream's `smarttubetv/build.gradle`, so whenever upstream bumps theirs we inherit
  the bump — but if we ever build two releases from the same upstream code, we must bump past the
  previously published value.

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
