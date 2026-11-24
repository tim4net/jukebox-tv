#!/usr/bin/env bash
# Upload built Jukebox APKs + latest.json to Cloudflare R2 for the in-app self-updater.
#
# Usage: scripts/upload-to-r2.sh [--dry-run] <apk_dir> <manifest.json>
#
# <apk_dir> must contain the APKs named in <manifest.json> (as produced by
# :smarttubetv:assembleStjukeboxRelease + scripts/make-update-manifest.sh).
# <manifest.json> is the latest.json for this build.
#
# Order matters (see docs/UPDATES.md): both APKs are uploaded first, each one
# verified against the manifest (sha256 + size, by downloading it back), and
# install/latest.json is uploaded LAST so clients never see a manifest whose
# APKs are missing or half-uploaded.
#
# Env:
#   JUKEBOX_R2_ACCOUNT_ID        Cloudflare account ID (S3 endpoint host)
#   JUKEBOX_R2_ACCESS_KEY_ID     scoped R2 API token: access key ID
#   JUKEBOX_R2_SECRET_ACCESS_KEY scoped R2 API token: secret access key
#   JUKEBOX_R2_BUCKET            override (default jukebox-apks)
#   JUKEBOX_R2_BASE_URL          public base URL the manifest was built against
#                                (default https://jukebox.tfour.net/install)
#
# The token needs Object Read & Write on the bucket only. With --dry-run no
# credentials and no network are needed: it checks the local APKs against the
# manifest and prints the exact upload commands it would run.
set -euo pipefail

DRY_RUN=false
if [[ "${1:-}" == "--dry-run" ]]; then
    DRY_RUN=true
    shift
fi

if [[ $# -ne 2 ]]; then
    echo "usage: $0 [--dry-run] <apk_dir> <manifest.json>" >&2
    exit 2
fi

APK_DIR="$1"
MANIFEST="$2"
BUCKET="${JUKEBOX_R2_BUCKET:-jukebox-apks}"
BASE_URL="${JUKEBOX_R2_BASE_URL:-https://jukebox.tfour.net/install}"

for cmd in jq sha256sum stat; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: missing required command: $cmd" >&2; exit 1; }
done

if [[ ! -d "$APK_DIR" ]]; then
    echo "ERROR: apk dir not found: $APK_DIR" >&2
    exit 1
fi
if [[ ! -f "$MANIFEST" ]]; then
    echo "ERROR: manifest not found: $MANIFEST" >&2
    exit 1
fi

if [[ "$DRY_RUN" == "false" ]]; then
    for var in JUKEBOX_R2_ACCOUNT_ID JUKEBOX_R2_ACCESS_KEY_ID JUKEBOX_R2_SECRET_ACCESS_KEY; do
        if [[ -z "${!var:-}" ]]; then
            echo "ERROR: $var is not set (scoped R2 token from Bou — see docs/UPDATES.md)" >&2
            exit 1
        fi
    done
    command -v aws >/dev/null 2>&1 || { echo "ERROR: missing required command: aws" >&2; exit 1; }
    ENDPOINT="https://${JUKEBOX_R2_ACCOUNT_ID}.r2.cloudflarestorage.com"
    export AWS_ACCESS_KEY_ID="$JUKEBOX_R2_ACCESS_KEY_ID"
    export AWS_SECRET_ACCESS_KEY="$JUKEBOX_R2_SECRET_ACCESS_KEY"
    export AWS_DEFAULT_REGION=auto
    export AWS_EC2_METADATA_DISABLED=true
fi

VERSION_CODE="$(jq -r '.jukebox.versionCode' "$MANIFEST")"
if ! [[ "$VERSION_CODE" =~ ^[0-9]+$ ]]; then
    echo "ERROR: manifest has no numeric .jukebox.versionCode: $MANIFEST" >&2
    exit 1
fi

# Monotonic guard: the TV's updater silently ignores a manifest whose
# versionCode is not strictly greater than the installed one, so refuse to
# publish over a live manifest with an equal-or-lower versionCode. The live
# manifest may predate the `.jukebox` block, so fall back to the newest
# version entry. A missing live manifest (404: first publish) proceeds with
# a warning; any other fetch failure fails closed. Skipped for --dry-run
# (no network by design).
if [[ "$DRY_RUN" == "false" ]]; then
    command -v curl >/dev/null 2>&1 || { echo "ERROR: missing required command: curl" >&2; exit 1; }
    LIVE_JSON="$(mktemp)"
    HTTP_CODE="$(curl -sS -o "$LIVE_JSON" -w '%{http_code}' --max-time 20 "$BASE_URL/latest.json")" || HTTP_CODE="000"
    case "$HTTP_CODE" in
        200)
            PUBLISHED_CODE="$(jq -r '(.jukebox.versionCode // ([to_entries[] | select(.key != "package") | .value.versionCode // empty] | max)) // empty' "$LIVE_JSON")"
            rm -f "$LIVE_JSON"
            if ! [[ "$PUBLISHED_CODE" =~ ^[0-9]+$ ]]; then
                echo "ERROR: published $BASE_URL/latest.json has no numeric versionCode" >&2
                exit 1
            fi
            if (( VERSION_CODE <= PUBLISHED_CODE )); then
                echo "ERROR: refusing to publish versionCode $VERSION_CODE: live manifest is already $PUBLISHED_CODE (versionCode must strictly increase)" >&2
                exit 1
            fi
            echo "live manifest is versionCode $PUBLISHED_CODE; publishing $VERSION_CODE"
            ;;
        404)
            rm -f "$LIVE_JSON"
            echo "warning: no live manifest at $BASE_URL/latest.json (first publish?) — proceeding"
            ;;
        *)
            rm -f "$LIVE_JSON"
            echo "ERROR: could not fetch published manifest (HTTP $HTTP_CODE from $BASE_URL/latest.json) — refusing to publish" >&2
            exit 1
            ;;
    esac
