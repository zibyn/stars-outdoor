# 户外导航

## Build

```sh
scripts/fetch-glyphs.sh          # CJK Noto Sans glyphs → app assets (~34 MB, gitignored)
./gradlew :app:testDebugUnitTest :app:assembleDebug
scripts/build-data.sh [dir]      # offline data + glyphs (tens of GB; BBOX=… for a small area) — see 数据 below
scripts/push-data.sh <dir>       # the data files → device (or copy them into app/src/debug/assets/data/ to bundle into the debug APK)
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
BBOX=107.5,33.7,108.1,34.2 scripts/build-data.sh ~/stars-outdoor-data/qinling   # 小范围试验
scripts/build-data.sh ~/stars-outdoor-data/2026q4                               # 中国全境，每季度一次，约 30–50 GB，要跑很多小时
```

`BBOX` 的格式是 `最小经度,最小纬度,最大经度,最大纬度`，不填时默认是中国全境的外接矩形。每季度更新时请用一个新的空目录。

| 文件 | 内容 | 受 BBOX 限制 |
|---|---|---|
| `basemap.pmtiles` | Protomaps 矢量底图（z0–15） | 是 |
| `dem.pmtiles` | Mapterhorn 高程，用于山体阴影和分层设色（z0–11） | 是 |
| `contours.pmtiles` | Copernicus GLO-30 生成的 20 m 等高线（z12–14） | 是 |
| `places.sqlite` | 离线搜索用的地名索引 | 否，全国 |
| `routes.geojson` | OSM 徒步线路（`route=hiking` / `route=foot`） | 否，全国 |
| `platform.sql` | 港台平台轨迹，每条注明来源；不上传，导入 PostGIS（`deploy/README.md`） | 否 |

另外还会准备 CJK 字形。脚本可以重复运行：已完成的文件和已下载的高程瓦片都会保留，中途失败后直接重跑同一条命令即可接着做。下载失败会自动重试 3 次，并打印出错原因。

**台湾平台轨迹（手动）**：林业及自然保育署的"自然步道轨迹图"在 data.gov.tw 上是一条步道一个 KMZ，下载站在台湾以外连不上。请在台湾网络环境（或代理）下载 KMZ，放进 `<输出目录>/tw/`，删除已有的 `platform.sql` 后重跑并重新导入。不放时只生成香港的平台轨迹（渔护署郊野公园远足径），不会报错。

**只重建徒步线路**：`scripts/osm-extract.sh <输出目录>`。它会下载 Geofabrik 中国 PBF（约 1.3 GB），抽取后生成 `routes.geojson`。这个脚本本身就是公开的抽取与标签规则（ODbL），App 的"关于"页会链接到它。

### 上传：`scripts/upload-data.sh`

```sh
scripts/upload-data.sh ~/stars-outdoor-data/qinling
```

- 必须带上构建目录作为参数。
- 上传上表 6 个文件，外加 `osm-extract.sh`（供"关于"页的链接）。6 个文件缺一不可：少了任何一个，上传会中途停下，服务器也会对离线包请求返回 503。
- 服务器按上传的底图范围判断哪里能下载离线包：上传秦岭的数据，就只有秦岭能下载。
- 每次上传后数据版本会变，手机上已下载的离线包会显示"可更新"，不会弹窗。

### 开发时推到手机：`scripts/push-data.sh`

```sh
scripts/push-data.sh ~/stars-outdoor-data/qinling
```

用 adb 把同样 6 个文件推到 App 的数据目录，不经过服务器也能看到底图和周边路网。推全国的 `routes.geojson` 时，点地图查"经过这里的轨迹"会慢几秒。

### App 里的周边路网

1. **打开**：右上"图层" →"周边路网"。打开后显示：
   - **山路高亮**：底图小径加粗，橙色。
   - **徒步线路**：洋红色。
   - **平台轨迹**：青色。
   - **公开轨迹**：细的半透明紫线，重叠越多颜色越深，z11 起显示；有网时读服务器瓦片，离线时读离线包里的快照，两者不会同时画。
2. **经过这里的轨迹**：开关打开时点地图，底部列出附近经过的线路（名称、类型、来源、长度），附近没有时不弹出。每条可以：
   - **设为参考轨迹**：先存成计划轨迹，再设为参考轨迹。
   - **保存到我的轨迹**：存成计划轨迹。
3. **离线**：先在有网时下载离线包，包里会带上该区域的徒步线路、平台轨迹和公开轨迹快照。离线时列表会提示"离线中：公开轨迹来自离线包快照"。
4. **关于**：菜单 →"关于"，列出数据来源和许可证，并链接到 OSM 抽取脚本。

### 常见问题

| 现象 | 原因与处理 |
|---|---|
| `upload-data.sh: 1: data dir` | 没带构建目录参数 |
| `The user-provided path /data/… does not exist` | 构建没跑完，重跑 `build-data.sh` 同一目录 |
| `needs osmium-tool` | `sudo apt install osmium-tool` |
| 下载离线包提示"下载失败"，服务器返回 503 | 对象存储里缺文件，先完整上传 6 个文件 |
| 提示"该地区暂不支持离线" | 该位置不在上传数据的范围内 |
| "关于"里的脚本链接打不开 | 还没运行过 `upload-data.sh` |
| 有网但看不到公开轨迹 | 缩放级别低于 z11；或服务器连不上，这时快照也不会显示 |
