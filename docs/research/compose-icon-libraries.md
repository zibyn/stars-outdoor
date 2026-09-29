# 调研：Compose 可用的图标库与强光下的辨识度

调研日期：2026-09-29。对应 #76（地图 #61 体验改版）。

**来源说明**
- 只用一手来源：官方文档、官方仓库、许可证文件、Maven 仓库元数据、论文摘要（Crossref）。
- 标 **自测** 的数字是本次在本机用官方产物实测的，命令见文中。
- 没有一手出处的判断标 **未核实**。

## 1. 摘要

- **项目现状**：`app/build.gradle.kts` 只有 `org.jetbrains.compose.foundation:foundation:1.12.0`，没有任何 Material / 图标依赖；仓库里没有 `gradle/libs.versions.toml`（依赖直接写在 build 脚本里）；`res/` 下没有 drawable；**没有开启 R8**（没有 `isMinifyEnabled`，默认关闭）。
- **不要用 `material-icons-extended`**：
  - 官方文档说它"no longer maintained or recommended"，并且"can also increase the build time of your apps *significantly*" [A1]。
  - Maven 上最后一版是 1.7.8（2025-02），之后不再发布 [A2]。
  - 图标是旧的 Material Icons 外形，不是 Material Symbols。
  - AAR 有 35.7 MB。我们不开 R8，它会整包进 APK：**自测**转 dex 后约 21.9 MB，压缩后约 4 MB。
- **推荐方案：Material Symbols（Outlined），按需把单个图标的 Android XML 复制进 `res/drawable/`，用 foundation 的 `Image(painterResource(...), colorFilter = ColorFilter.tint(...))` 显示**。
  - 许可证是 Apache 2.0 [S1][S2]。
  - 这也是官方推荐的做法 [A1]。
  - 不增加任何依赖。20 来个图标只多出几十 KB（**自测**：单个 24dp XML 约 1–2 KB）。
  - 我们需要的图标它**全有**，包括定位的三种状态、`sos`、`share_location`、`download_for_offline`（见 §4）。
  - 官方仓库为每个图标预生成了 **粗细 100–700 × 填充 0/1 × 等级 −25/0/200 × 光学尺寸 20/24/40/48** 的全部 XML [S3]，可以直接挑"更粗、更高等级"的版本来应对强光，不用自己转。
- **Phosphor / Tabler / Lucide 都是 MIT 或 ISC 许可**，图标齐全度也够。但它们：
  - 都**没有官方的 Android / Compose 产物**，社区封装要么过期（`compose-icons` 里的 Tabler 停在 1.39.1，官方已是 v3.48.0），要么只有 0–1 星。
  - 接入方式和 Material Symbols 一样，要自己用 Vector Asset Studio 或 Valkyrie 转换 SVG，没有额外优势。
  - Lucide 和 Phosphor **没有 `sos` 图标**；Lucide 没有"分享位置"专用图标。
  - Tabler 和 Lucide 是描边 SVG（`stroke-width="2"`），导入后可以改线宽。
- **强光和小尺寸下的风格选择**：
  - 找不到针对"户外强光"的一手指南（**未核实**）。
  - 相关的一手依据有三条：
    - WCAG 1.4.11 要求图标与背景的对比度 ≥ 3:1，并建议"避免特别细的线和形状"，因为抗锯齿会让细线显得更淡 [W1]。
    - Material Symbols 官方说明：等级（grade）调高可以"突出"图标，调低用来减少深色背景上的眩光 [S1]。
    - 一篇 2025 年的同行评审研究：**实心图标**在识别和视觉搜索上显著更好，尤其是用户不熟悉的图标；熟悉之后差距缩小 [R1]。
  - **建议**：
    - 活动状态下的大键（标注 / 暂停 / 求助）和状态图标用**填充**版（`fill1`），并调粗（`wght500`–`600`）。
    - 规划状态和次要入口用 Outlined `wght400`–`500`。
    - 不要用细于 400 的粗细（Phosphor 的 thin / light 也不要用）。
    - "未选中 / 选中"可以直接用 Outlined 和填充来区分；这是 Material Symbols 设计的用法 [S1]。

## 2. 项目现状（origin/main `bee765c`）