fi

# s3 cp wrapper: prints the command in dry-run, runs it for real otherwise.
s3_cp() { # $1: source, $2: s3://bucket/key, $3: content-type
    if [[ "$DRY_RUN" == "true" ]]; then
        echo "[dry-run] aws s3 cp --endpoint-url \"https://<JUKEBOX_R2_ACCOUNT_ID>.r2.cloudflarestorage.com\" --content-type \"$3\" \"$1\" \"$2\""
    else
        aws s3 cp --endpoint-url "$ENDPOINT" --content-type "$3" "$1" "$2"
    fi
}

# Upload + verify one APK (v7a/v8a). $1: abi
upload_apk() {
    local abi="$1"
    local url sha size file key tmp
    url="$(jq -r ".jukebox.apks[\"$abi\"].url" "$MANIFEST")"
    sha="$(jq -r ".jukebox.apks[\"$abi\"].sha256" "$MANIFEST")"
    size="$(jq -r ".jukebox.apks[\"$abi\"].size" "$MANIFEST")"
    if [[ -z "$url" || "$url" == "null" || -z "$sha" || "$sha" == "null" || -z "$size" || "$size" == "null" ]]; then
        echo "ERROR: manifest is missing .jukebox.apks.$abi (url/sha256/size)" >&2
        exit 1
    fi
    case "$url" in
        "$BASE_URL"/*) key="${url#"$BASE_URL"/}" ;;
        *) echo "ERROR: manifest $abi URL does not start with $BASE_URL: $url" >&2; exit 1 ;;
    esac
    file="$APK_DIR/$(basename "$url")"
    if [[ ! -f "$file" ]]; then
        echo "ERROR: APK named by the manifest is missing: $file" >&2
        exit 1
    fi
    # Local integrity first: the manifest must describe the files we are about to ship.
    local local_sha local_size
    local_sha="$(sha256sum "$file" | cut -d' ' -f1)"
    local_size="$(stat -c '%s' "$file")"
    if [[ "$local_sha" != "$sha" || "$local_size" != "$size" ]]; then
        echo "ERROR: $file does not match the manifest (sha256 $local_sha vs $sha, size $local_size vs $size)" >&2
        exit 1
    fi

    echo "uploading $key ($abi, versionCode $VERSION_CODE, $size bytes)"
    s3_cp "$file" "s3://$BUCKET/$key" "application/vnd.android.package-archive"

    # Verify the object that landed in R2, before the manifest can point at it.
    if [[ "$DRY_RUN" == "true" ]]; then
        echo "[dry-run] verify s3://$BUCKET/$key by downloading it back and comparing sha256/size to the manifest"
    else
        tmp="$(mktemp)"
        trap 'rm -f "$tmp"' EXIT
        aws s3 cp --endpoint-url "$ENDPOINT" "s3://$BUCKET/$key" "$tmp" >/dev/null
        local back_sha back_size
        back_sha="$(sha256sum "$tmp" | cut -d' ' -f1)"
        back_size="$(stat -c '%s' "$tmp")"
        rm -f "$tmp"
        trap - EXIT
        if [[ "$back_sha" != "$sha" || "$back_size" != "$size" ]]; then
            echo "ERROR: uploaded $key failed verification (sha256 $back_sha vs $sha, size $back_size vs $size) — manifest NOT uploaded" >&2
            exit 1
        fi
        echo "verified s3://$BUCKET/$key (sha256 + size match the manifest)"
    fi
}

# APKs first, in ABI order; manifest last.
upload_apk armeabi-v7a
upload_apk arm64-v8a

echo "uploading install/latest.json (last)"
s3_cp "$MANIFEST" "s3://$BUCKET/install/latest.json" "application/json"

echo "done: $BASE_URL/latest.json now points at versionCode $VERSION_CODE"
