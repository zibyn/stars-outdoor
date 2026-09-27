#!/usr/bin/env bash
# THROWAWAY: rebuilds the 太白山 sample offline pack used to size offline maps
# (see issue "任务：样例山区离线包体积实测"). Needs: curl, docker. Output in ./out.
set -euo pipefail
BBOX=107.5,33.8,108.05,34.25
mkdir -p out && cd out
[ -x pmtiles ] || curl -sL https://github.com/protomaps/go-pmtiles/releases/download/v1.31.2/go-pmtiles_1.31.2_Linux_x86_64.tar.gz | tar xz pmtiles
BUILD=$(curl -s https://build-metadata.protomaps.dev/builds.json | python3 -c "import sys,json;print(json.load(sys.stdin)[-1]['key'])")
./pmtiles extract "https://build.protomaps.com/$BUILD" basemap.pmtiles --bbox=$BBOX --maxzoom=15
./pmtiles extract https://download.mapterhorn.com/planet.pmtiles dem.pmtiles --bbox=$BBOX --maxzoom=12
docker run --rm -v "$PWD":/w -w /w ghcr.io/osgeo/gdal:ubuntu-small-latest bash -c '
B=https://copernicus-dem-30m.s3.amazonaws.com; L=""
for t in N33_00_E107_00 N33_00_E108_00 N34_00_E107_00 N34_00_E108_00; do L="$L /vsicurl/$B/Copernicus_DSM_COG_10_${t}_DEM/Copernicus_DSM_COG_10_${t}_DEM.tif"; done
gdalbuildvrt -q dem.vrt $L
gdalwarp -q -overwrite -te 107.5 33.8 108.05 34.25 dem.vrt dem.tif
gdal_contour -q -i 20 -a ele dem.tif contours.gpkg
rm -f contours.pmtiles
ogr2ogr -q -f PMTiles contours.pmtiles contours.gpkg -dsco MINZOOM=12 -dsco MAXZOOM=14 -dsco SIMPLIFICATION=8 -dsco SIMPLIFICATION_MAX_ZOOM=8 -nln contours'
ls -l *.pmtiles
