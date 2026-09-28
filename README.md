# 户外导航

## Build

```sh
scripts/fetch-glyphs.sh          # CJK Noto Sans glyphs → app assets (~34 MB, gitignored)
./gradlew :app:testDebugUnitTest :app:assembleDebug
scripts/build-data.sh [dir]      # China basemap/DEM/contours.pmtiles + glyphs (tens of GB; BBOX=… for a small area)
scripts/push-data.sh <dir>       # basemap/dem/contours.pmtiles → device
(cd scripts/style-lint && npm ci && npm test && npm run lint) # MapLibre style check (also in CI)
```
