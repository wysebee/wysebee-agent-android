#!/usr/bin/env bash
# Build one version of Wysebee Agent for Android. Each is an app of its own, so both can be on
# one phone side by side. Both pair with https://app.wysebee.com unless the person chooses another
# server in the app, or scans a QR code another Wysebee Cloud shows:
#
#   dev    "Wysebee Agent Dev"    com.wysebee.agent.android.dev   (debuggable)
#   prod   "Wysebee Agent"        com.wysebee.agent.android       (the release)
#
# Usage: scripts/build.sh [dev|prod]   (default: dev)
# Install a dev build with scripts/install.sh. prod is the release for people to download:
# signed with the release key (~/.gradle/gradle.properties) and copied to
# dist/wysebee-agent-android-v<version>.apk, with its SHA-256 beside it.
set -euo pipefail

cd "$(dirname "$0")/.."
env="${1:-dev}"
case "$env" in
  dev)   task=assembleDebug;   apk=app/build/outputs/apk/debug/app-debug.apk ;;
  prod)  task=assembleRelease; apk=app/build/outputs/apk/release/app-release.apk ;;
  -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown version '$env': use dev or prod." >&2; exit 2 ;;
esac

if [[ "$env" == prod ]] && ! grep -q '^WYSEBEE_AGENT_ANDROID_STORE_FILE=' "${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties" 2>/dev/null; then
  echo "No release key: WYSEBEE_AGENT_ANDROID_STORE_FILE and _STORE_PASSWORD go in ~/.gradle/gradle.properties (README, Releasing)." >&2
  exit 1
fi

rm -f app/build/outputs/apk/release/*.apk
./gradlew "$task"

if [[ "$env" == prod ]]; then
  [[ -f "$apk" ]] || { echo "The release build came out unsigned: check the release key's properties." >&2; exit 1; }
  version=$(sed -n 's/^val appVersion = "\(.*\)"$/\1/p' app/build.gradle.kts)
  sdk=$(sed -n 's/^sdk\.dir=//p' local.properties)
  apksigner=$(ls -d "$sdk"/build-tools/*/apksigner | sort -V | tail -1)
  "$apksigner" verify --print-certs "$apk" | grep -m1 "SHA-256" || { echo "The APK's signature does not verify." >&2; exit 1; }
  mkdir -p dist
  out="dist/wysebee-agent-android-v$version.apk"
  cp "$apk" "$out"
  (cd dist && shasum -a 256 "$(basename "$out")" > "$(basename "$out").sha256")
  echo
  echo "Release $version: $out ($(du -h "$out" | cut -f1 | tr -d ' '))"
  echo "SHA-256: $(cut -d' ' -f1 "$out.sha256")"
  exit 0
fi
echo
echo "Built the $env version: $apk"
