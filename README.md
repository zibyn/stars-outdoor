# 户外导航

## Build

```sh
scripts/fetch-glyphs.sh          # CJK Noto Sans glyphs → app assets (~34 MB, gitignored)
./gradlew :app:testDebugUnitTest :app:assembleDebug
scripts/build-data.sh [dir]      # China basemap/DEM/contours.pmtiles + glyphs (tens of GB; BBOX=… for a small area)
scripts/push-data.sh <dir>       # basemap/dem/contours.pmtiles → device (or copy them into app/src/debug/assets/data/ to bundle into the debug APK)
scripts/perf-sample.py > perf.gpx # 1000 标注 + 20k-point 轨迹 (drawn capped at 5000) for the on-device smoothness check (#36); open it with the app
(cd server && go generate ./... && go test ./...)  # API (Go): edit server/openapi.yaml first, api/ is generated (ADR 0004); deploy: deploy/README.md
(cd scripts/style-lint && npm ci && npm test && npm run lint) # MapLibre style check (also in CI)
```
