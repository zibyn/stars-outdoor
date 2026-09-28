#!/usr/bin/env bash
# Push the offline data (PMTiles and the 地名索引) to the device/emulator.
# Usage: scripts/push-data.sh <dir containing basemap.pmtiles dem.pmtiles contours.pmtiles places.sqlite>
# (build them with scripts/build-data.sh)
set -euo pipefail
SRC=${1:?data dir}
DEST=/sdcard/Android/data/dev.stars.outdoor/files
adb shell mkdir -p "$DEST"
for f in basemap.pmtiles dem.pmtiles contours.pmtiles places.sqlite; do adb push "$SRC/$f" "$DEST/$f"; done
