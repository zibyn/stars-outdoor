# 调研：MapLibre Compose 3D 地形可行性

> 对应 issue #17（父 issue #1）。调研日期 2026-09-27。基准：**maplibre-compose v0.18.0**（main 分支源码）、**maplibre-native-ffi 0.202609.4**、MapLibre Native main 与 `feature/terrain-3d` 分支。
> 背景：[maplibre-compose 调研](https://github.com/zibyn/stars-outdoor/blob/research/maplibre-compose/docs/research/maplibre-compose.md)、[底图调研](https://github.com/zibyn/stars-outdoor/blob/research/basemaps/docs/research/basemaps.md)（Protomaps PMTiles + Mapterhorn 30 m DEM）。

## 一句话结论

**现在做不到真 3D 地形**：不是 Compose 没暴露，而是**整条链路都没有**——MapLibre Native 正式版/main 分支没有 terrain，maplibre-native-ffi 跟的是 main，Compose 也明确"尚未暴露 terrain"。3D 地形只存在于 MapLibre Native 的 **draft 分支 `feature/terrain-3d`（PR #4190）**，Android/OpenGL 上能跑但未达生产级。**MVP 用"倾斜视角（≤60°）+ hillshade 山体阴影 + color-relief 分层设色"的 2.5D 方案**，等上游合并后再切真 3D；数据层（raster-dem PMTiles）两者完全通用，不会白做。

## 1. 各层现状

| 层 | terrain 支持 | 证据 |
| --- | --- | --- |
| MapLibre GL JS | ✅ 生产可用 | Compose 的 Web 目标已处理 terrain（`jsMain/.../GlJsMapSession.kt` 里的 `GlJsTerrain`）；与 Android 无关 |
| MapLibre Native 正式版 / main | ❌ 无 | 最新 android-v13.6.1 / ios-v6.31.0；FFI 所钉的 native 提交 `d695dee`（main，2026-09-23）源码树中除测试瓦片外**没有任何 terrain 文件**；跟踪 issue [#252 Terrain3D](https://github.com/maplibre/maplibre-native/issues/252) 仍 open |
| MapLibre Native `feature/terrain-3d` | ⚠️ 开发中 | [PR #4190](https://github.com/maplibre/maplibre-native/pull/4190)：**draft**、2026-03 开、2026-09-25 仍在更新；分支内 `TERRAIN.md` 自述"大部分开发和测试在 Android/OpenGL" |
| maplibre-native-ffi | ❌ 无 | 子模块 `third_party/maplibre-native` = `d695dee`（main）；C API / Kotlin 绑定仅有 raster-dem 源，无 terrain（`include/maplibre_native_c/`、`MapHandleTest.kt`） |
| maplibre-compose | ❌ 未暴露 | `ci/style_spec_parity.py`：`OMITTED_ROOT_OBJECTS = {"terrain"}`，注释"`terrain` is not yet exposed"；`style/Sky.kt`："3D terrain, which this library does not yet expose"；`docs/.../camera.mdx`：锚点保持保证"不适用于 globe 或 terrain" |

资金/进度：MapLibre 于 2026-04 在 #252 中称正在为 Terrain3D 寻找赞助方（[newsletter 2026-03](https://maplibre.org/news/2026-04-01-maplibre-newsletter-march-2026/#terrain3d-call-for-funding)）；PR [#4389](https://github.com/maplibre/maplibre-native/pull/4389)（2026-08 合入 terrain 分支）补齐了 drape、符号/圆点贴地、按 DEM 视锥裁剪瓦片。**没有公开的合并到 main / 发版时间表。** 之后还需 FFI 暴露 C API、Compose 暴露 Kotlin API，各自再走一轮发布。

## 2. terrain-3d 分支的能力与成熟度（来源：该分支 `TERRAIN.md`）

- 已实现：OpenGL/Metal/Vulkan/WebGPU 四后端着色器；Terrain-RGB 与 **Terrarium**（Mapterhorn 用的编码）解码；background/fill/line/raster/hillshade/color-relief 图层 drape 到地形网格；symbol/circle/fill-extrusion 按 DEM 抬高；符号被山体遮挡；style JSON 根属性 `"terrain": {"source", "exaggeration"}` 解析；CPU 高程查询；Android `style.setTerrain(Terrain(...))` API。
- 后端验证：OpenGL 完整；**Vulkan 在真机上验证可渲染**（Compose 在 Android 默认用 Vulkan runtime）；WebGPU 未测。
- 未完成 / 已知问题（"Remaining Work for Production" 一节）：与 GL JS 的瓦片覆盖与 LOD 选择仍有系统性差异；drape 目标固定 512×512；相机-地形锚定；PR 描述清单中 line 图层着色器分支、瓦片跨多地形瓦片渲染等项未勾选。
- 社区实测：有人基于该分支 + 14 个自有补丁做了 iOS 徒步 App 的"轨迹回放飞行"（iPhone SE 2），但同时修了瓦片覆盖单位错误、相机高度等问题（[#4190 评论](https://github.com/maplibre/maplibre-native/pull/4190#issuecomment-5782583773)，fork `mapriot/maplibre-native`）——说明"能用"但需要自己打补丁。

## 3. 性能（中端 Android）

一手数据只有 `TERRAIN.md`，都来自低端 PowerVR GE8320：
- 放大/倾斜/平移时新瓦片集中构建，**最差帧 713 ms / 233 ms**；为此加了 `TerrainLoadMode { Quality, Balanced, Performance }`（分帧预算，Performance = 每帧 8 瓦片 / 4 drape），默认 Quality 不限速。
- 曾有 hillshade + terrain 平移时 GPU 纹理单调增长直至 **OOM**（175→634 纹理 / 45 s），已在分支修复。
- 文档自述剩余热点：每帧 `computeDrapeCoverage` CPU 开销随"目标×drawable"增长、固定 512 drape 尺寸、DEM 上传调度；"欢迎真机 profiling"。

结论：**没有中端机的可信帧率数据**；按现状判断中端机可用但有卡顿尖峰，需要真机 spike 才能定论。

## 4. 离线（DEM 打进 PMTiles）

- 可行且与 2.5D 方案共用：3D terrain 与 hillshade 用的是**同一个 raster-dem 源**（`TERRAIN.md` 示例即 Mapterhorn tilejson；Android 例子 `RasterDemSource` + `setTerrain`）。PMTiles 对 raster-dem 源的支持、`pmtiles://file://` 离线方式见底图调研。
- Compose 侧 `RasterDemTileSource` 已支持 `demEncoding`（含 Terrarium）（`sources/RasterDemTileSource.kt`）；FFI 绑定测试覆盖 Terrarium raster-dem 源（`MapHandleTest.kt`）。
- 注意：native 平台 SDK 的程序化 `RasterDemSource` 曾不暴露 encoding 参数（[#4357](https://github.com/maplibre/maplibre-native/issues/4357)，open），Compose/FFI 这条路不受影响。
- 结论：**今天打好的 DEM PMTiles 区域包，未来开 3D 时零改动复用**；3D 不需要比 hillshade 更高的 DEM 缩放级（30 m 源对应约 z12，超过由引擎 overzoom）。

## 5. 绕行方式评估

| 方案 | 可行性 | 代价 / 风险 |
| --- | --- | --- |
| A. `withPlatformMap` / 命令式 style 直接写 `terrain` | ❌ | FFI 引擎本身没有 terrain，写了也不会渲染 |
| B. 回退 MapLibre Android SDK + AndroidView | ❌ | 正式版 Android SDK 同样没有 terrain |
| C. 自建 FFI：把 `third_party/maplibre-native` 指向 `feature/terrain-3d`，style JSON 里写 `terrain` 根属性 | ⚠️ 技术上可能 | 需自行交叉编译 native（多 ABI、Vulkan/OpenGL），维护 FFI 补丁（`patches/` 下已有 17 个针对 main 的补丁）与 draft 分支的冲突；Compose 的手势/锚点对 terrain 无保证；**不适合 MVP** |
| D. 独立"3D 预览"页用 WebView + MapLibre GL JS | ✅ 可行 | GL JS terrain 成熟；离线需 pmtiles JS 协议 + 本地文件服务；与主地图两套渲染，WebView 性能/内存一般；仅做轨迹 3D 预览/回放可接受 |
| E. 2.5D：倾斜 + `HillshadeLayer` + `ColorReliefLayer` | ✅ 现成 | Compose 已有全部 API；`CameraConstraints.maxPitch` 默认 60°；无真实起伏，但山脊/沟谷立体感足够导航 |

## 6. 建议

1. **MVP 采用方案 E（2.5D）**，3D 视图状态（原型中的 3D 切换）即"倾斜 + 山体阴影 + 分层设色"，可在 UI 文案上避免承诺"真 3D"。
2. DEM 按底图调研做成区域 PMTiles，同时服务 hillshade 与未来 3D。
3. 若产品必须有真 3D（如轨迹回放飞行），优先方案 D 做成独立页面，而不是改主地图引擎。
4. 关注信号：maplibre-native PR #4190 合入 main → maplibre-native-ffi 更新子模块并暴露 terrain → maplibre-compose 从 `OMITTED_ROOT_OBJECTS` 移除 `terrain`。出现后再开 spike（中端机真机帧率 + Vulkan runtime）。

## 来源

- maplibre-compose main：`ci/style_spec_parity.py`、`.agents/skills/style-spec-parity/SKILL.md`、`lib/maplibre-compose/src/commonMain/.../style/Sky.kt`、`.../map/CameraConstraints.kt`、`.../sources/RasterDemTileSource.kt`、`.../layers/HillshadeLayer.kt`、`ColorReliefLayer.kt`、`docs/src/content/docs/camera.mdx`、`gradle/libs.versions.toml`
- maplibre-native-ffi main：`.gitmodules`、`third_party/maplibre-native`（→ `d695dee`）、`patches/maplibre-native/`、`include/maplibre_native_c/map.h`、`bindings/kotlin/.../MapHandleTest.kt`
- maplibre-native：[issue #252](https://github.com/maplibre/maplibre-native/issues/252)、[PR #4190](https://github.com/maplibre/maplibre-native/pull/4190)、[PR #4389](https://github.com/maplibre/maplibre-native/pull/4389)、[`TERRAIN.md` @ feature/terrain-3d](https://github.com/maplibre/maplibre-native/blob/feature/terrain-3d/TERRAIN.md)、[issue #4357](https://github.com/maplibre/maplibre-native/issues/4357)、[Releases](https://github.com/maplibre/maplibre-native/releases)
