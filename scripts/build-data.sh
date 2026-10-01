#!/usr/bin/env bash
# Build the offline data for the app (spec §3.3 steps 1–5 + glyphs): basemap, DEM and contour PMTiles
# for China, the 地名索引 places.sqlite (§2.10) and the 周边路网's 徒步线路 routes.geojson (osm-extract.sh),
# plus CJK glyphs. Needs curl, python3, zstd, docker, osmium.
# Re-runnable and incremental: reuse the same OUT dir every quarter. Finished outputs, downloads and
# finished contour bands are kept, so an interrupted run picks up where it stopped; the OSM-derived files
# (basemap, places, routes and their downloads) are rebuilt once over 30 days old, while DEM and
# contours are built once and kept (the terrain sources barely change). One OUT dir per BBOX.
# Usage: [BBOX=minlon,minlat,maxlon,maxlat] scripts/build-data.sh [out dir, default ~/Data/outdoor]   → then scripts/upload-data.sh
# ponytail: China bbox, not its outline (also covers neighbours, misses the South China Sea islands);
# pass a GeoJSON to `pmtiles extract --region` and `gdalwarp -cutline` if the extra GBs matter.
# ponytail: contour bands run one after another (single-threaded); run them with xargs -P if the contour
# step proves too slow. Disk peaks while the bands become PMTiles: China's band gpkgs measured ~180 GB
# (30 sampled tiles), ~230 GB with basemap, DEM and the contour PMTiles.
set -euo pipefail
BBOX=${BBOX:-73.4,18.0,135.1,53.6}
OUT=$(realpath -m "${1:-$HOME/Data/outdoor}")
SCRIPTS=$(dirname "$(realpath "$0")")
mkdir -p "$OUT/copernicus" && cd "$OUT"
find . -maxdepth 1 \( -name basemap.pmtiles -o -name places.sqlite -o -name routes.geojson \
  -o -name china-latest.osm.pbf -o -name photon-china.jsonl.zst -o -name '*.part' \) -mtime +30 -delete
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
  # Copernicus GLO-30 1°×1° tiles (named by SW corner) intersecting the bbox, as "lat name" lines; tileList
  # skips all-ocean cells.
  C=https://copernicus-dem-30m.s3.amazonaws.com
  curl -sSf --retry 3 --retry-all-errors "$C/tileList.txt" | python3 -c "
import sys, math, re
w, s, e, n = map(float, sys.argv[1:])
for t in sys.stdin.read().split():
    m = re.search(r'([NS])(\d+)_00_([EW])(\d+)_00', t)
    if not m: continue
    lat = int(m[2]) * (1 if m[1] == 'N' else -1)
    lon = int(m[4]) * (1 if m[3] == 'E' else -1)
    if math.floor(s) <= lat < n and math.floor(w) <= lon < e: print(lat, t)" "$W" "$S" "$E" "$N" > copernicus/tiles.txt
  echo "contours from $(wc -l < copernicus/tiles.txt) Copernicus tiles"
  # gdal_contour holds every unclosed line in memory, so the whole mosaic at once OOMed at ~27 GB: run 1°
  # latitude bands instead, each ~2 px taller so lines meet across the seam. Bands go south to north and a
  # band needs only its own tile row and the one above, so each row is downloaded just in time and deleted
  # once its band is done (China: ~70 GB of tiles never on disk at once). A finished band is kept, so a
  # rerun resumes; the bands are then read through one union layer.
  mkdir -p contours
  for s in $(seq "$S" 1 "$N"); do
    r=$(awk "BEGIN { r = int($s); print (r > $s ? r - 1 : r) }")
    if [ ! -s "contours/$s.gpkg" ]; then
      awk -v r="$r" '$1 == r || $1 == r + 1 { print $2 }' copernicus/tiles.txt > copernicus/band.txt
      [ -s copernicus/band.txt ] || continue
      xargs -P 8 -I{} sh -c '[ -s copernicus/{}.tif ] || { curl -sSf --retry 3 --retry-all-errors -o copernicus/{}.tif.tmp '"$C"'/{}/{}.tif && mv copernicus/{}.tif.tmp copernicus/{}.tif; }' < copernicus/band.txt
      sed 's|.*|copernicus/&.tif|' copernicus/band.txt > copernicus/files.txt
      n=$(awk "BEGIN { print ($s + 1 < $N ? $s + 1 : $N) + 0.0006 }")
      docker run --rm -u "$(id -u):$(id -g)" -v "$OUT":/w -w /w -e W="$W" -e E="$E" -e s="$s" -e n="$n" \
        ghcr.io/osgeo/gdal:ubuntu-small-latest bash -c '
        set -e
        gdalbuildvrt -q -overwrite -resolution highest -te "$W" "$s" "$E" "$n" -input_file_list copernicus/files.txt band.vrt
        rm -f band.gpkg*
        gdal_contour -q -i 20 -a ele band.vrt band.gpkg
        mv band.gpkg "contours/$s.gpkg"
        rm band.vrt'
    fi
    awk -v r="$r" '$1 == r { print "copernicus/" $2 ".tif" }' copernicus/tiles.txt | xargs -r rm -f
  done
  rm -f copernicus/*.tif  # the row above the last band, read only for its seam
  docker run --rm -u "$(id -u):$(id -g)" -v "$OUT":/w -w /w ghcr.io/osgeo/gdal:ubuntu-small-latest bash -c '
    set -e
    {
      echo "<OGRVRTDataSource><OGRVRTUnionLayer name=\"contours\">"
      for f in contours/*.gpkg; do echo "<OGRVRTLayer name=\"contour\"><SrcDataSource>$f</SrcDataSource></OGRVRTLayer>"; done
      echo "</OGRVRTUnionLayer></OGRVRTDataSource>"
    } > contours.vrt
    rm -f contours.tmp.pmtiles
    ogr2ogr -q -f PMTiles contours.tmp.pmtiles contours.vrt -dsco MINZOOM=12 -dsco MAXZOOM=14 \
      -dsco SIMPLIFICATION=8 -dsco SIMPLIFICATION_MAX_ZOOM=8 -dsco NAME=contours -nln contours
    rm -r contours contours.vrt'
  mv contours.tmp.pmtiles contours.pmtiles
fi

if [ ! -s places.sqlite ]; then
  # Photon's weekly OSM export for China (~500 MB); the places keep their own coordinates, not BBOX.
  # Resumable (.part survives a failed run); zstd -t throws the .part away if a resume spliced two weekly
  # dumps together.
  P=photon-china.jsonl.zst
  if [ ! -s $P ]; then
    for i in 1 2 3 4 5; do curl -sSfL -C - -o $P.part https://download1.graphhopper.com/public/asia/china/photon-dump-china-1.0-latest.jsonl.zst && break; [ $i = 5 ] && exit 1; sleep 10; done
    zstd -tq $P.part || { rm $P.part; echo "$P: corrupt download, rerun" >&2; exit 1; }
    mv $P.part $P
  fi
  zstd -dc $P | python3 "$SCRIPTS/build-places.py" places.tmp.sqlite
  mv places.tmp.sqlite places.sqlite
fi

[ -s routes.geojson ] || "$SCRIPTS/osm-extract.sh" "$OUT"

"$SCRIPTS/fetch-glyphs.sh"
du -h "$OUT"/*.pmtiles "$OUT"/places.sqlite "$OUT"/routes.geojson
