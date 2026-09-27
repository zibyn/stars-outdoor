#!/usr/bin/env bash
# Push sample offline PMTiles to the device/emulator.
# Usage: scripts/push-data.sh <dir containing basemap.pmtiles dem.pmtiles contours.pmtiles>
# (build them with tools/offline-pack-sample/build.sh on branch task/offline-pack-volume)
set -euo pipefail
SRC=${1:?data dir}
DEST=/sdcard/Android/data/dev.stars.outdoor/files
adb shell mkdir -p "$DEST"
for f in basemap.pmtiles dem.pmtiles contours.pmtiles; do adb push "$SRC/$f" "$DEST/$f"; done
