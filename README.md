# 户外导航

## Build

```sh
scripts/fetch-glyphs.sh          # CJK Noto Sans glyphs → app assets (~34 MB, gitignored)
./gradlew :app:testDebugUnitTest :app:assembleDebug
scripts/push-data.sh <dir>       # basemap/dem/contours.pmtiles → device (build via task/offline-pack-volume)
(cd scripts/style-lint && npm ci && npm test && npm run lint) # MapLibre style check (also in CI)
```
