#!/usr/bin/env bash
# Upload offline PMTiles to the S3 bucket (RustFS on the LAN now, OSS HK later — ADR 0003).
# Usage: scripts/upload-data.sh <dir containing basemap.pmtiles dem.pmtiles contours.pmtiles>
# Reads S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY from deploy/.env.
set -euo pipefail
SRC=$(realpath "${1:?data dir}")
set -a; . "$(dirname "$0")/../deploy/.env"; set +a
# ponytail: aws-cli picks path-style for a custom endpoint; OSS wants virtual-hosted,
# so for OSS add AWS_CONFIG_FILE with `s3 = addressing_style = virtual` when migrating.
aws() {
  docker run --rm --network host -v "$SRC:/data:ro" \
    -e AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" -e AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY" \
    -e AWS_DEFAULT_REGION=us-east-1 -e AWS_ENDPOINT_URL="$S3_ENDPOINT" \
    amazon/aws-cli "$@"
}
aws s3api head-bucket --bucket "$S3_BUCKET" 2>/dev/null || aws s3 mb "s3://$S3_BUCKET"
for f in basemap.pmtiles dem.pmtiles contours.pmtiles; do aws s3 cp "/data/$f" "s3://$S3_BUCKET/$f"; done
