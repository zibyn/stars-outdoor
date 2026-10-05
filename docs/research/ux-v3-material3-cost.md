# 调研：在现有 Compose 工程引入 Material 3 Expressive 的代价

调研日期：2026-10-05。对应 #127，地图 #125（体验改版 v3）。

**来源说明**
- 版本和依赖关系直接读 Google Maven / Maven Central 的 `maven-metadata.xml`、`.pom`、`.module`，以及 `material3-android` 的 sources jar，不依赖二手文章。
- 发布说明：[Compose Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3)（下文简写 [RN]）、[What's new in Compose Multiplatform 1.12](https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html)（[CMP]）。
- APK 体积是 **在本工程实测** 的（`assembleRelease`，见 §4），不是估算。
- 找不到一手来源的写 **未找到**；属于我们自己的判断的写 **判断**。

## 1. 结论

- **要 Expressive 就只能用 alpha。** 最新稳定版 `androidx.compose.material3:material3:1.4.0`（2025-09-24）里 `MaterialExpressiveTheme` 和 `MotionScheme` 都是 `internal`，Expressive 组件根本不在里面。Expressive 只在 `1.5.0-alphaNN` 里，这条 alpha 线已经走了 14 个月（alpha01 2025-07-30 → alpha29 2026-09-23），还没有 beta。
- **推荐钉 `1.5.0-alpha27`。** 它依赖 `foundation 1.12.0-beta01`，在本工程解析成现有的 `1.12.0` 稳定版，不会把 foundation 也带到 alpha。alpha28、alpha29 要求 `foundation 1.13.0-alpha01`，会把整个 Compose 拖到 alpha。
- **alpha27 里 Expressive 的 API 大多已经不需要 opt-in。** 主题、MotionScheme、按钮、FAB 菜单、ButtonGroup、FloatingToolbar、SplitButton、TopAppBar 都已转正。仍要 `@ExperimentalMaterial3ExpressiveApi` 的只剩 `LoadingIndicator`、`MaterialShapes`、一个 `DropdownMenuItem` 重载和 `TonalToggleButton`。
- **与 JetBrains foundation 兼容，不用换坐标。** 本工程只有 Android，JetBrains `foundation-android:1.12.0` 在 Android 上就是转发到 `androidx.compose.foundation:foundation:1.12.0`，直接引 `androidx.compose.material3` 即可。JetBrains 自己的 `org.jetbrains.compose.material3` 也只是包一层 androidx 的 alpha。
- **APK 增量（实测）：** 现在的 release 不开 R8，增加约 **2.3 MB**。开 R8 并用到一组常见组件，增加约 **0.56 MB**。顺带发现：只开 R8 就能让现在的 APK 小 **7.4 MB**。
- **动态取色（Material You）不建议用作地图上的颜色**（判断）。理由见 §5。
- **深色底图走现有的换样式机制即可。** `basemapStyle(...)` 已经是 `remember` 出来的 JSON 串，换底图时整份样式都会重载。把 `isSystemInDarkTheme()` 也作为一个 key 传进去就行，真正的工作量在三种底图各自的深色配色（§6）。

## 2. 版本与 Expressive API 的稳定性

### 2.1 有哪些版本

| 坐标 | 最新稳定 | 最新预发布 | 来源 |
|---|---|---|---|
| `androidx.compose.material3:material3` | 1.4.0（2025-09-24） | 1.5.0-alpha29（2026-09-23） | Google Maven metadata；[RN] |
| `org.jetbrains.compose.material3:material3` | 1.9.0 | 1.13.0-alpha01（转发到 androidx 1.5.0-alpha27） | Maven Central metadata、`.module` |
| `androidx.compose.foundation:foundation` | 1.12.1 | 1.13.0-alpha03 | Google Maven metadata |
| `org.jetbrains.compose.foundation:foundation` | 1.12.1 | 1.13.0-alpha01 | Maven Central metadata |

- JetBrains 从 1.10 起只发 material3 的 alpha，版本号和 CMP 脱钩。CMP 1.12.1（2026-09-22）配的是 `material3 1.12.0-alpha03`，基于 Jetpack `1.5.0-alpha22` [CMP]。
- **1.4.0 稳定版里没有 Expressive。** 在 `material3-android-1.4.0-sources.jar` 里：`internal interface MotionScheme`，`internal fun MaterialExpressiveTheme(`；`ButtonGroup`、`FloatingToolbar`、`LoadingIndicator`、`MaterialShapes` 等源文件都不存在。1.4.0 的发布说明只提到"Material 3 components are now using the new MotionScheme"，即组件内部用了，但没有公开 [RN 1.4.0]。

### 2.2 1.5.0 alpha 里转正的进度（摘自 [RN]）

| 版本 | 转正（不再需要 opt-in） |
|---|---|
| alpha15 | "Graduate motion scheme from experimental" |
| alpha18 | "Promote materialExpressTheme, expressiveLightColorScheme"；`WavyProgressIndicator` |
| alpha19 | 按钮、ToggleButtons、FAB 与 FAB Menu、菜单；同版"Revert MaterialShapes and LoadingIndicator promotions to stable" |
| alpha20 | SplitButton |
| alpha21–22 | ButtonGroup；FloatingToolbar |
| alpha23 | Expressive 列表项；TopAppBar 各种 Flexible 变体；FlexibleBottomAppBar；Expanded 搜索栏变体 |
| alpha24 | SearchBarState 与基于 slot 的 SearchBar |
| alpha26 | BottomAppBar |
| alpha27 | 删除 `LocalMotionScheme`，改用 `MaterialTheme.motionScheme` |

- 在 alpha27 的 sources jar 中统计，行首带 `@ExperimentalMaterial3ExpressiveApi` 的声明只剩 4 个文件：`LoadingIndicator.kt`（5 处）、`MaterialShapes.kt`（4 处）、`Menu.kt`（2 处）、`ToggleButton.kt`（1 处）。
- `MaterialExpressiveTheme` 和 `MotionScheme` 都已经是 `public`，不需要 opt-in。
- **风险在于 artifact 本身是 alpha，而不在 opt-in。** alpha 之间仍在删改 API：alpha19 删了 `DropdownMenuItem` 的实验版，alpha20 删了旧 `WideNavigationRail`，alpha27 删了 `LocalMotionScheme`。每次升级都可能要改代码。
- 1.5.0 何时出 beta 或稳定版：**未找到** 官方时间表。

## 3. 与 JetBrains foundation 1.12.0 的兼容性

依赖关系来自各库的 Gradle `.module` 和 `.pom`，以及在本工程跑的 `:app:dependencies`：

- `org.jetbrains.compose.foundation:foundation-android:1.12.0` 依赖 `androidx.compose.foundation:foundation:1.12.0`，ui、runtime、animation 也都是 JetBrains 1.12.0。
- `maplibre-compose-android:0.18.0` 依赖 JetBrains `foundation 1.12.0`。0.19.0 已经发布，本次没有核对它的依赖。
- 各版 material3 要求的 `foundation-android`：

| material3 | 要求的 foundation |
|---|---|
| 1.4.0 | 1.8.1 |
| 1.5.0-alpha19 … alpha23 | 1.12.0-alpha02 / alpha03 |
| **1.5.0-alpha24 … alpha27** | **1.12.0-beta01** |
| 1.5.0-alpha28 … alpha29 | **1.13.0-alpha01** |

- 实测：加 `androidx.compose.material3:material3:1.5.0-alpha27` 后，`foundation:1.12.0-beta01 -> 1.12.0`，`ui:1.12.0-beta01 -> 1.12.0`，都落在现有稳定版上。新增的传递依赖有 `material3-ripple:1.5.0-alpha27`、`material-ripple:1.12.0-beta01`、`graphics-shapes:1.0.1`。工程能编译，也能打 release 包。
- 结论：**alpha27 是"Expressive 最全，又不把 foundation 拖进 alpha"的最后一版。** 等 foundation 1.13 出了稳定版，再一起升级到更新的 material3 alpha。
- `material-ripple:1.12.0-beta01` 是唯一一个解析成 beta 的传递依赖，因为本工程没有直接依赖它。如果介意，可以显式钉 `androidx.compose.material:material-ripple:1.12.0`（判断，未实测）。

## 4. APK 体积增量（实测）

做法：在本工程（依赖与 `main` 的 `3ac5be5` 相同）里临时加依赖，然后跑 `./gradlew :app:assembleRelease`，比较 unsigned APK 的字节数。试验代码已经撤销，不在本分支里。"用到组件"指加了一个只在 `filesVersion < 0` 时才渲染的探针 composable，让 R8 删不掉它。探针里用了 `MaterialExpressiveTheme(MotionScheme.expressive())`、Scaffold、TopAppBar、NavigationBar、SnackbarHost、FAB、Button、FilledTonalButton、IconButton、Switch、Slider、LoadingIndicator、ListItem、TextField、HorizontalFloatingToolbar、ButtonGroup、ToggleButton、ModalBottomSheet 和 AlertDialog。

| 配置 | APK 字节 | 相对基线 |
|---|---|---|
| 现状（release 不开 R8） | 70,424,173 | — |
| 不开 R8，加 material3 alpha27 | 72,783,722 | **+2.36 MB** |
| 不开 R8，加 material3 并用到组件 | 72,800,106 | +2.38 MB |
| 开 R8（`proguard-android-optimize.txt`），不加 material3 | 62,969,104 | 基线 B：比现状 −7.46 MB |
| 开 R8，加 material3 但不用 | 63,329,805 | 基线 B + 0.36 MB |
| 开 R8，加 material3 并用到组件 | 63,542,798 | **基线 B + 0.57 MB** |

- 这些数字只是"能打出包"，开 R8 后 App 能不能正常运行 **没有验证**。maplibre-native-ffi、FIT SDK、OkHttp 可能需要 keep 规则，要真机跑一遍。
- APK 的大头是约 34 MB 的 CJK 字形（`app/build.gradle.kts` 的注释）。material3 增加的 2.4 MB 或 0.6 MB 相比之下是小数。

## 5. 动态取色（Material You）对地图 App 值不值得

一手事实：
- 动态取色只在 Android 12（API 31）及以上可用，官方要求低版本回退到自定义的浅色、深色 `ColorScheme` [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)。本工程 `minSdk = 26`，所以无论如何都要自己定一套色板。

判断（不是一手来源）：**不建议用**，或者只用在不涉及地图语义的边角（比如设置页）。
- 地图上的颜色有含义：轨迹、参考轨迹、队员颜色（`memberColor`）、天气红点（ADR 0011）、等高线和路网。壁纸取色会让主色随用户壁纸漂移，可能和这些语义色撞色。比如壁纸偏红时，主按钮就和告警红分不开。
- 浮在地图上的控件需要在天地图影像、地形晕渲、深色底图上都看得清。一套固定、对比度经过验证的色板，比运行时算出来的色板容易保证这一点（户外强光可读性列在 #125 的"Not yet specified"里）。
- #125 的 Out of scope 已经排除了"用户自定义主题色"。动态取色本质上也是一种由用户决定的主题色。
- 代价很小，留作以后的选项：`dynamicDarkColorScheme(context)` 只有一行，品牌色板定下来后可以随时加开关。

## 6. 深色模式下 MapLibre 底图随主题切换

现状（本仓库代码）：
- `MainActivity` 用 `rememberMapState(baseStyle = BaseStyle.Json(style))`，其中 `style = remember(terrain, basemap, overseas, …) { basemapStyle(...) }`（`MainActivity.kt` 约 455–466 行）。也就是说，**换底图时本来就会重建整份样式 JSON**。我们自己的轨迹、队员等图层写在 `rememberMapState` 的样式内容 lambda 里，样式换了会重新加上。
- maplibre-compose 官方文档给的深色做法也是同一个模式：`val variant = if (isSystemInDarkTheme()) "dark" else "light"`，再用它拼 `BaseStyle` 的地址 [maplibre-compose: Style the map](https://maplibre.org/maplibre-compose/styling/)。文档 **没有** 说换样式时组合里声明的图层会怎样，本工程现有的换底图功能已经在实际使用这个机制。
- 落地只需要把 `isSystemInDarkTheme()` 加进上面的 `remember` key，再传给 `basemapStyle`。每种底图的深色配色要分别做：

| 底图 | 现在的来源 | 深色做法 | 依据 |
|---|---|---|---|
| 地形 | 自有 `assets/style.json`（28 层，矢量数据来自 Protomaps，另有 color-relief、hillshade；57 个不同的 `#hex` 颜色） | 自己做一份深色调色表，在 `basemapStyle` 里替换颜色，或者再维护一份 `style-dark.json`。color-relief 的色带和晕渲的 `hillshade-*-color` 也要调暗。 | 判断；颜色数量来自本仓库 |
| 标准·国内 | 天地图 `vec`/`cva` 栅格 | 天地图的深色或夜间图层：**未找到**（服务说明页 `lbs.tianditu.gov.cn/server/MapService.html` 本次没能抓到正文）。可以用 MapLibre raster 图层的 `raster-brightness-max`、`raster-saturation`、`raster-contrast` 把栅格压暗，效果需要原型验证。 | [MapLibre Style Spec – raster](https://maplibre.org/maplibre-style-spec/layers/#raster) |
| 标准·海外 | OpenFreeMap `liberty` | OpenFreeMap 官方提供 `dark` 和 `fiord` 样式，换 `OPEN_FREE_MAP_STYLE` 的地址即可，缓存文件名也要分开。 | [OpenFreeMap quick start](https://openfreemap.org/quick_start/) |
| 卫星 | 天地图 `img`/`cia` | 影像本身就偏暗，建议深色模式下不改（判断）。 | — |

- 浮在底图上的 Compose 图层（轨迹、队员、标注）有 41 处写死的 `Color(0x…)`（`app/src/main/java` 下 grep 的结果）。改主题时这些要改成取主题色，或者为深色单独定值。这部分工作量和引入 material3 无关，用不用 M3 都要做。
- 切换代价：深浅色只在系统主题变化时才切，频率很低。重新加载样式和现在换底图一样，会闪一下（判断，未实测）。

## 7. 引入的工作量清单（给规格用）

1. `app/build.gradle.kts` 加 `implementation("androidx.compose.material3:material3:1.5.0-alpha27")`，可选再钉 `material-ripple:1.12.0`。
2. 新增 `AppTheme`：`MaterialExpressiveTheme(colorScheme = 浅/深自定义, motionScheme = MotionScheme.expressive() 或 standard(), typography, shapes)`，包住 `setContent` 的根。
3. 41 处写死的颜色逐一改为取主题色或地图语义色，地图语义色单独做一张令牌表。
4. 深色底图：按 §6 的表，地形一份深色调色、海外换 OFM dark、天地图栅格压暗或不改。
5. （另立 ticket）release 开 R8 并补 keep 规则：省 7 MB 以上，同时把 material3 的增量从 2.4 MB 压到约 0.6 MB。
6. 升级策略：钉住 alpha27，等 foundation 1.13 稳定后，再成对升级 foundation 和 material3。每次升级读一遍 [RN] 里的 API Changes。
