# 调研：MapLibre Compose 能力与限制

> 对应 issue #4。调研日期 2026-09-27，基准版本 **maplibre-compose v0.18.0**（2026-09-25 发布）。
> 结论只引用一手来源：maplibre-compose 仓库源码 / 文档 / Release notes / issues，以及 maplibre-native、maplibre-native-ffi 仓库。

## 一句话结论

**可以用作 Stars Outdoor 的地图层**：离线区域下载、PMTiles/MBTiles、自定义栅格/矢量源、定位+朝向、GeoJSON 大量点线图层在 Android 上都有一等支持，iOS 同一套 API（Beta）。主要风险是 **API 仍在频繁破坏性变更（0.x、约每 1–2 周一个 minor）**，以及 **底层已不再是 MapLibre Native Android SDK**（v0.15 起改为 maplibre-native-ffi），"直接调 Android SDK API" 这条绕行路已不存在，只能通过 `withPlatformMap` 拿到 FFI 层的 `MapHandle`。

## 1. 成熟度与架构

| 项 | 现状 | 来源 |
| --- | --- | --- |
| 版本 | v0.18.0；v0.13–v0.18 在 2026-05 至 2026-09 间发布，**每个 minor 都含 breaking changes** | `gh release list`；各版本 Release notes |
| 稳定性声明 | Android **Beta**、iOS **Beta**、Desktop/Web Alpha、macOS Native Experimental；"minor releases can contain breaking changes" | [README – Stability](https://github.com/maplibre/maplibre-compose#stability) |
| 底层引擎 | v0.15 起 Android、iOS、Desktop 全部基于 [maplibre-native-ffi](https://github.com/maplibre/maplibre-native-ffi)（MapLibre Native 的 C API），**MapLibre Android SDK 不再是传递依赖** | [v0.15.0 release](https://github.com/maplibre/maplibre-compose/releases/tag/v0.15.0) |
| FFI 自身状态 | 仓库描述为 "An **experimental** C API for MapLibre Native"，当前 0.202609.4 | maplibre-native-ffi 仓库描述；`gradle/libs.versions.toml` |
| Android 要求 | minSdk 24；需选一个渲染 runtime：`maplibre-compose-runtime-vulkan-android` 或 `-opengl-android`；ABI：armeabi-v7a / arm64-v8a / x86_64 | [Getting started](https://maplibre.org/maplibre-compose/getting-started/)；v0.15/v0.16 release |
| 社区 | 577 stars，19 个 open issue，BSD-3-Clause | GitHub API |
| 性能基准 | 仓库自带基准，能与"classic" Android/iOS SDK 对比，结果发布在 [benchmarks 页](https://maplibre.org/maplibre-compose/benchmarks/)；v0.18 称 Pixel 8 上几何更新 CPU 降约 75% | `benchmarks/README.md`；v0.18.0 release |

## 2. 核心能力逐项

### 2.1 离线区域下载 —— 支持

- `OfflineManager`（从 map runtime 取得）：`create`（style URL + bbox + zoom 范围，或 `OfflinePackDefinition.Shape` 按任意 GeoJSON 几何下载）、`resume` / `pause`、`delete`、`invalidate`（对服务器重新校验并更新变化的瓦片）。`packs`、`downloadProgress` 均为 `StateFlow`，**可在 ViewModel / 后台 Worker 中收集**。下载完成后离线时自动使用，无需额外配置。平台：Android / iOS / Desktop，Web 不支持。
  来源：[docs/offline](https://maplibre.org/maplibre-compose/offline/)、`lib/maplibre-compose/src/commonMain/.../offline/OfflineManager.kt`
- 还有 `mergeDatabase(path)`（导入另一个 MapLibre 离线数据库的包）、环境缓存 `invalidateAmbientCache` / `clearAmbientCache` / `setMaximumAmbientCacheSize`。适合"服务端预打包区域 → App 下载整库导入"的方案。来源：同上 `OfflineManager.kt`；v0.16.0 release
- **中文相关坑**：离线包**总是包含 CJK 字形**（`INCLUDE_IDEOGRAPHS = true`），因为 maplibre-native-ffi 未暴露"本地字体渲染汉字"选项（旧 Android/iOS SDK 用系统字体渲染汉字）。后果：style 的 glyphs 服务必须提供完整 CJK 字形 PBF，离线包体积会变大，在线时汉字标注也走网络字形。来源：`lib/maplibre-compose/src/maplibreNativeMain/.../offline/util.kt`

### 2.2 PMTiles / MBTiles —— 支持

- PMTiles：MapLibre Native 自 Android 11.8.0 起支持（[#2882](https://github.com/maplibre/maplibre-native/pull/2882)），后续加了 PMTiles 环境缓存（#4290）。maplibre-native-ffi 构建时强制 `MLN_WITH_PMTILES ON`；用法为 `pmtiles://` 前缀 URL，可指向 APK assets（需加 `androidResources.noCompress += "pmtiles"`）或 `file://` 下载到 app 存储的文件（远程 HTTP range 读取为 MapLibre Native PMTiles 的一般能力，本次未在 compose 侧文档中核实）。
  来源：maplibre-native `platform/android/CHANGELOG.md`；maplibre-native-ffi `cmake/mln_ffi_options.cmake`、`docs/.../guides/load-a-style.mdx`
- MBTiles：`mbtilesUrl(uri)` / `rememberMbtilesUrl(uri)` 生成 `mbtiles://` URL，用于 `VectorTileSource` / `RasterTileSource`；打包资源会被拷贝到缓存目录。来源：`sources/MbtilesUrl.kt`
- 注：compose 自身文档没有专门的 PMTiles 页面，能力来自引擎 URL 协议；上线前应在真机验证一次 `pmtiles://file://...`。

### 2.3 自定义栅格/矢量源 —— 支持

- Source 类型：`GeoJsonSource`、`VectorTileSource`、`RasterTileSource`、`RasterDemTileSource`（地形/山体阴影，配合 `HillshadeLayer`、`ColorReliefLayer`）、`ImageSource`、以及 `CustomSources.kt` 中的自定义几何源（`GeometryTileProvider` 按瓦片返回要素，suspend）。来源：`lib/maplibre-compose/src/commonMain/.../sources/`
- `MapRequestInterceptor`（改写 URL、加 header，如给自有瓦片服务加 token）+ `MapResourceProvider`（由 App 代码直接返回瓦片字节），对 map、snapshotter、离线操作均生效。可用来接入国内可达的自建/托管瓦片源、或从本地文件供瓦片。来源：[docs/requests](https://maplibre.org/maplibre-compose/requests/)
- 可叠加到基础 style 上并用 `Anchor` 控制层级；`getBaseSource` 复用 style 里的源。来源：[docs/layers](https://maplibre.org/maplibre-compose/layers/)

### 2.4 用户定位与朝向 —— 支持

- `location` 模块：`LocationProvider` → `rememberLocationState` → `LocationIndicatorLayer`（v0.18 起取代 `LocationPuck`，绘制朝向精度扇区）+ `LocationTrackingEffect`（相机跟随）。Android/iOS 提供默认**朝向（heading）provider**；`LocationMeasurement` / `HeadingMeasurement` 带时间戳与精度，heading 标明北向基准。来源：[docs/location](https://maplibre.org/maplibre-compose/location/)；v0.16/v0.18 release
- Android 融合定位：可选 `location-runtime-gms`（Google Play 服务）或 `location-runtime-hms`（华为 HMS），否则走系统 framework provider。**对国内无 GMS 的设备友好**。
- 库不会自动申请权限；后台定位只提示声明 `ACCESS_BACKGROUND_LOCATION`，**不提供前台服务 / 后台轨迹记录**——离线记录轨迹与队伍位置上报需 App 自己实现 Foreground Service + FusedLocation/LocationManager，地图只负责显示。
- `location` 模块可独立使用（不依赖地图），可在前台服务里复用同一 provider 抽象。来源：v0.15.0 release

### 2.5 大量点/线图层（标注、轨迹、队伍成员）—— 支持，推荐走 GeoJSON + 样式图层

- 图层：`CircleLayer`、`SymbolLayer`、`LineLayer`、`FillLayer`、`HeatmapLayer` 等全套 style-spec 图层；通用 `Layer(id, type)` 兜底插件图层。来源：`layers/` 目录；v0.18 release
- `GeoJsonSource` 支持 `cluster`（点聚合，适合大量标注）、`tolerance`（Douglas-Peucker 简化，适合长轨迹）、`buffer`；支持 feature-state 写入（高亮选中轨迹/成员而不重建数据）。来源：`sources/GeoJsonSource.kt`；v0.15/v0.16 release
- v0.13 起 GeoJSON 同步更新；v0.18 称几何更新 CPU 降约 75%。实时队伍成员位置应**更新同一个 GeoJSON source 的数据**，而不是为每个成员放一个 Compose 组件。
- Compose 覆盖物标记（`GeographicLayout` / 地理定位 overlay）适合少量交互型标记（如当前选中的队友气泡），大量点用 Compose 会有重组开销。基准用例里同时有 `overlays-points`、`layers-points`、`recompose-points` 可对比。来源：[docs/controls](https://maplibre.org/maplibre-compose/controls/)；`benchmarks/cases.json`
- 点击：图层支持要素点击/双击、命中 padding、未处理点击回调；`queryRenderedFeatures` 类查询为 suspend。来源：v0.15/v0.16 release

### 2.6 其它对户外 App 有用的能力

- `MapSnapshotter`：无交互地渲染地图为 `ImageBitmap`（轨迹分享图、离线包缩略图）。来源：[docs/snapshotter](https://maplibre.org/maplibre-compose/snapshotter/)
- `AndroidMapPresentation`：在 Android `Surface` 上渲染（Android Auto 等），暂非 MVP 需要。
- `CameraPosition` 支持 Kotlin serialization，便于保存状态。来源：v0.17 release

## 3. iOS / KMP 现状

- 同一个 `commonMain` API 覆盖 Android / iOS / Desktop / Web；v0.15 起 Android 与 iOS 共用 FFI 实现，**不再需要 CocoaPods/SwiftPM 引入 MapLibre iOS**，只加系统链接参数。iOS 要求 15.5+，Beta。
- 离线包、定位（含朝向）、PMTiles/MBTiles 在 iOS 上同样可用（Web 除外）。
- 结论：只要地图相关代码写在 `commonMain`、定位后台服务按平台 `expect/actual`，将来扩到 iOS 的地图部分改动很小。
- 来源：[Getting started](https://maplibre.org/maplibre-compose/getting-started/)、v0.15.0 / v0.18.0 release

## 4. 已知限制与缺失

| 限制 | 影响 | 来源 |
| --- | --- | --- |
| 0.x 且每个 minor 都有 breaking change | 升级成本持续存在；需锁版本、按 release 的 Upgrading 小节迁移 | Release notes；README |
| 底层 FFI 仍 "experimental" | 引擎层 bug 需同时关注两个仓库 | maplibre-native-ffi 仓库 |
| 不再依赖 MapLibre Android SDK，旧 SDK 的 `MapView`/`MapLibreMap`/插件（annotation plugin 等）**都不能用** | 网上大量 Android SDK 教程不适用 | v0.15.0 release |
| 离线包强制包含 CJK 字形，无本地字体渲染汉字 | 离线包变大；glyphs 服务要全量 CJK | `offline/util.kt` |
| 无后台定位/轨迹记录服务 | 需自研前台服务 | docs/location |
| Android 前后台切换地图闪烁 [#1377](https://github.com/maplibre/maplibre-compose/issues/1377)（open） | 视觉瑕疵；issue 里提到改用 Texture 渲染模式可规避 | GitHub issue |
| 仍有 UI 线程等待 native 工作 [#1512](https://github.com/maplibre/maplibre-compose/issues/1512)（open） | 低端机可能偶发卡顿 | GitHub issue |
| Compose 覆盖物与地图帧非原子呈现 [#1368](https://github.com/maplibre/maplibre-compose/issues/1368)（open） | 快速移动时 Compose 标记可能轻微错位；用图层画点可规避 | GitHub issue |
| Vulkan 为默认后端，模拟器上可能有问题 | 开发期可换 OpenGL runtime | v0.13.0 release |

## 5. 缺失时的绕行方案

1. **`MapState.withPlatformMap { map }`**（`@DelicateMapApi`）：借用底层 `org.maplibre.nativeffi.map.MapHandle`，只在回调内有效。这是现在唯一的"下沉到引擎"入口——**拿到的是 FFI 句柄，不是 Android SDK 的 `MapLibreMap`**。来源：`maplibreNativeMain/.../map/PlatformMapAccess.kt`
2. **`mapState.style` 命令式句柄**：直接增删改 source/layer/image、feature-state、cluster 查询，覆盖声明式 API 不便的场景（如高频更新）。来源：v0.16.0 release
3. **`Layer(id, type)` 通用图层 + `MapResourceProvider`**：新图层类型 / 自定义瓦片供给，无需改库。
4. **自定义几何源 `GeometryTileProvider`**：按瓦片按需从本地数据库（标注、公开轨迹、周边路网）提供要素，避免一次性塞入巨大 GeoJSON。
5. 真正缺的引擎能力：给 maplibre-compose / maplibre-native-ffi 提 issue/PR；**回退到 MapLibre Android SDK（`org.maplibre.gl:android-sdk`）+ AndroidView 是最后选项**，但会失去 KMP 共享与 compose 的所有封装，不推荐。

## 6. 对 Stars Outdoor 的建议

- 选用 maplibre-compose，锁定具体版本（当前 0.18.0），升级走 release 的 Upgrading 小节。
- 离线地图：优先"服务端/托管生成 PMTiles 或 MapLibre 离线库 → App 下载 → `pmtiles://file://` 或 `mergeDatabase`"，按需辅以 `OfflineManager.create` 按区域下载；注意 CJK 字形体积。
- 标注 / 轨迹 / 队友：全部用 GeoJSON source + Symbol/Line/Circle 图层；标注开 cluster，长轨迹设 `tolerance`；队友位置周期性整体替换 source 数据。
- 定位：地图端用 `location` 模块显示与朝向；轨迹离线记录、队伍位置上报用自研前台服务（Android 依赖 `location-runtime-hms`/`-gms` 视设备）。
- 需要提前做的 spike：真机验证 `pmtiles://` 本地文件 + 离线包 CJK 体积；无 GMS 国产机上的定位与朝向表现。
