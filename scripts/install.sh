#!/usr/bin/env bash
# Install a dev build of Wysebee Agent for Android (built by scripts/build.sh) on the
# phone or emulator adb sees, then open it. It does not build: build first.
#
# Usage: scripts/install.sh [dev]   (default: dev)
# More than one device attached: name one with ANDROID_SERIAL=<serial> (see `adb devices`).
set -euo pipefail

cd "$(dirname "$0")/.."
env="${1:-dev}"
case "$env" in
  dev)   apk=app/build/outputs/apk/debug/app-debug.apk; pkg=com.wysebee.agent.android.dev ;;
  -h|--help) sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown version '$env': use dev (a prod build is downloaded from the Wysebee website)." >&2; exit 2 ;;
esac

# adb from the PATH, else from the SDK local.properties names.
adb=$(command -v adb || true)
if [[ -z "$adb" ]]; then
  sdk=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)
  adb="${sdk:-${ANDROID_HOME:-}}/platform-tools/adb"
fi
[[ -x "$adb" ]] || { echo "adb not found: put the SDK's platform-tools on the PATH." >&2; exit 1; }
[[ -f "$apk" ]] || { echo "No $env build at $apk: run scripts/build.sh $env first." >&2; exit 1; }

# Exactly one device, unless ANDROID_SERIAL names which.
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  devices=$("$adb" devices -l | sed -n '2,$p' | grep -w device || true)
  count=$(printf '%s' "$devices" | grep -c . || true)
  if [[ "$count" -eq 0 ]]; then
    echo "No phone or emulator attached (see adb devices)." >&2; exit 1
  elif [[ "$count" -gt 1 ]]; then
    echo "More than one device is attached; say which with ANDROID_SERIAL=<serial> scripts/install.sh $env" >&2
    printf '%s\n' "$devices" | while read -r serial rest; do echo "  $serial  $(grep -o 'model:[^ ]*' <<<"$rest")"; done >&2
    exit 1
  fi
fi

"$adb" install -r "$apk"
"$adb" shell am start -n "$pkg/com.wysebee.agent.android.ui.MainActivity" >/dev/null
echo "Installed and opened the $env version ($pkg)."
echo "On the phone: tap Open accessibility settings and turn the app on there."