- `app/build.gradle.kts` 的依赖：maplibre-compose 0.18.0、activity-compose 1.10.1、`org.jetbrains.compose.foundation:foundation:1.12.0`、garmin fit、okhttp。没有 `material` / `material3` / `material-icons-*`。
- `build.gradle.kts` 只声明插件版本；没有 `gradle/libs.versions.toml`。
- `buildTypes` 没有配置，`release` 不做 R8 压缩和资源压缩。
- `app/src/main/res/` 只有 `xml/file_paths.xml`，没有 drawable。
- foundation 自带 `Image` 和 `painterResource`（后者在 compose-ui 里，foundation 依赖它）。没有 Material 就没有 `Icon` 组件，用 `Image` + `ColorFilter.tint` 就能实现同样的效果，不需要为了 `Icon` 引入 Material。

## 3. 各候选逐项比较

| | Material Symbols（按需导入 XML） | material-icons-extended | Phosphor | Tabler | Lucide |
|---|---|---|---|---|---|
| 许可证 | Apache 2.0 [S1][S2] | Apache 2.0（AndroidX） | MIT [P1] | MIT [T1] | ISC；一部分图标源自 Feather（MIT）[L1] |
| 维护 | 活跃；官方仓库持续更新 | **停更**，最后一版 1.7.8（2025-02）[A2] | 活跃，core v2.0.8 [P2] | 活跃，v3.48.0 [T2] | 活跃，1.48.0 [L2] |
| 数量 | Outlined 约 3,900 条目（**自测**，fonts.google.com 元数据） | 每种风格 2,221 个，另加 core（**自测**） | 1,512 × 6 种粗细（**自测**） | outline 5,166 / filled 1,054（**自测**） | 1,856（**自测**） |
| 风格 | Outlined / Rounded / Sharp；可变轴：填充 0–1、粗细 100–700、等级 −25–200、光学尺寸 20–48 [S1] | Filled / Outlined / Rounded / Sharp / TwoTone，固定粗细 | thin / light / regular / bold / fill / duotone | 描边 2px（可调）+ 部分 filled | 仅描边，2px（可调） |
| Android 产物 | 官方仓库**直接给出 Android XML**，每个组合一份 [S3]；Android Studio 的 Vector Asset Studio 也内置 Material 图标 [A3] | Maven artifact，得到 `Icons.Outlined.X` 这样的 `ImageVector` | 无官方 Compose 产物；`phosphor-icons/android` 是个 2021 年的启动器图标包，不是库 [P3] | 无官方产物；`DevSrSouza/compose-icons` 里是 1.39.1，2024 年后未更新 [C1] | 无官方产物；社区封装都是 0–1 星 |
| 接入 | 复制 XML → `res/drawable` → `painterResource` | 一行依赖 | 下载 SVG → Vector Asset Studio / Valkyrie [V1] 转 XML 或 `ImageVector` | 同左 | 同左 |
| APK 影响（我们不开 R8） | 每个图标约 1–2 KB（**自测**） | **约 4 MB**（dex 21.9 MB，压缩后，**自测**） | 同 Material Symbols 量级 | 同左 | 同左 |

### 3.1 material-icons-extended 的体积与 R8

- **自测**：下载 `material-icons-extended-android-1.7.8.aar`，大小 35,720,998 字节。里面的 `classes.jar` 有 37 MB、11,105 个 class，每个图标是一个单独的 `XxxKt` 类。
  - 用 `build-tools/36.0.0/d8 --release --min-api 26` 转成 dex，得到 **21,879,144 字节**，zip -9 压缩后是 **4,057,605 字节**。
  - minSdk 26 时 AGP 默认把 dex 压缩后放进 APK（**未核实** 具体阈值），所以不开 R8 时 APK 大约增加 4 MB。安装后解压的 dex 约 22 MB。
- **开了 R8 会怎样**：每个图标在独立的类里，R8 的 tree shaking 会删掉没用到的类，最后只留下用到的几十个（按 R8 的通用行为推断，**未核实** 实测数字）。但即便如此：
  - debug 构建不走 R8，每次构建都要处理 3.7 万多个方法，官方说会"显著"拖慢构建 [A1]。
  - 为了图标去开 R8，要承担额外的 keep 规则风险（MapLibre、OkHttp、FIT SDK 的反射），不划算。
- 图标本身是旧的 Material Icons，外形和粗细都固定，不能用 Material Symbols 的可变轴来加粗。

### 3.2 Material Symbols 的获取方式（三选一，推荐第一种）

