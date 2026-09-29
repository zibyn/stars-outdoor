#!/usr/bin/env bash
# Upload the offline data (PMTiles, the 地名索引 and the 周边路网 GeoJSON) to the S3 bucket (RustFS on the LAN now, OSS HK later — ADR 0003).
# Usage: scripts/upload-data.sh <dir containing basemap.pmtiles dem.pmtiles contours.pmtiles places.sqlite routes.geojson>
# Reads S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY from deploy/.env. Run from the
# machine that built the files: set S3_ENDPOINT to the server's LAN address, not localhost.
set -euo pipefail
SRC=$(realpath "${1:?data dir}")
set -a; . "$(dirname "$0")/../deploy/.env"; set +a
# ponytail: aws-cli uses path-style for a custom endpoint, fine for RustFS. OSS needs a config file
# with `[default]` / `s3 =` / `  addressing_style = virtual` (OSS rejects path-style).
aws() {
  docker run --rm -i --network host -v "$SRC:/data:ro" \
    -e AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" -e AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
    -e AWS_DEFAULT_REGION="$S3_REGION" -e AWS_ENDPOINT_URL="$S3_ENDPOINT" \
    amazon/aws-cli:2.37.4 "$@"
}
aws s3 ls "s3://$S3_BUCKET" >/dev/null 2>&1 || aws s3 mb "s3://$S3_BUCKET"
for f in basemap.pmtiles dem.pmtiles contours.pmtiles places.sqlite routes.geojson; do aws s3 cp "/data/$f" "s3://$S3_BUCKET/$f"; done
# The script behind routes.geojson, served to the app's 关于 page (ODbL): uploaded with the data it made.
aws s3 cp - "s3://$S3_BUCKET/osm-extract.sh" --content-type "text/plain; charset=utf-8" < "$(dirname "$0")/osm-extract.sh"
