#!/usr/bin/env bash
# Guided screenshot capture for design review (Phase 6).
#
# Walks through a list of screens; for each, navigate there on the device and
# press Enter to capture (or type "s" to skip). Captures phone light/dark and,
# optionally, a simulated tablet (temporarily changes the display size, then
# restores it). Screenshots go to screenshots/<date>/ (ignored by Git).
#
# Usage: scripts/capture-screens.sh [--tablet] [--only phone|tablet]
# Requires an authorized device (adb devices); set ANDROID_SERIAL if several.
set -euo pipefail

tablet=0
only=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tablet) tablet=1 ;;
    --only) only="$2"; shift ;;
    -h | --help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

adb get-state > /dev/null 2>&1 || { echo "No authorized device; check adb devices." >&2; exit 1; }

out="screenshots/$(date +%Y-%m-%d_%H%M)"
mkdir -p "$out"

screens=(
  "home|Home"
  "search|Search with a query typed"
  "library|Library (Playlists)"
  "album|An album page (scrolled to top)"
  "playlist|A playlist page"
  "artist|An artist page"
  "nowplaying|Now Playing (full player)"
  "queue|Queue"
  "downloads|Downloads screen"
  "settings|Settings"
  "offline-library|Library in airplane mode"
)

restore_display() {
  adb shell wm size reset > /dev/null 2>&1 || true
  adb shell wm density reset > /dev/null 2>&1 || true
}

capture_set() {
  local form="$1" mode
  for mode in light dark; do
    if [[ "$mode" == dark ]]; then adb shell cmd uimode night yes > /dev/null; else adb shell cmd uimode night no > /dev/null; fi
    echo
    echo "== $form, $mode theme (the app must use the System theme in Settings) =="
    for entry in "${screens[@]}"; do
      local name="${entry%%|*}" description="${entry#*|}" answer
      read -r -p "Show: $description — Enter to capture, s to skip: " answer
      [[ "$answer" == s ]] && continue
      adb exec-out screencap -p > "$out/${form}-${mode}-${name}.png"
      echo "  saved $out/${form}-${mode}-${name}.png"
    done
  done
  adb shell cmd uimode night auto > /dev/null || true
}

if [[ "$only" != tablet ]]; then
  capture_set phone
fi

if [[ "$tablet" == 1 || "$only" == tablet ]]; then
  trap restore_display EXIT
  echo
  echo "Simulating a tablet display (restored when this script exits)."
  adb shell wm size 1600x2560
  adb shell wm density 280
  read -r -p "Rotate the device to landscape if you want the landscape tablet layout, then press Enter: " _
  capture_set tablet
fi

echo
echo "Done: $out"