1. **官方仓库** `google/material-design-icons` 下的 `symbols/android/<名称>/materialsymbolsoutlined/<名称>_<变体>_24px.xml` [S3]。
   - 以 `my_location` 为例，一共 160 个文件，覆盖 `wght100…700`、`fill1`、`grad200` / `gradN25`、`20/24/40/48px` 的所有组合。
   - 直接复制就能用，只需去掉 `android:tint="?attr/colorControlNormal"`（这个属性依赖 View 主题，Compose 里用 `ColorFilter.tint` 着色）。
   - 注意：XML 的 `viewportWidth` / `viewportHeight` 是 960。
2. **fonts.google.com/icons**：在页面上调好轴，从 Android 标签页下载 XML。这是官方文档推荐的路径 [A1]。
3. **Android Studio Vector Asset Studio 的 Clip art**：内置 Material 图标，但看不出能不能选 Symbols 的粗细和等级（**未核实**）。

**写法**（只用 foundation，不加依赖）：

```kotlin
Image(
  painter = painterResource(R.drawable.my_location_fill1_wght600),
  contentDescription = "跟随定位",
  colorFilter = ColorFilter.tint(color),
  modifier = Modifier.size(28.dp),
)
```

### 3.3 Phosphor / Tabler / Lucide

- **Phosphor**：MIT [P1]。
  - `assets/` 下有 6 种粗细，每种 1,512 个 SVG [P2]。都是填充路径（`fill="currentColor"`，viewBox 256）。
  - **自测**：regular 的线宽 16/256 → 24dp 下约 **1.5dp**；bold 是 24/256 → 约 **2.25dp**。
  - 缺 `sos`。
- **Tabler**：MIT [T1]。
  - outline 版的 SVG 是 `stroke-width="2"`、圆角端点 [T3]。
  - 有 `sos`、`location-share`、`current-location`（含 filled）、`navigation`（含 filled）、`location`（导航箭头）。
  - 描边版导入成 VectorDrawable 后可以直接改 `strokeWidth`，Tiny SVG 1.2 的描边是支持的 [A3]。
- **Lucide**：ISC，一部分图标源自 Feather（MIT）[L1]。
  - 只有描边版，2px。
  - 有 `locate` / `locate-fixed` / `navigation`，没有 `sos`，没有"分享位置"专用图标。
- 三者都没有官方的 Android 分发。社区 Compose 封装要么落后主线好几年 [C1]，要么是个人小项目。要用的话，最稳的办法还是自己把 SVG 转成 XML 放进仓库，接入成本和 Material Symbols 一样，但得不到官方的 XML 预生成和可变轴。

### 3.4 其他

- `DevSrSouza/compose-icons`（MIT）：打包了 Feather、Tabler、Font Awesome、Eva、Octicons、Line Awesome、css.gg 等 [C1]，但 2024-09 之后没有提交，而且每个图标包都是整包依赖。不推荐。
- `ComposeGears/Valkyrie`（Apache 2.0）：一个工具，不是图标库。IDE 插件、CLI 或 Gradle 插件，把 SVG / XML 转成 `ImageVector` 代码 [V1]。只有在不想用 `res/drawable` 时才需要，这次用不上。

## 4. 这次要用的图标覆盖情况（均已在官方仓库核对存在）

| 用途 | Material Symbols | Tabler | Lucide | Phosphor |
|---|---|---|---|---|
| 定位：未跟随 | `location_searching` | `current-location`（outline） | `locate` | `crosshair-simple` |
| 定位：跟随 | `my_location`（可用 `fill1`） | `current-location`（filled） | `locate-fixed` | `crosshair` / `gps-fix` |
| 定位：朝向 | `navigation`（fill1）/ `explore` | `navigation`（filled）/ `location` | `navigation` / `navigation-2` | `navigation-arrow` / `compass` |
| 标注 / 图钉 | `location_on`、`add_location_alt`、`pin_drop`、`push_pin` | `map-pin`、`map-pin-plus`、`pin` | `map-pin`、`map-pin-plus`、`pin` | `map-pin`、`map-pin-plus`、`push-pin` |
| 图层 | `layers` | `stack-2`、`layers-intersect` | `layers` | `stack` |
| 队伍 | `group`、`groups` | `users`、`users-group` | `users`、`users-round` | `users`、`users-three` |
| 求助 | `sos`、`e911_emergency` | `sos`、`urgent` | 无 sos；`siren`、`life-buoy` | 无 sos；`siren`、`lifebuoy` |
| 分享位置 | `share_location` | `location-share`、`map-pin-share` | 无专用；`share-2` | 无专用；`share-network` |
| 离线 / 下载 | `download_for_offline`、`cloud_off`、`wifi_off` | `download`、`cloud-off`、`wifi-off` | `download`、`cloud-off`、`wifi-off` | `download`、`cloud-slash`、`wifi-slash` |
| 轨迹 / 路线 | `route`、`conversion_path`、`timeline`、`hiking` | `route`、`route-2`、`timeline` | `route`、`waypoints`、`footprints` | `path`、`footprints` |
| 暂停 | `pause`、`pause_circle` | `player-pause`（含 filled） | `pause` | `pause` |
| 停止 / 结束 | `stop`、`stop_circle` | `player-stop`（含 filled） | `square`、`circle-stop` | `stop`、`stop-circle` |
| 指南针 | `explore` | `compass` | `compass` | `compass` |
| 搜索 | `search` | `search` | `search` | `magnifying-glass` |
| 设置 | `settings` | `settings` | `settings` | `gear` |

