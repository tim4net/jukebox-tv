#!/usr/bin/env bash
# Generate the Jukebox self-update manifest (latest.json) from built APKs.
#
# Usage: scripts/make-update-manifest.sh <apk_dir> <out.json>
#
# <apk_dir> must contain exactly one Jukebox_*_armeabi-v7a.apk and one
# Jukebox_*_arm64-v8a.apk (as produced by :smarttubetv:assembleStjukeboxRelease).
# The output is deterministic: same APKs + same env => byte-identical JSON.
#
# Env:
#   ANDROID_HOME              SDK root (used to locate aapt2 / apkanalyzer)
#   JUKEBOX_INSTALL_BASE_URL  override (default https://jukebox.tfour.net/install)
#   JUKEBOX_CHANGELOG         newline-separated changelog lines (default: one line
#                             "Jukebox <versionName>")
#   SOURCE_DATE_EPOCH         epoch for "builtAt" (default: mtime of the v7a APK,
#                             so rebuilding from the same artifacts is reproducible)
#   JUKEBOX_COMMIT            commit for "commit" (default: git rev-parse HEAD,
#                             fallback "unknown")
#
# Manifest format (SmartTube-compatible; see docs/UPDATES.md):
#   - "package": {"downloadUrlList": [v7a url], "downloadUrlList_arm64-v8a": [v8a url]}
#     (parsed by SharedModules/appupdatechecker2 AppVersionChecker: it prefers
#     downloadUrlList_<primary abi>, then downloadUrlList)
#   - "<versionName>": {"versionCode": N, "changelog": [...]}
#   - "jukebox": our extra metadata (sha256/size per ABI etc.). NOTE: AppVersionChecker
#     treats every non-"package" key as a version entry keyed by versionCode; because
#     "jukebox" is emitted FIRST and carries the same versionCode, the real
#     "<versionName>" entry (emitted last) wins, so the updater reports the real name.
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "usage: $0 <apk_dir> <out.json>" >&2
    exit 2
fi

APK_DIR="$1"
OUT_JSON="$2"

BASE_URL="${JUKEBOX_INSTALL_BASE_URL:-https://jukebox.tfour.net/install}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

if [[ ! -d "$APK_DIR" ]]; then
    echo "ERROR: apk dir not found: $APK_DIR" >&2
    exit 1
fi

