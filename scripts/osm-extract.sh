#!/usr/bin/env bash
# 徒步线路 for Stars Outdoor's 周边路网, extracted from OpenStreetMap.
# Data © OpenStreetMap contributors, under the Open Database License 1.0 (https://www.openstreetmap.org/copyright).
# This script is the whole extraction: the tag rules are the osmium filter and the SQL below. Nothing is added,
# corrected or merged with other data; each OSM route relation becomes one line, as mapped.
# Needs curl, osmium (osmium-tool) and docker (GDAL).
# Usage: scripts/osm-extract.sh [out dir]   → <out dir>/routes.geojson
set -euo pipefail
OUT=$(realpath -m "${1:-out}")
mkdir -p "$OUT" && cd "$OUT"
command -v osmium >/dev/null || { echo "needs osmium-tool (apt install osmium-tool)" >&2; exit 1; }

[ -s china-latest.osm.pbf ] || { curl -sSfL --retry 3 -o china.tmp.osm.pbf https://download.geofabrik.de/asia/china-latest.osm.pbf && mv china.tmp.osm.pbf china-latest.osm.pbf; }

# Tag rule 1: relations tagged route=hiking or route=foot, with the ways and nodes they are made of.
osmium tags-filter --overwrite -o routes.osm.pbf china-latest.osm.pbf r/route=hiking,foot

# Tag rule 2: of those, one feature per relation, its member ways as a MultiLineString, keeping the OSM id,
# name (name:zh first), ref and route. Super-relations (routes made of routes) have no ways of their own and drop out.
docker run --rm -u "$(id -u):$(id -g)" -v "$OUT":/w -w /w ghcr.io/osgeo/gdal:ubuntu-small-latest \
  ogr2ogr -f GeoJSON routes.tmp.geojson routes.osm.pbf -lco RFC7946=YES -lco COORDINATE_PRECISION=6 -dialect SQLite -sql "
    SELECT osm_id, coalesce(hstore_get_value(other_tags, 'name:zh'), name) AS name,
           hstore_get_value(other_tags, 'ref') AS ref, hstore_get_value(other_tags, 'route') AS route, geometry
    FROM multilinestrings WHERE hstore_get_value(other_tags, 'route') IN ('hiking', 'foot')"
mv routes.tmp.geojson routes.geojson
rm routes.osm.pbf
