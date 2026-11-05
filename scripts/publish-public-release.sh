#!/usr/bin/env bash
set -euo pipefail
: "${1:?Usage: publish-public-release.sh TAG SIGNED_DIRECTORY}"
: "${2:?Provide directory signed by sign-local-apks.py}"
release_tag="$1"
release_dir="$2"
if [[ ! "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-+.][A-Za-z0-9._-]+)?$ ]]; then
    echo 'Invalid release tag' >&2
    exit 1
fi
if [[ ! -s "$release_dir/certificate.sha256" ]]; then
    echo 'Run sign-local-apks.py before publication' >&2
    exit 1
fi
(cd "$release_dir" && sha256sum --check SHA256SUMS.txt)
: "${ANDROID_HOME:?Set ANDROID_HOME}"
certificate="$(tr -d '\r\n' < "$release_dir/certificate.sha256")"
[[ "$certificate" =~ ^[a-f0-9]{64}$ ]] || { echo 'Invalid certificate fingerprint' >&2; exit 1; }
for apk in "$release_dir"/*.apk; do
    "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$apk" | \
        grep -Fxq "Signer #1 certificate SHA-256 digest: $certificate"
done
# gh uses the author's normal browser login; relatives need no GitHub account or token.
repository='Romeo13card-sys/Luma-Music'
visibility="$(gh repo view "$repository" --json isPrivate --jq .isPrivate)"
if [[ "$visibility" != false ]]; then
    echo 'Distribution repository must be public' >&2
    exit 1
fi
gh release create "$release_tag" "$release_dir"/*.apk "$release_dir/SHA256SUMS.txt" "$release_dir/certificate.sha256" \
    --repo "$repository" --prerelease --title "Luma Music $release_tag" \
    --notes 'Luma Music · made by Romeo13card. APKs signed with the persistent family release key. Android requests installation confirmation.'
