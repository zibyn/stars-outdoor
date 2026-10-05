# 调研：2026 年现代简约 App 设计潮流与户外地图类 App 的新做法

调研日期：2026-10-05。对应 [#126](https://github.com/zibyn/stars-outdoor/issues/126)，属于地图 [#125 体验改版 v3](https://github.com/zibyn/stars-outdoor/issues/125)。下游：风格原型（#130、#131）。

**来源说明**
- 优先一手来源：Google / Apple 官方设计文档与博客、Android 开发者文档、厂商新闻稿、App Store 版本说明（通过 `itunes.apple.com/lookup` 读取，2026-10-05）。
- 媒体评测只用来补日期和细节，标注出处。
- 没有一手出处、或只在搜索摘要里见到的细节，标 **「未核实」**。
- m3.material.io 是纯前端渲染，抓不到正文；M3 Expressive 的结论来自 Google Design 研究文章、Google 官方博客、Android 开发者博客和 Compose Material 3 发布说明。Apple HIG 通过其公开 JSON（`developer.apple.com/tutorials/data/design/human-interface-guidelines/<页面>.json`）读取。

## 1. 结论（先看这里）

1. **M3 Expressive 适合用，但要"收着用"。** Google 自己的研究说表现力强的设计让人找到关键按钮最多快 4 倍、老年人和年轻人一样快 [M1]，同时也提醒"适合媒体播放器或邮箱的做法，可能不适合银行类界面"、"再多表现力也比不上基本功能好用" [M1]。户外地图更接近"工具"：只在 1–2 个关键时刻（开始记录、结束记录、到达）用大形状、强调色和弹簧动画，其余地方保持安静。
2. **Liquid Glass 的思路（控件浮在内容之上的一层）值得借鉴，透明玻璃本身不值得照搬。** Apple 规定玻璃只用于控件与导航层、不用于内容层、要"克制使用"，透明款（clear）只在暗色或加了 35% 压暗层的背景上用 [A1]。地图底图又亮又花，强光下更看不清，所以我们的地图浮层应该用 **不透明或接近不透明的色调面**。
3. **同类 App 2025–2026 年的改版都在"少一层、多看图"**：Komoot 改了色板、字体、图标、插画，主导航不变，更突出照片 [K1]；Strava 把记录页改成数据和地图同时可见 [S1]；Gaia GPS 2026.6 适配了 Liquid Glass [G1]；两步路 9.2.x 加了 3D 地图、统一了搜索、重排了线路详情页 [L1]；六只脚 4.300 "优化卡片 UI" [F1]。
4. **户外硬指标在官方文档里是现成的**：点击区 ≥ 48dp [D1]；文字对比度 4.5:1（大字 3:1）[D1][A2]；颜色在强光下"显得更暗、更灰"，要在户外实测 [A3]；不能只用颜色传达信息 [A2]。
5. **减少文字**（开发者补充要求，见 §5）：删掉的应该是说明段落，不是按钮标签。图标旁边保留 1–2 个字的标签 [N1]，说明改成数字、图形、就地提示和"默认收起"[N2][A4]。

## 2. 设计体系现状（2026 年 10 月）

### 2.1 Material 3 Expressive

- **是什么**：2025-05-13 随 Android 16 / Wear OS 6 发布，带来弹簧动画、动态色彩、触感反馈、模糊、强调字体、可变形状 [M2]。依据是 46 项研究、18,000 多名参与者 [M1]。
- **研究结论** [M1]：
  - 关键元素最多能快 4 倍被找到；
  - 表现力强的设计在各年龄段都更受欢迎，18–24 岁的人里高达 87%；
  - 老年用户找关键按钮的速度追上了年轻人；
  - 提醒：要看场景；功能第一，不能打破用户熟悉的交互模式；用户不熟悉时效果会打折扣。
- **手段**：强调来自颜色角色、形状、大小、动效和容器，而不是描边和阴影 [M1]。
- **Compose 落地情况**（Compose Material 3 发布说明 [D2]，2026-10-05 读取）：
  - 稳定版 1.4.0 已转正：`ButtonGroup`、`FloatingToolbar`、FAB 和 FAB 菜单。
  - 仍要 `@ExperimentalMaterial3ExpressiveApi`：`MaterialExpressiveTheme`。
  - `MaterialShapes` 和 `LoadingIndicator` 转正后又被撤回，仍是实验 API。
  - `MotionScheme`：通过 `MaterialTheme.motionScheme` 取用。
  - 示例 App Androidify 用 `MaterialExpressiveTheme` 加 `MotionScheme.expressive()`，并用了 `HorizontalFloatingToolbar` 和 `MaterialShapes` [D3]。
  - 我们现在只依赖 `org.jetbrains.compose.foundation:foundation:1.12.0`（`app/build.gradle.kts`）。引入 material3 时，用稳定的组件（浮动工具栏、按钮组、FAB 菜单），主题和形状库先按实验 API 处理。
- **Live Updates**（Android 16 系统层面的配套能力）[D4]：
  - 通知置顶、出现在锁屏和状态栏小胶囊上，默认展开、不能折叠。
  - 必须同时满足"进行中、由用户发起、有时效"。
  - 官方列出的合适场景是导航、通话、打车、外卖，不能用于广告、提醒或功能快捷入口。
  - 徒步记录符合这三个条件，但不在官方列出的场景里，**能否通过审核「未核实」**。

### 2.2 iOS 26 Liquid Glass（参考，不实现）

Apple HIG《Materials》[A1]：
- "Liquid Glass forms a distinct functional layer for controls and navigation elements … that floats above the content layer."
- "Don't use Liquid Glass in the content layer."
- "Use Liquid Glass effects sparingly … Limit these effects to the most important functional elements."
- 常规款（regular）会模糊背景、调整亮度，保证文字可读，适合文字多的组件；透明款（clear）只用在照片、视频之类的富媒体上，背景亮时加 **35% 的暗色压暗层**。
- 系统设置"降低透明度 / 增强对比度"会改变玻璃外观 [A1]；HIG《Color》要求每种配色都准备增强对比度版本 [A3]。

对我们的启发：**"控件层 / 内容层"分开**这条原则可以直接用（地图是内容，按钮和面板是控件层）；材质用不透明的 M3 色调面代替，不做实时模糊（Compose 的 `Modifier.blur` 只模糊组件自己，不能透出背后的地图；对地图做背景模糊要额外实现，性能和耗电都会增加，**成本「未核实」**）。

### 2.3 克制的扁平极简

两家文档里与此相关、且与户外场景直接有关的硬规则：
- 点击区：Android ≥ 48×48dp，越大越好 [D1]；iOS 默认 44×44pt [A2]。
- 对比度：正文 4.5:1，大字或粗体 3:1 [D1][A2]。
- 强光："In bright surroundings, colors look darker and more muted" → 在各种光线下测试配色 [A3]。
- 不能只用颜色："Convey information with more than color alone"，红绿、蓝橙对色弱用户尤其难分 [A2]。

## 3. 同类 App 的公开改版（2025–2026）

| App | 时间 | 公开的改动 | 出处 |
|---|---|---|---|
| Komoot | 2025-09-15（网页）/ 2025-09 底（手机） | 换了色板、字体、图标、插画；加大留白和对比；主导航和工作流不变；更突出照片（8,700 万张图库）。测试：110 场面谈和 3,000 多份问卷，80% 以上更喜欢新版。路线图包括深色模式、热力图、手表离线导航 | [K1][K2] |
| Komoot | 2026-01-26 | Apple Watch 独立离线导航 | [K3] |
| Komoot | 2026.40.1（2026-10-01） | 从主屏和 Spotlight 直接"开始记录 / 规划 / 离线"；手表上右滑暂停或停止 | [K4] |
| Komoot 深色模式 | — | 2025 年的路线图里写了，**截至 2026-10 是否上线「未核实」** | [K2] |
| Strava | 2025-07-16 | 重做记录页：数据和地图同时可见、实时分段，新地图渲染引擎（FATMAP 技术）；付费图层包括 3D 地形、冬季样式、热力图 | [S1] |
| AllTrails | 2025 夏 | 推出 Peak 会员（年费 79.99 美元）：自定义路线、路况、社区热力图、拍照识物；新增路点类型（观景、水源、营地）；打开时推荐"上次看过的线路"；导航中发现附近线路。**"线路页把长度、难度、爬升放在首屏无需滚动"只见于搜索摘要，「未核实」** | [T1] |
| AllTrails | 26.9.50（2026-10-01） | 版本说明只写了修复问题 | [T2] |
| Gaia GPS | 2026.6（2026-09-14） | "UI Updates for Apple's Liquid Glass interface"；下载离线地图时说明覆盖范围 | [G1] |
| Gaia GPS | 2026.2 / 2026.5 | 锁屏实时显示记录距离和时间等，**只见于搜索摘要，「未核实」** | — |
| 两步路 | 9.2.0（2026-08）至 9.2.7（2026-09-27） | 9.2.0：3D 地图、统一搜索、线路频道加新手主题、导航语音和剩余距离进度环；9.2.2：线路详情页重排；9.2.4：热力图配色可选；9.2.7：**看广告抽会员**，同时"进一步限制部分页面的广告展示" | [L1][L2] |
| 六只脚 | 4.300.0 至 4.310.0（2026-09） | "优化搜索功能；优化卡片 UI" | [F1] |

按议题归纳（只写有出处的内容）：
- **首次引导**：各家公开材料都没讲引导流程。Apple 的建议是"最好让人直接用就能懂"，如果要引导，就做成可跳过、可交互的，或拆成就地的提示；非必要的设置往后放；权限请求放进引导时要说明用途 [A4]。
- **空状态和"接着上次"**：AllTrails 打开时推荐上次看过的线路 [T1]；Komoot 从主屏快捷入口直接开始记录 [K4]。两家都在缩短"打开 → 出发"的路径。
- **色彩**：Komoot 换色板、加大对比 [K1]；两步路让热力图配色可选 [L1]；Komoot 和 Strava 都把深色地图或深色模式列为方向 [K2][S1]（Strava 记录页的"深色地图"只见于搜索摘要，「未核实」）。
- **地图浮层**：Strava 让数据和地图同时可见 [S1]；Gaia 适配 Liquid Glass，说明 iOS 端浮动控件正在成为常态 [G1]。
- **数据大字**：两步路用剩余距离进度环 [L1]；Strava 用实时分段 [S1]。都是用图形来表达进度，而不是靠文字。
- **反馈与文案**：没有找到哪家公开谈过文案改版（**「未核实」**）。两步路的广告抽奖反过来提醒我们：别在工具界面里塞营销 [L2]。

## 4. 适合与不适合户外地图的做法

| 做法 | 结论 | 理由 |
|---|---|---|
| 弹簧动画、可变形状只用在 1–2 个关键时刻（开始、结束、到达） | 适合 | M1 说表现力能帮人快速找到关键元素；关键时刻正好是需要确认反馈的地方 |
| 地图浮层、按钮做弹跳或变形动画 | 不适合 | 看地图时会分散注意力；M1 提醒功能第一；地图平移时浮层抖动影响定位（推断） |
| 浮动工具栏、FAB 菜单把操作收拢到拇指区 | 适合 | 已是稳定 API [D2]；单手操作 |
| 不透明的色调面板浮在地图上 | 适合 | A1：控件层和内容层要分开；底图花哨，强光下又会变暗变灰 [A3] |
| 透明玻璃、实时模糊做地图浮层 | 不适合 | A1：透明款要求背景够暗或加压暗层；Compose 没有现成的背景模糊（未核实成本） |
| 随壁纸取色（dynamic color）用作品牌色和地图语义色 | 不适合语义色 | 偏离、危险、队友等颜色必须全机固定，随壁纸变色就失去意义（推断）；界面辅助色可以考虑 |
| 等宽数字、大字号、粗字重 | 适合 | 大字或粗体只需 3:1 对比度，强光下更稳 [D1][A2] |
| 只靠颜色表达状态（如红点） | 不适合 | A2 明确禁止；要配形状或图标 |
| 大照片、内容流（Komoot、Strava 方向） | 不适合 | 我们定位为工具，精选和社交不在范围内（#125 Out of scope） |
| 工具界面里的广告或营销（两步路的看广告抽奖） | 避免 | L2 |
| 锁屏或通知里显示记录进度（Live Updates） | 值得评估 | D4；能否通过审核「未核实」 |

## 5. 减少文字的做法（开发者补充要求）

问题：现在的 App 大段文字太多，户外不适合读。官方建议和同类做法可以归成几条：

1. **删掉的应是说明，不是标签。**
   - NN/g：除了主页、打印、搜索等少数图标，几乎所有图标都有歧义，"a text label must be present alongside an icon"，而且标签要一直显示 [N1]。
   - 5 秒法则：5 秒内想不出合适图标的概念，就不该只用图标 [N1]。
   - 做法：底栏和主按钮保留 1–2 个字的标签；要砍的是段落式说明。只有图标的按钮必须有无障碍描述 [A5]。
2. **能用数字和图形，就不用句子。**
   - 两步路用剩余距离进度环 [L1]；Strava 用实时分段 [S1]。
   - 对我们来说：沿轨里程、剩余爬升、天气都可以用"数字 + 单位 + 小图标"表达，状态用色块加形状。
3. **渐进披露：常用的放在第一屏，其余默认收起。**
   - NN/g："Initially, show users only a few of the most important options"；但用户常用的必须放在第一屏，只有少数情况才需要展开 [N2]。
   - 对应到我们：轨迹详情只露出距离、爬升、用时、天气；其余收进"更多"。
4. **引导用"做一次"代替"读一遍"。**
   - Apple：交互式引导，或拆成就地提示，放在相关控件旁边；可以跳过，跳过后不再弹出 [A4]。
   - 对应到我们：首次打开不放介绍页；第一次出现某个功能时，在它旁边给一句提示。
5. **文案只说一件事，动词开头。**
   - Apple："If you can use fewer words, do so"；按钮用动词；不要为了俏皮牺牲清晰 [A6]。
   - 这和 v2 已有的规则一致："活动状态主句 ≤ 8 字；先说结果再说怎么办"。
6. **加载和空状态用占位图形，不用解释文字。**
   - Apple：尽快显示占位图形，能估算时长就用确定进度 [A7]。
7. **按场景调整语气和字数**：运动中简短直接，完成时可以多一句 [A6]。对应活动状态最简短，回看页可以多写一点。

## 6. 风格原型的候选方向（2–3 个）

三个方向都基于 M3 色彩角色、形状和字号体系；差别在品牌色、浮层材质，以及表现力用在哪里。

### 方向 A：「山野工具」——收着用的 M3 Expressive（推荐作为基线）
- **色彩**：低饱和的自然色（苔绿或岩灰）做 primary；偏离、危险用固定的高饱和橙红，并配形状；不用 dynamic color 做语义色。
- **浮层**：不透明的 `surfaceContainer` 色调面，大圆角；主要操作收进一个 `FloatingToolbar` 或 FAB 菜单 [D2]。
- **动效**：默认用标准动效；只有开始记录、结束记录、到达终点用 `MotionScheme.expressive()` 和形状变化 [D3]。
- **文字**：图标加 1–2 个字的标签；详情页渐进披露。
- **原型要验证**：强光下浮层和地图是否分得开；关键按钮是否一眼找到。

### 方向 B：「高对比仪表」——数据优先
- **色彩**：近黑或近白的面板加一个强调色（荧光黄或橙），追求最高对比；深色地图跟随系统。
- **数据**：等宽数字的大字占活动状态上半屏，进度用环或条（两步路 [L1]、Strava [S1]），几乎没有句子。
- **形状**：小圆角、粗分隔，"仪表"感；几乎不用动画，只保留触感反馈。
- **原型要验证**：戴手套、强光下的可读性；规划状态会不会显得太硬、太冷。

### 方向 C：「轻浮层」——借 Liquid Glass 的层次，不借透明
- **色彩**：中性底色加较亮的品牌蓝或青；浮层用约 90% 不透明度的色调面，加轻阴影或描边与地图分开（不做模糊）。
- **布局**：控件全部"浮"在全屏地图上，没有固定的顶栏和底栏，贴近 iOS 26 和 Gaia 2026.6 的观感 [A1][G1]。
- **原型要验证**：亮色卫星图或雪地底图下，浮层是否还能看清（A1 对透明款的警告）；Android 用户觉得是否"像 iOS"。

## 7. 要避免的做法

- 地图浮层用透明玻璃或实时模糊（A1：透明款只用于暗背景或加压暗层的背景）。
- 到处用弹跳、形状变化；把表现力用在地图浮层上（M1：功能第一）。
- 只靠颜色表达状态，或红绿配对（A2）。
- 偏离、危险、队友等语义色随壁纸变化（推断）。
- 小于 48dp 的点击区，靠文字撑大的按钮（D1）。
- 只有图标、没有标签的非通用图标（N1）；也不要走到另一个极端：大段说明文字（§5）。
- 首次打开连续好几屏介绍；引导里放条款（A4）。
- 工具界面里放广告或营销抽奖（L2）。
- 为了"看起来新"改动用户熟悉的主流程。Komoot 改版明确保留了主导航 [K1]，M1 也提醒不熟悉会抵消收益。

## 8. 来源

- [M1] Google Design，"Expressive Design: Google's UX Research"：<https://design.google/library/expressive-material-design-google-research>
- [M2] Google 官方博客，2025-05-13，Material 3 Expressive 发布：<https://blog.google/products/android/material-3-expressive-android-wearos-launch/>
- [D1] Android 开发者，提高应用的无障碍性（点击区、对比度）：<https://developer.android.com/guide/topics/ui/accessibility/apps>
- [D2] Compose Material 3 发布说明：<https://developer.android.com/jetpack/androidx/releases/compose-material3>
- [D3] Android 开发者博客，2025-05，Androidify：<https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html>
- [D4] Android 开发者，Live Update 通知：<https://developer.android.com/develop/ui/views/notifications/live-update>
- [A1] Apple HIG，Materials：<https://developer.apple.com/design/human-interface-guidelines/materials>
- [A2] Apple HIG，Accessibility：<https://developer.apple.com/design/human-interface-guidelines/accessibility>
- [A3] Apple HIG，Color：<https://developer.apple.com/design/human-interface-guidelines/color>
- [A4] Apple HIG，Onboarding：<https://developer.apple.com/design/human-interface-guidelines/onboarding>
- [A5] Apple HIG，Icons：<https://developer.apple.com/design/human-interface-guidelines/icons>
- [A6] Apple HIG，Writing：<https://developer.apple.com/design/human-interface-guidelines/writing>
- [A7] Apple HIG，Loading：<https://developer.apple.com/design/human-interface-guidelines/loading>
- [N1] Nielsen Norman Group，Icon Usability：<https://www.nngroup.com/articles/icon-usability/>
- [N2] Nielsen Norman Group，Progressive Disclosure：<https://www.nngroup.com/articles/progressive-disclosure/>
- [K1] Komoot 新闻稿，2025-09-15：<https://newsroom.komoot.com/254252-komoot-unveils-modern-design-as-part-of-ambitious-product-roadmap/>
- [K2] heise，2025-09-15（路线图：深色模式等）：<https://heise.de/-10644247>；BikeRadar：<https://www.bikeradar.com/news/komoot-redesign-2025>
- [K3] BikeRadar，2026-01-26：<https://www.bikeradar.com/news/komoot-apple-watch-app-jan-2026>
- [K4] App Store，komoot 2026.40.1 版本说明（2026-10-01），id447374873
- [S1] BikeRadar，2025-07-16，Strava 重做记录页：<https://www.bikeradar.com/news/strava-redesigned-record-feature>
- [T1] Advnture，AllTrails 夏季更新：<https://www.advnture.com/hiking/youll-always-be-a-step-ahead-alltrails-rolls-out-biggest-update-in-over-a-decade-with-6-new-features-heres-what-were-most-excited-about>；TechCrunch，2025-05-12：<https://techcrunch.com/2025/05/12/alltrails-debuts-a-80-year-membership-that-includes-ai-powered-smart-routes>（alltrails.com/press 返回 403，没有读到原文）
- [T2] App Store，AllTrails 26.9.50 版本说明（2026-10-01），id405075943
- [G1] App Store，Gaia GPS 2026.6 版本说明（2026-09-14），id1201979492
- [L1] App Store（中国区），两步路 9.2.0–9.2.4 版本历史：<https://apps.apple.com/cn/app/id646277024>
- [L2] App Store，两步路 9.2.7 版本说明（2026-09-27），id646277024
- [F1] App Store（中国区），六只脚 4.300.0–4.310.0 版本历史：<https://apps.apple.com/cn/app/id543465749>