- Material Symbols 的名称是**自测**的：在 `google/material-design-icons` 的 `symbols/android/<名称>/materialsymbolsoutlined/<名称>_24px.xml` 逐个请求，都返回 200。`emergency_share` 那次请求超时，没有确认。
- 其余三套是**自测**的：读官方仓库的文件树 [P2][T3][L3]。
- 定位三态的"未跟随 → 跟随 → 朝向"用 `location_searching` → `my_location` → `navigation`，这是 Android 平台上常见的组合（**未核实** 出处）；#62 已决定"图标示状态"。

## 5. 强光与小尺寸下的辨识度

**一手依据**
- **WCAG 2.2 SC 1.4.11 非文本对比度**：理解图标所必需的部分，与相邻颜色的对比度至少 3:1。并且："best practice would be for authors to avoid particularly thin lines and shapes"，因为抗锯齿会让细线渲染得更淡 [W1]。WCAG 没有提到户外或强光 [W1]。
- **Material Symbols 官方**：
  - "Weight defines the symbol's stroke weight … thin (100) and bold (700)"。
  - "To reduce glare for a light symbol on a dark background, use a low grade"。
  - "To highlight a symbol, increase the positive grade"。
  - 等级对图标尺寸的影响小于粗细。
  - 光学尺寸让图标"在不同尺寸下看起来一样"，范围 20–48dp。
  - 填充让同一图标呈现未填充和填充两种状态 [S1]。
- **Shen 等，2025，*Human Factors and Ergonomics in Manufacturing & Service Industries***：120 对实心 / 线性图标，27 名被试。实心图标在识别和视觉搜索上显著更好，尤其是不熟悉的图标；熟悉之后优势减弱；具体概念的实心图标视觉搜索最好 [R1]。
- 另有一篇 UNC 硕士论文《Filled-in vs. Outline Icons》（1,260 名被试）：结论是两种风格没有绝对优劣，只在黑底白图标时线性版稍慢 [R2]。**原页面未能打开，以上摘自搜索结果摘要，未核实。**

**推断（未核实，没有户外实测）**
- 强光下屏幕的有效对比度会下降，细线最先"消失"。所以：
  - 线宽应不低于约 2dp。对应 Material Symbols `wght400` 在 24dp 下的线宽（**自测**：`my_location` 圆环厚 80/960 × 24 ≈ 2.0dp；`wght600` ≈ 2.4dp）。
  - 实心图标的面积大，在低对比度下更容易辨认，和 [R1] 的方向一致。
- 活动状态（戴手套、强光）的大键和状态图标：用 `fill1` + `wght500`–`600` + `grad200`，并放在高对比度的底色上；显示尺寸 ≥ 28dp，点击区 ≥ 48dp（Android 无障碍的通用建议，**未核实** 出处未在本次抓取）。
- 规划状态的次要入口：用 Outlined `wght400`–`500`，与 #61 Notes 中的"图标 + 2–4 字标签"搭配。
- 不要用 thin / light（Phosphor 的 thin / light、Material `wght100`–`300`）；不要用 duotone 或 TwoTone，浅色那层在强光下会丢失。
- 地图上的图标（叠在底图上）要加白色描边或底板，保证 3:1 对比度 [W1]。

## 6. 建议