find_apk() { # $1: abi
    local matches=()
    mapfile -t matches < <(ls -1 "$APK_DIR" | grep -E "^Jukebox.*_$1\.apk$" | sort || true)
    if [[ ${#matches[@]} -eq 0 ]]; then
        echo "ERROR: no Jukebox_*_$1.apk found in $APK_DIR" >&2
        exit 1
    fi
    if [[ ${#matches[@]} -gt 1 ]]; then
        echo "ERROR: multiple $1 APKs found in $APK_DIR: ${matches[*]}" >&2
        exit 1
    fi
    printf '%s\n' "${matches[0]}"
}

V7A_APK="$(find_apk armeabi-v7a)"
V8A_APK="$(find_apk arm64-v8a)"

read_version() { # $1: apk, $2: aapt2|versionCode|versionName
    local apk="$1" field="$2" out=""
    local aapt2="$ANDROID_HOME/build-tools/30.0.3/aapt2"
    if [[ -x "$aapt2" ]]; then
        out="$( "$aapt2" dump badging "$APK_DIR/$apk" 2>/dev/null | sed -n '1s/^package:.* '"$field"'='"'"'\([^'"'"']*\)'"'"'.*$/\1/p' )"
    fi
    if [[ -z "$out" ]]; then
        local apkanalyzer="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
        if [[ -x "$apkanalyzer" ]]; then
            case "$field" in
                versionCode) out="$( "$apkanalyzer" manifest version-code --file "$APK_DIR/$apk" 2>/dev/null | tr -d '[:space:]' )" ;;
                versionName) out="$( "$apkanalyzer" manifest version-name --file "$APK_DIR/$apk" 2>/dev/null | tr -d '[:space:]' )" ;;
            esac
        fi
    fi
    if [[ -z "$out" ]]; then
        echo "ERROR: could not read $field from $apk (need aapt2 in \$ANDROID_HOME/build-tools/ or apkanalyzer in cmdline-tools)" >&2
        exit 1
    fi
    printf '%s\n' "$out"
}

VERSION_CODE_V7A="$(read_version "$V7A_APK" versionCode)"
VERSION_NAME_V7A="$(read_version "$V7A_APK" versionName)"
VERSION_CODE_V8A="$(read_version "$V8A_APK" versionCode)"
VERSION_NAME_V8A="$(read_version "$V8A_APK" versionName)"

if [[ "$VERSION_CODE_V7A" != "$VERSION_CODE_V8A" || "$VERSION_NAME_V7A" != "$VERSION_NAME_V8A" ]]; then
    echo "ERROR: ABI APKs disagree on version ($VERSION_CODE_V7A/$VERSION_NAME_V7A vs $VERSION_CODE_V8A/$VERSION_NAME_V8A)" >&2
    exit 1
fi
VERSION_CODE="$VERSION_CODE_V7A"
VERSION_NAME="$VERSION_NAME_V7A"
if ! [[ "$VERSION_CODE" =~ ^[0-9]+$ ]]; then
    echo "ERROR: versionCode is not an integer: $VERSION_CODE" >&2
    exit 1
fi

apk_info() { # $1: apk file -> url basename, sha256, size
    local apk="$1" sha size
    sha="$( sha256sum "$APK_DIR/$apk" | cut -d' ' -f1 )"
    size="$( stat -c '%s' "$APK_DIR/$apk" )"
    printf '%s %s\n' "$sha" "$size"
}

# Canonical R2/APK names: Jukebox_<versionName>_<abi>.apk (no flavor segment)
V7A_URL="$BASE_URL/apk/$VERSION_CODE/Jukebox_${VERSION_NAME}_armeabi-v7a.apk"
V8A_URL="$BASE_URL/apk/$VERSION_CODE/Jukebox_${VERSION_NAME}_arm64-v8a.apk"

read -r V7A_SHA V7A_SIZE < <(apk_info "$V7A_APK")
read -r V8A_SHA V8A_SIZE < <(apk_info "$V8A_APK")

if [[ -n "${SOURCE_DATE_EPOCH:-}" ]]; then
    BUILT_AT="$( date -u -d "@$SOURCE_DATE_EPOCH" '+%Y-%m-%dT%H:%M:%SZ' )"
else
    BUILT_AT="$( date -u -d "@$( stat -c '%Y' "$APK_DIR/$V7A_APK" )" '+%Y-%m-%dT%H:%M:%SZ' )"
fi

if [[ -n "${JUKEBOX_COMMIT:-}" ]]; then
    COMMIT="$JUKEBOX_COMMIT"
elif git -C "$(dirname "$0")/.." rev-parse --verify HEAD >/dev/null 2>&1; then
    COMMIT="$( git -C "$(dirname "$0")/.." rev-parse HEAD )"
else
    COMMIT="unknown"
fi

if [[ -n "${JUKEBOX_CHANGELOG:-}" ]]; then
    CHANGELOG_JSON="$( printf '%s\n' "$JUKEBOX_CHANGELOG" | jq -Rn '[inputs]' )"
else
    CHANGELOG_JSON="$( printf 'Jukebox %s' "$VERSION_NAME" | jq -Rn '[input]' )"
fi

# Key order matters: "jukebox" FIRST (see script header), then "package", then the
# real "<versionName>" entry. jq emits objects in insertion order => deterministic.
jq -n \
    --arg versionCode "$VERSION_CODE" \
    --arg versionName "$VERSION_NAME" \
    --arg builtAt "$BUILT_AT" \
    --arg commit "$COMMIT" \
    --arg v7aUrl "$V7A_URL" --arg v7aSha "$V7A_SHA" --argjson v7aSize "$V7A_SIZE" \
    --arg v8aUrl "$V8A_URL" --arg v8aSha "$V8A_SHA" --argjson v8aSize "$V8A_SIZE" \
    --argjson changelog "$CHANGELOG_JSON" \
    '{
        jukebox: {
            versionCode: ($versionCode | tonumber),
            versionName: $versionName,
            builtAt: $builtAt,
            commit: $commit,
            apks: {
                "armeabi-v7a": {url: $v7aUrl, sha256: $v7aSha, size: $v7aSize},
                "arm64-v8a":   {url: $v8aUrl, sha256: $v8aSha, size: $v8aSize}
            }
        },
        package: {
            downloadUrlList: [$v7aUrl],
            "downloadUrlList_arm64-v8a": [$v8aUrl]
        },
        ($versionName): {
            versionCode: ($versionCode | tonumber),
            changelog: $changelog
        }
    }' > "$OUT_JSON"

echo "wrote $OUT_JSON:"
cat "$OUT_JSON"
