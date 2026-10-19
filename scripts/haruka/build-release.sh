#!/usr/bin/env bash
# Build signed release APKs locally, named like the CI release assets.
# Usage: scripts/haruka/build-release.sh   (keystore: ~/.haruka/keystore.properties, never in the repo)
# Output: build/haruka-release/haruka-v<version>.apk, haruka-arm64-v8a-v<version>.apk, SHA256SUMS.txt
set -euo pipefail
cd "$(dirname "$0")/../.."

props="${HARUKA_KEYSTORE_PROPERTIES:-$HOME/.haruka/keystore.properties}"
[ -f "$props" ] || { echo "Missing $props" >&2; exit 1; }
prop() { grep "^$1=" "$props" | cut -d= -f2-; }

version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
tag="v$version"

# Same signing path as CI: MIHON_GITHUB_RELEASE swaps the signing config to these variables.
MIHON_GITHUB_RELEASE=true \
storeFile="$(prop storeFile)" storePassword="$(prop storePassword)" \
keyAlias="$(prop keyAlias)" keyPassword="$(prop keyPassword)" \
    ./gradlew assembleRelease -Pdist=github

out=build/haruka-release
rm -rf "$out" && mkdir -p "$out"
cp app/build/outputs/apk/release/app-universal-release.apk "$out/haruka-$tag.apk"
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk "$out/haruka-arm64-v8a-$tag.apk"
(cd "$out" && shasum -a 256 haruka-*.apk > SHA256SUMS.txt)
ls -l "$out"
