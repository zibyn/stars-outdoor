# 开发

面向开发者：构建 App 与服务端、生成并上传离线数据。服务端部署见 [deploy/README.md](../deploy/README.md)。

## Build

```sh
scripts/fetch-glyphs.sh          # CJK Noto Sans glyphs → app assets (~34 MB, gitignored)
./gradlew :app:testDebugUnitTest :app:assembleDebug
scripts/build-apk.sh             # signed release APK → build/stars-trail-<commit>.apk, for your own phone (wizard; signs with ~/Data/andorid/starsdom-release.keystore)
git tag v1.2.3 && git push origin v1.2.3   # release: .github/workflows/release.yml builds, signs and publishes it (CONTRIBUTING.md, ADR 0013)
scripts/build-data.sh            # offline data + glyphs (tens of GB; BBOX=… for a small area) — see 数据 below
scripts/push-data.sh             # the data files → device (or copy them into app/src/debug/assets/data/ to bundle into the debug APK)
scripts/perf-sample.py > perf.gpx # 1000 标注 + 20k-point 轨迹 (drawn capped at 5000) for the on-device smoothness check (#36); open it with the app
(cd server && go generate ./... && go test ./...)  # API (Go): edit server/openapi.yaml first, api/ is generated (ADR 0004); deploy: deploy/README.md
(cd scripts/style-lint && npm ci && npm test && npm run lint) # MapLibre style check (also in CI)
```

## 数据

### 准备（只做一次）

| 依赖 | 用途 | 安装 |
|---|---|---|
| `curl`、`python3`、`zstd` | 下载与处理数据 | `apt install zstd` |
| `docker` | 运行 GDAL（等高线、徒步线路转换）和 aws-cli（上传） | |
| `osmium-tool` | 从 OSM 中抽取徒步线路 | `sudo apt install osmium-tool` |
| `deploy/.env` | 对象存储：`S3_ENDPOINT`、`S3_BUCKET`、`S3_ACCESS_KEY`、`S3_SECRET_KEY`、`S3_REGION` | 见 `deploy/README.md` |

### 构建：`scripts/build-data.sh`

```sh
scripts/build-data.sh                                                  # 中国全境 → ~/Data/outdoor，每季度重跑一次，约 30–50 GB，首次要跑很多小时
BBOX=107.5,33.7,108.1,34.2 scripts/build-data.sh ~/Data/outdoor-qinling   # 小范围试验：另给一个目录，别混进全国数据
```

`BBOX` 的格式是 `最小经度,最小纬度,最大经度,最大纬度`，不填时默认是中国全境的外接矩形。数据目录默认 `~/Data/outdoor`（四个数据脚本都用它，第一个参数可改）；一个 BBOX 固定用一个目录，每季度对同一目录重跑即可（增量）：超过 30 天的底图、地名索引、徒步线路及其下载会重建；高程和等高线只在首次生成，之后一直保留。

| 文件 | 内容 | 受 BBOX 限制 |
|---|---|---|
| `basemap.pmtiles` | Protomaps 矢量底图（z0–15） | 是 |
| `dem.pmtiles` | Mapterhorn 高程，用于山体阴影和分层设色（z0–11） | 是 |
| `contours.pmtiles` | Copernicus GLO-30 生成的 20 m 等高线（z12–14） | 是 |
| `places.sqlite` | 离线搜索用的地名索引 | 否，全国 |
| `routes.geojson` | OSM 徒步线路（`route=hiking` / `route=foot`） | 否，全国 |

另外还会准备 CJK 字形。脚本可以重复运行：已完成的文件和已下载的高程瓦片都会保留，中途失败后直接重跑同一条命令即可接着做。Photon 地名数据和 Geofabrik PBF 支持断点续传（`*.part`），下载完会做完整性校验，校验不过就删掉，重跑即可。

**只重建徒步线路**：`scripts/osm-extract.sh`。它会下载 Geofabrik 中国 PBF（约 1.3 GB），抽取后生成 `routes.geojson`。这个脚本本身就是公开的抽取与标签规则（ODbL），App 的"关于"页会链接到它。

### 上传：`scripts/upload-data.sh`

```sh
scripts/upload-data.sh
```

- 上传上表 5 个文件，外加 `osm-extract.sh`（供"关于"页的链接）。5 个文件缺一不可：少了任何一个，上传会中途停下，服务器也会对离线包请求返回 503。
- 服务器按上传的底图范围判断哪里能下载离线包：上传秦岭的数据，就只有秦岭能下载。
- 增量上传：只传大小变了或上次上传后重建过的文件。有文件变化时数据版本会变，手机上已下载的离线包会显示"可更新"，不会弹窗；什么都没变时数据版本不变，已缓存的离线包继续有效。

### 开发时推到手机：`scripts/push-data.sh`

```sh
scripts/push-data.sh
```

用 adb 把同样 5 个文件推到 App 的数据目录，不经过服务器也能看到底图和周边路网。推全国的 `routes.geojson` 时，点地图查"经过这里的轨迹"会慢几秒。

### 常见问题

| 现象 | 原因与处理 |
|---|---|
| `The user-provided path /data/… does not exist` | 构建没跑完，重跑 `build-data.sh` 同一目录 |
| `needs osmium-tool` | `sudo apt install osmium-tool` |
| 下载离线包提示"下载失败"，服务器返回 503 | 对象存储里缺文件，先完整上传 5 个文件 |
| 提示"该地区暂不支持离线" | 该位置不在上传数据的范围内 |
| "关于"里的脚本链接打不开 | 还没运行过 `upload-data.sh` |
| 有网但看不到公开轨迹 | 缩放级别低于 z11；或服务器连不上，这时快照也不会显示 |