1. 选 **Material Symbols Outlined**（Apache 2.0），**不加任何依赖**：从官方仓库复制需要的 XML 到 `app/src/main/res/drawable/`，文件名保留变体后缀（例如 `my_location_fill1_wght600_24px.xml`），用 foundation 的 `Image` + `painterResource` + `ColorFilter.tint`。
2. 默认用 `wght500`；活动状态和"选中 / 跟随"状态用 `fill1`；需要强调的（求助）加 `grad200`。光学尺寸取最接近显示尺寸的档（24 或 40）。
3. 不用 `material-icons-extended`：已停更，外形旧；我们不开 R8，会让 APK 增大约 4 MB。
4. Tabler 是备选：如果将来需要 Material 没有的户外专用图形，可以单独导入 Tabler 的 SVG（MIT），并把线宽调到 2 以上。混用时注意视觉统一。
5. 需要开发者真机确认的：选定的粗细和填充在阳光直射下的实际效果。建议另开任务 ticket，请开发者截图或拍照对比 `wght400` 和 `wght600`、outlined 和 fill。

## 7. 来源

- [A1] Android Developers，Material Design icons（Compose）："this artifact is no longer maintained or recommended…"。<https://developer.android.com/develop/ui/compose/graphics/images/material>
- [A2] Google Maven 元数据，`androidx.compose.material:material-icons-extended` / `material-icons-core`，latest = 1.7.8，lastUpdated 2025-02-12。<https://dl.google.com/android/maven2/androidx/compose/material/material-icons-extended/maven-metadata.xml>
- [A3] Android Studio，Vector Asset Studio（支持 Tiny SVG 1.2 除文本外的特性、只支持居中描边、不支持虚线）。<https://developer.android.com/studio/write/vector-asset-studio>
- [S1] Google Fonts，Material Symbols guide（四个轴、许可证）。<https://developers.google.com/fonts/docs/material_symbols>
- [S2] `google/material-design-icons` LICENSE（Apache 2.0）。<https://github.com/google/material-design-icons/blob/master/LICENSE>
- [S3] `google/material-design-icons`，`symbols/android/my_location/materialsymbolsoutlined/`。<https://github.com/google/material-design-icons/tree/master/symbols/android/my_location/materialsymbolsoutlined>
- [P1] `phosphor-icons/core` LICENSE（MIT）。<https://github.com/phosphor-icons/core/blob/main/LICENSE>
- [P2] `phosphor-icons/core` `assets/`（v2.0.8）。<https://github.com/phosphor-icons/core/tree/main/assets>
- [P3] `phosphor-icons/android`（启动器图标包，最后提交于 2021-01）。<https://github.com/phosphor-icons/android>
- [T1] `tabler/tabler-icons` LICENSE（MIT）。<https://github.com/tabler/tabler-icons/blob/main/LICENSE>
- [T2] `tabler/tabler-icons` releases（v3.48.0）。<https://github.com/tabler/tabler-icons/releases>
- [T3] `tabler/tabler-icons` `icons/outline/current-location.svg`。<https://github.com/tabler/tabler-icons/blob/main/icons/outline/current-location.svg>
- [L1] `lucide-icons/lucide` LICENSE（ISC + Feather MIT 部分）。<https://github.com/lucide-icons/lucide/blob/main/LICENSE>
- [L2] `lucide-icons/lucide` releases（1.48.0）。<https://github.com/lucide-icons/lucide/releases>
- [L3] `lucide-icons/lucide` `icons/`。<https://github.com/lucide-icons/lucide/tree/main/icons>
- [C1] `DevSrSouza/compose-icons` README（Tabler 1.39.1 等；最后提交于 2024-09）。<https://github.com/DevSrSouza/compose-icons>
- [V1] `ComposeGears/Valkyrie`（SVG/XML → ImageVector）。<https://github.com/ComposeGears/Valkyrie>
- [W1] W3C，Understanding SC 1.4.11 Non-text Contrast（WCAG 2.2）。<https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html>
- [R1] Shen, Wang, Li, Chen, Hu (2025). *Outline or Solid? The Role of Icon Style on User's Perception.* Human Factors and Ergonomics in Manufacturing & Service Industries. doi:10.1002/hfm.70006（摘要经 Crossref 读取）。<https://doi.org/10.1002/hfm.70006>
- [R2] UNC Carolina Digital Repository，*Filled-in vs. Outline Icons: The Impact of Icon Style on Usability*（硕士论文；页面未能打开，未核实）。<https://cdr.lib.unc.edu/concern/masters_papers/6w924g35w>
