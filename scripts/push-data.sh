#!/usr/bin/env bash
# Push the offline data (PMTiles, the 地名索引 and the 周边路网 GeoJSON) to the device/emulator.
# Usage: scripts/push-data.sh <dir containing basemap.pmtiles dem.pmtiles contours.pmtiles places.sqlite routes.geojson>
# (build them with scripts/build-data.sh)
set -euo pipefail
SRC=${1:?data dir}
DEST=/sdcard/Android/data/dev.stars.outdoor/files
adb shell mkdir -p "$DEST"
for f in basemap.pmtiles dem.pmtiles contours.pmtiles places.sqlite routes.geojson; do adb push "$SRC/$f" "$DEST/$f"; done
