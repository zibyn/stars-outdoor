# 调研：国内外底图与等高线数据源

> 对应 issue #3。调研日期：2026-09-27。仅采信官方文档、价格页、许可证/ToS 原文；二手来源单独标注。
> 前提（见 #1）：MapLibre（Android 优先）、不上国内商店（不走 ICP / 审图号）、个人业余、**离线地图是核心**。

## 结论（TL;DR）

1. **矢量底图：自托管 Protomaps（OSM）PMTiles** 是唯一同时满足「全球覆盖 + 允许离线分发 + 近零成本」的方案。按区域 `pmtiles extract` 出离线包，App 内用 `pmtiles://file://` 直接读取（MapLibre Android ≥ 11.7 原生支持）。
2. **地形：Mapterhorn 地形瓦片（全球底层 Copernicus GLO-30，30 m）** 做山体阴影；**等高线需预生成矢量瓦片**——`maplibre-contour` 只支持 MapLibre GL JS，不能用于 Android 原生。
3. **卫星影像没有「免费 + 可离线 + 高清」的来源。** 大陆可用天地图影像（在线、免费 Key）；海外只能付费在线（MapTiler/Stadia/Mapbox），且都禁止批量预下载。建议 MVP **卫星图仅在线**。
4. **坐标系：全链路 WGS-84 即无偏移。** OSM、天地图（CGCS2000，与 WGS-84 差异极小）、GPS 都是真实坐标；**避开高德/腾讯/百度等 GCJ-02/BD-09 瓦片**。
5. 商业托管底图（MapTiler、Stadia、Thunderforest、Mapbox）都**限制预下载离线**，只适合作为「在线图层」或备选，不适合做离线主底图。

## 逐项对比

| 数据源 | 类型 | 坐标系 | 离线下载许可 | 价格 / 免费额度 | 个人可申请 | 大陆可达性 |
|---|---|---|---|---|---|---|
| **Protomaps / PMTiles 自托管** | 矢量底图（OSM） | WGS-84 | ✅ ODbL，可自由分发（需署名） | 数据免费；托管费用自付 | ✅ 无需申请 | 取决于托管位置（需实测） |
| **OpenFreeMap** | 矢量底图（OSM，OpenMapTiles schema） | WGS-84 | 公共实例未写明；可下载整星球 MBTiles 自托管 | 免费、无 Key、无限额 | ✅ | 需实测 |
| **天地图** | 栅格：矢量/影像/地形晕渲 + 注记 | CGCS2000（≈WGS-84） | ⚠️ 条款未提及缓存/离线，未授权 | 免费 Key；配额见下 | ✅ 个人开发者 | ✅ 国内官方 |
| **MapTiler Cloud** | 矢量、卫星、等高线、地形 | WGS-84 | ❌ 仅「单一终端用户的临时缓存」；禁止批量下载 | Free：5k 会话 / 10 万请求/月（仅非商用）；Flex $30/月 | ✅ | 需实测 |
| **Stadia Maps** | 矢量/栅格、卫星 | WGS-84 | ⚠️ 移动端离线缓存 **≤100 MB/设备** | Free 20 万 credit/月（不可商用）；Starter $20/月 | ✅ | 需实测 |
| **Thunderforest**（含 Outdoors 等高线风格） | 栅格 | WGS-84 | ⚠️ 允许设备端缓存；**预下载需 Small Business $255/月** | Hobby 15 万瓦片/月免费；Solo $125/月 | ✅ | 需实测 |
| **OpenTopoMap** | 栅格（含 SRTM 等高线） | WGS-84 | ❌ 不欢迎批量下载，无 SLA | 免费 | ✅ | 需实测 |
| **OSM 官方瓦片** tile.openstreetmap.org | 栅格 | WGS-84 | ❌ 明确禁止离线/预取 | 免费 | ✅ | 需实测 |
| **Mapbox** | 矢量、卫星 | WGS-84（全球服务） | ⚠️ 离线仅限自家 SDK（tile pack 上限 750）；MapLibre 下按瓦片计费 | Vector Tiles API 20 万次/月免费，之后 $0.25/千次 | ✅ | 需实测 |
| **Mapterhorn** | 地形 DEM（terrarium, 512 px WebP） | WGS-84 | ✅ 开放数据，按来源署名 | 免费 | ✅ | 需实测 |
| **AWS Terrain Tiles**（Mapzen/joerd） | 地形 DEM | WGS-84 | ✅ 开放数据，按来源署名 | 免费（无需 AWS 账号） | ✅ | 需实测 |
| **EOxCloudless**（Sentinel-2，10 m） | 卫星 | WGS-84 | 未明确 | 非商用 CC BY-NC-SA 4.0；商用需付费许可 | ✅ | 需实测 |
| **Esri World Imagery** | 卫星 | WGS-84 | ❌ 导出瓦片仅限 ArcGIS 内使用 | 需 ArcGIS 账号 | ✅ | 需实测 |

