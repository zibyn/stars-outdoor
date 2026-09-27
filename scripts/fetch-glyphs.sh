#!/usr/bin/env bash
# Download CJK-capable Noto Sans glyphs into the app's assets (Protomaps' own glyphs have empty CJK ranges).
set -euo pipefail
FONT="Noto Sans Regular"
DEST="$(dirname "$0")/../app/src/main/assets/fonts/$FONT"
mkdir -p "$DEST"
for s in $(seq 0 256 65280); do
  f="$DEST/$s-$((s+255)).pbf"
  [ -s "$f" ] || curl -sf -o "$f" "https://tiles.openfreemap.org/fonts/${FONT// /%20}/$s-$((s+255)).pbf"
done
du -sh "$DEST"
