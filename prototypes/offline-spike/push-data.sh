#!/usr/bin/env bash
# PROTOTYPE — push sample offline data to the device/emulator.
# Usage: ./push-data.sh <dir containing basemap.pmtiles dem.pmtiles contours.pmtiles>
# (build them with tools/offline-pack-sample/build.sh on branch task/offline-pack-volume)
set -euo pipefail
SRC=${1:?data dir}
FONT="Noto Sans Regular"
if [ ! -d "$SRC/fonts/$FONT" ]; then
  mkdir -p "$SRC/fonts/$FONT"
  for s in $(seq 0 256 65280); do
    curl -sf -o "$SRC/fonts/$FONT/$s-$((s+255)).pbf" "https://tiles.openfreemap.org/fonts/${FONT// /%20}/$s-$((s+255)).pbf" || true
  done
fi
du -sh "$SRC/fonts"
DEST=/sdcard/Android/data/dev.stars.spike/files
adb shell mkdir -p "$DEST"
for f in basemap.pmtiles dem.pmtiles contours.pmtiles; do adb push "$SRC/$f" "$DEST/$f"; done
adb push "$SRC/fonts" "$DEST/"
# adb-created dirs are owned by shell and not traversable by the app; unreadable glyphs silently block whole tiles
adb shell chmod -R 777 "$DEST/fonts"
