#!/usr/bin/env bash
# Build one version of Wysebee Agent for Android. Each is an app of its own, so both can be on
# one phone side by side. Both pair with https://app.wysebee.com unless the person chooses another
# server in the app, or scans a QR code another Wysebee Cloud shows:
#
#   dev      "Wysebee Agent Dev"  com.wysebee.agent.android.dev  debuggable, the debug key
#   release  "Wysebee Agent"      com.wysebee.agent.android      minified; signed only when the
#                                                                signing properties are given
#
# Usage: scripts/build.sh [dev|release]   (default: dev)
# Install a dev build with scripts/install.sh.
set -euo pipefail

cd "$(dirname "$0")/.."
env="${1:-dev}"
case "$env" in
  dev)     task=assembleDebug;   out=app/build/outputs/apk/debug ;;
  release) task=assembleRelease; out=app/build/outputs/apk/release ;;
  -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown version '$env': use dev or release." >&2; exit 2 ;;
esac

rm -f "$out"/*.apk
./gradlew "$task"
echo
echo "Built the $env version: $(ls "$out"/*.apk)"
