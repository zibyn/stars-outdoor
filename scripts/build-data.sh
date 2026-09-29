#!/usr/bin/env bash
# Build the offline data for the app (spec §3.3 steps 1–6 + glyphs): basemap, DEM and contour PMTiles
# for China, the 地名索引 places.sqlite (§2.10), the 周边路网's 徒步线路 routes.geojson (osm-extract.sh) and
# 平台轨迹 platform.sql (§2.8, imported into PostGIS once: deploy/README.md), plus CJK glyphs. Needs curl, python3, zstd, docker, osmium.
# 平台轨迹 from Taiwan: put the 林业保育署 自然步道轨迹图 KMZs (data.gov.tw, one dataset per trail) in <out dir>/tw/
# first, else only Hong Kong's are built.
# ponytail: by hand, as data.moa.gov.tw refused connections from outside Taiwan when this was written;
# fetch them here (its OpenDataList JSON per dataset) once that works from where this runs.
# Re-runnable: finished outputs and downloaded Copernicus tiles are kept, so use a fresh OUT dir for
# the quarterly refresh.
# Usage: [BBOX=minlon,minlat,maxlon,maxlat] scripts/build-data.sh [out dir]   → then scripts/push-data.sh <out dir>
# ponytail: China bbox, not its outline (also covers neighbours, misses the South China Sea islands);
# pass a GeoJSON to `pmtiles extract --region` and `gdalwarp -cutline` if the extra GBs matter.
# ponytail: one gdal_contour over the whole mosaic (single-threaded, big intermediate gpkg, no checkpoint);
# split into per-band runs if the full-China contour step proves too slow or too large.
set -euo pipefail
BBOX=${BBOX:-73.4,18.0,135.1,53.6}
OUT=$(realpath -m "${1:-out}")
SCRIPTS=$(dirname "$(realpath "$0")")
mkdir -p "$OUT/copernicus" && cd "$OUT"
IFS=, read -r W S E N <<< "$BBOX"

[ -x pmtiles ] || { curl -sSfL --retry 3 https://github.com/protomaps/go-pmtiles/releases/download/v1.31.2/go-pmtiles_1.31.2_Linux_x86_64.tar.gz | tar xzO pmtiles > pmtiles.tmp && chmod +x pmtiles.tmp && mv pmtiles.tmp pmtiles; }

if [ ! -s basemap.pmtiles ]; then
  BUILD=$(curl -sSf --retry 3 https://build-metadata.protomaps.dev/builds.json | python3 -c "import sys,json;print(json.load(sys.stdin)[-1]['key'])")
  ./pmtiles extract "https://build.protomaps.com/$BUILD" basemap.tmp.pmtiles --bbox="$BBOX" --maxzoom=15
  mv basemap.tmp.pmtiles basemap.pmtiles
fi

if [ ! -s dem.pmtiles ]; then
  ./pmtiles extract https://download.mapterhorn.com/planet.pmtiles dem.tmp.pmtiles --bbox="$BBOX" --maxzoom=11
  mv dem.tmp.pmtiles dem.pmtiles
fi

if [ ! -s contours.pmtiles ]; then
  # Copernicus GLO-30 1°×1° tiles (named by SW corner) intersecting the bbox; tileList skips all-ocean cells.
  C=https://copernicus-dem-30m.s3.amazonaws.com
  curl -sSf --retry 3 "$C/tileList.txt" | python3 -c "
import sys, math, re
w, s, e, n = map(float, sys.argv[1:])
for t in sys.stdin.read().split():
    m = re.search(r'([NS])(\d+)_00_([EW])(\d+)_00', t)
    if not m: continue
    lat = int(m[2]) * (1 if m[1] == 'N' else -1)
    lon = int(m[4]) * (1 if m[3] == 'E' else -1)
    if math.floor(s) <= lat < n and math.floor(w) <= lon < e: print(t)" "$W" "$S" "$E" "$N" > copernicus/tiles.txt
  xargs -P 8 -I{} sh -c '[ -s copernicus/{}.tif ] || { curl -sSf --retry 3 -o copernicus/{}.tif.tmp '"$C"'/{}/{}.tif && mv copernicus/{}.tif.tmp copernicus/{}.tif; }' < copernicus/tiles.txt
  echo "contours from $(wc -l < copernicus/tiles.txt) Copernicus tiles"
  sed 's|.*|copernicus/&.tif|' copernicus/tiles.txt > copernicus/files.txt
  docker run --rm -u "$(id -u):$(id -g)" -v "$OUT":/w -w /w ghcr.io/osgeo/gdal:ubuntu-small-latest bash -c "
    set -e
    gdalbuildvrt -q -overwrite -resolution highest -te $W $S $E $N -input_file_list copernicus/files.txt dem.vrt
    rm -f contours.gpkg contours.tmp.pmtiles
    gdal_contour -q -i 20 -a ele dem.vrt contours.gpkg
    ogr2ogr -q -f PMTiles contours.tmp.pmtiles contours.gpkg -dsco MINZOOM=12 -dsco MAXZOOM=14 \
      -dsco SIMPLIFICATION=8 -dsco SIMPLIFICATION_MAX_ZOOM=8 -dsco NAME=contours -nln contours
    rm contours.gpkg dem.vrt"
  mv contours.tmp.pmtiles contours.pmtiles
fi

if [ ! -s places.sqlite ]; then
  # Photon's weekly OSM export for China (~500 MB); the places keep their own coordinates, not BBOX.
  curl -sSfL --retry 3 https://download1.graphhopper.com/public/asia/china/photon-dump-china-1.0-latest.jsonl.zst \
    | zstd -dc | python3 "$SCRIPTS/build-places.py" places.tmp.sqlite
  mv places.tmp.sqlite places.sqlite
fi

[ -s routes.geojson ] || "$SCRIPTS/osm-extract.sh" "$OUT"

if [ ! -s platform.sql ]; then
  curl -sSfL --retry 3 -o hk-trails.geojson "https://portal.csdi.gov.hk/csdi-webpage/file-api?dataset_id=afcd_rcd_1665568199103_4360&format=geojson&layer_name=HikingTrails_HikingTrails_Ext_GDB"
  TW=()
  if compgen -G "tw/*.kmz" >/dev/null; then
    rm -f tw-trails.geojson
    docker run --rm -u "$(id -u):$(id -g)" -v "$OUT":/w -w /w ghcr.io/osgeo/gdal:ubuntu-small-latest bash -c '
      ogrmerge.py -single -f GeoJSON -o tw-trails.geojson -src_layer_field_name file -src_layer_field_content "{DS_BASENAME}" /vsizip/tw/*.kmz'
    TW=(tw-trails.geojson)
  fi
  python3 "$SCRIPTS/build-platform.py" platform.tmp.sql hk-trails.geojson "${TW[@]}"
  mv platform.tmp.sql platform.sql
fi

"$SCRIPTS/fetch-glyphs.sh"
du -h "$OUT"/*.pmtiles "$OUT"/places.sqlite "$OUT"/routes.geojson "$OUT"/platform.sql
