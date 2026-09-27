# 户外导航

## Build

```sh
scripts/fetch-glyphs.sh          # CJK Noto Sans glyphs → app assets (~34 MB, gitignored)
./gradlew :app:testDebugUnitTest :app:assembleDebug
scripts/push-data.sh <dir>       # basemap/dem/contours.pmtiles → device (build via task/offline-pack-volume)
```