> 「大陆可达性」一列：本次调研环境无法从大陆真实网络测试，**全部海外服务标为需实测**（见文末待办）。

## 详细说明

### Protomaps / PMTiles（推荐主底图）

- 全球底图约 **120 GB（z0–15）**，每日构建，可用 `pmtiles extract` 按区域 + `maxzoom` 裁剪出小文件。许可：底图是 OSM 的 Produced Work，适用 **ODbL**，需 OSM 署名。[Protomaps Downloads](https://docs.protomaps.com/basemaps/downloads)
- 在线分发：PMTiles 是单文件 + HTTP Range 请求，可放对象存储 + CDN；官方给出 Cloudflare（省钱）/AWS（性能）部署方案。[Protomaps Deploy](https://docs.protomaps.com/deploy/)
- Cloudflare R2：10 GB-月存储、100 万 A 类 / 1000 万 B 类操作免费，**出网流量免费**。[R2 Pricing](https://developers.cloudflare.com/r2/pricing/)（大陆访问 Cloudflare 的速度需实测；备选国内可达、无需 ICP 的香港区域对象存储。）
- **MapLibre Android 11.7.0 起原生支持 `pmtiles://https://` 与 `pmtiles://file://`**，适用于 vector / raster / raster-dem 所有源类型；但 **PMTiles 源不支持 OfflineManager 离线包下载与缓存**——所以离线方案应是「下载整个 .pmtiles 区域文件到本地，再用 `file://` 打开」，而不是走 OfflineManager。[MapLibre Android PMTiles](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)
- 风险：中国大陆的 OSM 数据密度在偏远山区可能不足（与 #周边路网 研究相关）。

### OpenFreeMap

- 公共实例免费、无 Key、不限请求，允许商用；可下载每周整星球 MBTiles / Btrfs 自托管；署名「OpenFreeMap © OpenMapTiles Data from OpenStreetMap」。[openfreemap.org](https://openfreemap.org/)
- 未找到关于用公共实例做离线预下载的明文规定，稳妥做法是只做在线备用，离线仍用自有 PMTiles。

### 天地图（国内在线首选：影像、地形晕渲）

- 服务：矢量底图 `vec`、影像 `img`、地形晕渲 `ter` 及对应注记（`cva`/`cia`/`cta`），OGC WMTS，**经纬度投影（`_c`）与球面墨卡托（`_w`）两套**；MapLibre 用 `_w`。全部需 Key（`tk=`）。[天地图地图服务](http://lbs.tianditu.gov.cn/server/MapService.html)
- Key 在控制台免费申请，**可申请个人开发者**，之后可升级为企业开发者；2020 版起实行配额管理。[天地图开发许可说明](http://lbs.tianditu.gov.cn/authorization/authorization.html)
- 配额：官方页面未公开具体数字。二手来源称个人 Key 约 **1 万次/日/每种瓦片**（[CSDN](https://blog.csdn.net/qq_40772640/article/details/130920905)，**未经官方核实**，以控制台为准）。按 App 直连每个用户都消耗同一 Key 的配额，用户量稍大即不够——需实测或申请企业认证（需营业执照）。
- 坐标系：技术上是 **CGCS2000，与 WGS-84 极接近**，是国内唯一提供真实坐标 Web 墨卡托的服务（不是 GCJ-02）。[OSM Wiki: China](https://wiki.openstreetmap.org/wiki/China)
- 条款：服务条款要求遵守测绘地理信息法规、禁止利用天地图散布广告，**没有提及缓存/离线**；版权声明要求注明来源。[天地图服务条款](https://www.tianditu.gov.cn/about/service)、[版权声明](https://www.tianditu.gov.cn/about/copyright)。离线预下载属于灰区，不建议作为离线包来源。
- 海外覆盖仅为粗略全球数据，不适合海外线路。

### MapTiler Cloud

- Free：5k 会话、10 万 API 请求/月、须显示 logo，**仅限非商用及商业产品的研发阶段**；Flex $30/月起。[MapTiler Pricing](https://www.maptiler.com/cloud/pricing/)
- 条款原文：请求结果「can be stored in a temporary personal cache (browser cache, mobile app cache, etc.) for use by a single end-user only」；「it is prohibited to batch or excessive bulk download of map tiles」；禁止「export map content for usage outside the Service」。[MapTiler Cloud Terms](https://www.maptiler.com/terms/cloud/)
- → 可作为在线图层（尤其海外卫星、等高线），**不可作为离线下载包**。无广告且免费的 App 是否算「非商用」条款未定义，存在灰区。

### Stadia Maps

- Free 20 万 credit/月（不可商用）；Starter $20/月 100 万 credit；普通瓦片 1 credit，卫星 4 credit。[Stadia Pricing](https://stadiamaps.com/pricing/)
- 条款原文：禁止批量下载，「except for the purpose of caching small amounts of data for offline use in a mobile application, not to exceed 100MB cached at a time per device」；普通缓存不超过 HTTP 缓存头或 7 天。[Stadia ToS](https://stadiamaps.com/terms-of-service/)
- → 在商业托管里对离线最友好，但 100 MB/设备只够小范围。

### Thunderforest

- Hobby 免费 15 万瓦片/月；Solo $125/月；Small Business $255/月 起才允许 bulk download（定义：预取用户不会立即查看的瓦片）。[Thunderforest Pricing](https://www.thunderforest.com/pricing/)
- 条款：「Tiles may be cached in-browser and on-device for offline use」，可保留到拿到新瓦片为止；但预下载必须 Small Business+。[Thunderforest Terms](https://www.thunderforest.com/terms/)
- → 对个人开发者离线场景太贵。

### OpenTopoMap / OSM 官方瓦片

- OpenTopoMap：CC-BY-SA 3.0，数据 OSM + SRTM 等高线；允许 App 使用但不得因批量下载加重服务器负担，无可用性保证。[OpenTopoMap About](https://opentopomap.org/about)
- OSM 官方瓦片：「Offline use is not permitted on tile.openstreetmap.org」，禁止任何预取。[OSMF Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/)
- → 均不能用于离线。

### Mapbox

- 官方文档明确支持在 MapLibre 中使用 Mapbox API；**通过第三方库使用时按单个瓦片计费**（不按 map load 打包）。[Use Mapbox APIs in MapLibre](https://docs.mapbox.com/help/dive-deeper/mapbox-in-maplibre/)
- Vector Tiles API：每月前 20 万次免费，之后 $0.25/千次起。[Mapbox Pricing](https://www.mapbox.com/pricing)
- 离线功能是 Mapbox Maps SDK 自带的（tile pack 累计上限 750），MapLibre 用不上。[Mapbox Android Offline](https://docs.mapbox.com/android/maps/guides/offline/)
- → 无优势，不推荐。

### 地形 / 山体阴影 / 等高线

- **Mapterhorn**：全球地形瓦片，terrarium 编码、512 px WebP（`https://tiles.mapterhorn.com/{z}/{x}/{y}.webp`），也提供 PMTiles 下载；代码 BSD-3。[mapterhorn GitHub](https://github.com/mapterhorn/mapterhorn)、[Protomaps Downloads](https://docs.protomaps.com/basemaps/downloads)
  - 数据源 151 个，全球底层是 **Copernicus GLO-30（30 m，免费开放许可）**；中国大陆无高分辨率源（台湾有 20 m）。[attribution.json](https://download.mapterhorn.com/attribution.json)
- **AWS Terrain Tiles**（Mapzen joerd）：免费公开 S3，无需账号；署名要求列于 [joerd attribution](https://github.com/tilezen/joerd/blob/master/docs/attribution.md)（SRTM、GMTED2010、ETOPO1 等）。[AWS Registry](https://registry.opendata.aws/terrain-tiles/)
- **山体阴影**：DEM 以 `raster-dem` 源 + hillshade 图层渲染，离线同样用 `pmtiles://file://` 读取本地 DEM 包。
- **等高线**：`maplibre-contour`（BSD-3）可从 raster-dem 实时生成等高线，但**只支持 MapLibre GL JS**，Android 原生不可用。[maplibre-contour](https://github.com/onthegomap/maplibre-contour) → Android 需**预先从 DEM 生成等高线矢量瓦片**（打包进区域 PMTiles），或使用 MapTiler / Thunderforest 在线等高线图层。

### 卫星影像

- **天地图影像**：国内高清、免费 Key、真实坐标，在线使用（见上）。
- **MapTiler / Stadia / Mapbox 卫星**：付费在线，均受上述离线限制。
- **EOxCloudless（Sentinel-2，10 m）**：非商用 CC BY-NC-SA 4.0，商用需 EOX 商业许可，须标注年份。[EOxCloudless License](https://cloudless.eox.at/documentation/license)。10 m 对徒步辨识意义有限。
- **Esri World Imagery**：导出的离线瓦片「intended for use only within ArcGIS」，需组织账号。[World Imagery (for Export)](https://www.arcgis.com/home/item.html?id=226d23f076da478bba4589e7eae95952)
- → 没有能合法做离线包的高清卫星源；卫星图 MVP 只做在线。

### 坐标系（GCJ-02）

- 国内在线地图服务（含在国内提供服务的外国地图）普遍使用加密坐标 GCJ-02；OSM 不使用 GCJ-02，要求不引入该坐标系数据。[OSM Wiki: China](https://wiki.openstreetmap.org/wiki/China)
- 本方案所有图层（OSM 矢量、天地图、DEM、海外卫星）+ GPS 均为 WGS-84 / CGCS2000，**不需要做任何坐标纠偏**。只有接入高德/腾讯/百度瓦片，或导入来自这些 App 的 GCJ-02 轨迹时才需要转换（与 #轨迹导入 相关）。
- 合规提醒：GCJ-02 是国内公开地图的法规要求；本项目不上架国内商店、不申请审图号，使用 WGS-84 是有意识的选择，而不是被豁免。

## 建议的 MVP 组合

| 图层 | 国内 | 海外 | 离线 |
|---|---|---|---|
| 矢量底图 | Protomaps PMTiles（自托管） | 同左 | ✅ 下载区域 .pmtiles |
| 地形晕渲 | Mapterhorn / GLO-30 | 同左 | ✅ 下载区域 DEM .pmtiles |
| 等高线 | 由 DEM 预生成的矢量瓦片 | 同左 | ✅ 随区域包 |
| 卫星 | 天地图影像（在线） | MapTiler 或 Stadia（付费在线，可选） | ❌ |

固定成本：Cloudflare R2 免费额度内接近 0；卫星海外图层按需付费。

## 待办 / 未验证

- [ ] 在大陆真实网络（电信/移动/联通）实测：Cloudflare R2 自定义域名、maps.protomaps.com、tiles.mapterhorn.com、MapTiler、Stadia 的延迟与可用性。
- [ ] 登录天地图控制台确认个人 Key 的真实日配额。
- [ ] 估算中国全境 / 常用山区的 PMTiles 区域包体积（底图 z0–15 + DEM + 等高线）。
- [ ] 选定等高线生成管线（如 gdal_contour + tippecanoe / planetiler）并评估体积。
