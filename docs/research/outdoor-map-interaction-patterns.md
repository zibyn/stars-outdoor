# 调研：户外地图应用的交互模式

调研日期：2026-09-29。对应 #62（#61 的子问题）。以 #2 的竞品调研为起点，不重复其功能 / 收费对比。

**来源说明**
- 只用公开一手来源：官方帮助中心、官方社区的官方账号帖、商店描述、平台设计规范与其源码。
- Gaia GPS、AllTrails、Komoot 的帮助中心有 Cloudflare 拦截，通过 Zendesk JSON API（`/api/v2/help_center/en-us/articles/<id>.json`）读正文。下文引用的是公开文章 URL。
- 帮助中心几乎只写"点哪里"，很少写"放在屏幕哪一侧、多大、什么动画"。凡是只能靠截图或体验才能确认的细节，一律标 **未核实**。
- 国内三家（两步路、六只脚、奥维）没有成体系的帮助中心。两步路用官方账号（"两步路官方小助手"等）在社区发的帖，多为 2014–2017 年，界面可能已改版，引用时注明年份。六只脚只核实到商店描述。奥维、CalTopo、Outdooractive 本次没有查到可用的交互细节，不纳入结论。
- m3.material.io 和 developer.apple.com 页面由 JS 渲染，直接抓取为空。Material 3 的数值取自 Jetpack Compose Material3 源码（固定提交 `dedb1f9`，简写 `CM3`）；Apple HIG 取自其公开 JSON 数据。Material 3 网站上关于"何种过渡用何种时长"的文字指引本次没有读到，标 **未核实**。

## 1. 摘要（可借鉴的共性）

1. **定位按钮是一个多态按钮，点一次居中跟随，再点一次切到朝向模式；图标本身表示状态。** Gaia 三态：空心箭头＝不跟随，实心箭头＝跟随（北朝上），实心箭头 + 视野扇形＝航向朝上 [G1]。拖动地图后自动退出跟随，按钮变回空心；AllTrails 则是拖动后才出现一个"回到中心"小箭头，点它会保留当前朝向和缩放 [A1]。
2. **"北朝上 / 行进方向朝上"的切换普遍放在指南针图标上。** 地图被旋转后右上角出现指南针，点它回到北朝上（Gaia [G1]）；AllTrails、Komoot 在导航 / 记录时用指南针图标切换两种朝向 [A1][K1][K2]。
3. **标注以"准星 / 图钉对准地图中心"为核心，跟随状态下中心就是当前位置。** 两步路：点"标注"默认取当前位置，可拖地图微调 [L1]；记录中地图中心出现图钉工具，再选拍照 / 文字 / 语音 / 视频 [L2]。Gaia 要标当前位置，是先点定位按钮让地图居中，再从 + 菜单建航点 [G5]。点地图另起一个点是次要路径：Gaia 点图弹出底部抽屉，抽屉里有"保存"[G3][G5]。AllTrails 在记录中从底部抽屉"Add Waypoint"，先选预设类型（水源、宿营点等），再显示坐标供核对 [A4]。
4. **自己的数据和平台图层是两套开关。** 平台内容（热力图、路网、天气、坡度）统一放进"图层"面板，作为叠加层开关 [A5][K4][S3]。自己的轨迹 / 路线 / 航点另有一套：按类型总开关 + 每条"眼睛"开关 + 文件夹批量开关，每条轨迹可单独改颜色 [G7][G8]。六只脚"同一目的地支持同时添加多条参考线路，不同线路不同颜色"[F1]。
5. **跟随路线时用颜色区分"计划"和"已走"。** AllTrails：计划路线绿色、已走过的蓝色 [A2]；Komoot：偏离路线时路线变黑，回到路线后恢复 [K2]。
6. **轻内容用底部抽屉，长流程和数据管理用整页。** 点地图、点轨迹 / 步道 pin、导航 / 记录中的所有次要操作，都在不遮住地图的底部抽屉里，上拉看更多（Gaia 点图抽屉 [G3]、AllTrails 步道卡片和 Navigate 抽屉 [A9][A1]、Strava 记录页抽屉 [S1]）。"已保存"列表、离线包管理是整页或 tab（Gaia Saved、AllTrails Saved > Areas）[G9][A8]。离线下载是"在地图上框选范围 → 命名页"[G9][A8]。
7. **记录是一个独立的地图状态，而不是另一个 App。** 入口多为底部导航的一个 tab（AllTrails "Navigate"、Strava "Record"、Komoot 菜单栏 "Record"）[A3][S1][K3]。开始后：大号开始 / 暂停按钮在底部，数据面板可滑动切换指标，次要操作收进抽屉或侧栏 [K1][K3][S1]。暂停 / 结束做了防误触：AllTrails 先"Pause"才出现"Finish"[A1]，Komoot Android 要长按 Pause [K1]。三家都提供"保持屏幕常亮"开关 [A1][K1][S1]。
8. **安全功能都挂在记录 / 导航状态里，开始记录时自动生效。** AllTrails Live Share、Strava Beacon 都从记录页底部抽屉打开 [A7][S2]；Komoot Live Tracking 开启后，开始活动时自动通知安全联系人 [K6]。分享页都显示最后更新时间和电量；失去信号时显示"最后已知位置"[A7][K6][S2]。两步路队伍对离线队员显示"尾迹"，用来推断其方向和位置 [L3]。本次查阅的海外四家都没有 app 内 SOS 按钮，Strava 明确说不会自动发求救 [S2]。
9. **底部抽屉的平台默认值可以直接用。** Compose Material3：标准抽屉（不挡地图、无遮罩）默认露出高度 56dp，最大宽度 640dp，状态为 Hidden / PartiallyExpanded / Expanded [M1][M2]。Material 组件库的半展开比例默认 0.5 [M4]。Apple：中档约为全高一半、全高一档，把手可点击循环档位 [H1]。触控目标至少 48×48dp、间距 8dp [M5]。

## 2. 对比表（只列交互，不列功能与收费）

| 维度 | Gaia GPS | AllTrails | Komoot | Strava | 两步路 | 六只脚 |
|---|---|---|---|---|---|---|
| 定位按钮 | 右下角 [G2]；三态循环 [G1] | 拖动后左下角出现回中箭头 [A1] | **未核实** | **未核实** | "我的位置"点一下锁定、再点解锁（2014）[L1]；位置 **未核实** | **未核实** |
| 朝向切换 | 定位按钮第二次点击；旋转后右上角指南针回北 [G1] | 红灰指南针图标切换 [A1] | 侧栏指南针图标 [K1][K2] | **未核实** | 可叠加大 / 小指南针，双指旋转（2017）[L4] | **未核实** |
| 标注当前位置 | 先居中，再 + 菜单建航点 [G5] | 记录中抽屉 → Add Waypoint → 选类型 [A4] | 记录中侧栏拍照 [K3] | **未核实** | 默认当前位置，可拖图微调 [L1]；记录中中心图钉 + 4 种类型 [L2] | "脚印"（文字 / 图片 / 视频）[F1]；按钮位置 **未核实** |
| 点地图 | 底部抽屉：海拔、坐标、天气、附近已存数据，可存航点 [G3] | 点步道 pin → 底部卡片 [A9] | 规划器中点路线出菜单 [K7] | **未核实** | **未核实** | **未核实** |
| 多条自己的轨迹 | 类型开关 + 眼睛 + 文件夹；每条可改色 [G7][G8] | 跟随时一次一条，可"Switch route"[A1] | **未核实** | **未核实** | 轨迹 / 路网颜色和透明度可调（2017）[L4] | 多条参考线路，不同颜色 [F1] |
| 平台热力图 / 路网 | **未核实** | 图层菜单（右上角叠方块图标）→ Overlays [A5][A6] | 图层面板：底图 + 运动图层 + 热力图可组合 [K4] | Maps tab 右侧图层图标，个人 / 全球热力图可同开 [S3] | 路网已改热力图（#2）[P2] | **未核实** |
| 记录入口 | 顶部统计栏录制键或 Trip tab [G6] | 底部导航 Navigate → Start [A3] | 菜单栏 Record [K3] | 底部导航 Record → 底部 Start [S1] | 地图 → 开始记录 → 选运动类型（2017）[L2] | 底部"记录"（**未核实**，仅见非官方教程） |
| 暂停 / 结束 | 点计时器 → 暂停 / 完成 / 删除 [G6] | 先 Pause 再出现 Finish [A1] | Android 长按 Pause [K1] | Pause → Finish [S1] | **未核实** | **未核实** |
| 位置分享 / 安全 | 官方不支持（#2）[P2] | Live Share，记录页抽屉 [A7] | Live Tracking，开始时自动通知 [K6] | Beacon，记录页抽屉 [S2] | 队伍：位置 + 尾迹 [L3]；一键求助（#2）[P2] | **未核实** |

## 3. 分项发现

### 3.1 首屏布局与控件位置

- **Gaia**（Android）主地图的元素 [G3][G4]：
  - 顶部统计栏，带录制按钮；长按可改显示的指标。
  - 地图信息 "i"、图层菜单、3D、全屏按钮、定位按钮。
  - 底部 tab：Map / Trip / Activity Feed / Saved / Settings / Discover。
  - "+" 创建菜单：区域、路线、拍照、航点、下载离线地图。**可在设置里选放左边还是右边**。统计栏、指南针、全屏、3D 按钮、比例尺都能单独关掉。
  - 有"全屏模式"隐藏底部菜单。
- **AllTrails**：图层入口是"任何地图右上角的叠方块图标"[A5]；回中按钮在左下角 [A1]；离线框选入口是地图右下角的向下箭头 [A8]。
- **Komoot**：导航 / 记录时，地图相关操作（图层、朝向、拍照）放在"侧栏"，导航控制放在底部面板 [K1][K3]。侧栏在左还是右 **未核实**。
- **Strava**：记录页 Start 在底部，右上角有地图 / 数据切换 [S1]；Maps tab 的图层图标在右侧 [S3]。
- **两步路**：2017 年官方帖说队伍图标在"地图右下角"[L5]。现版本布局 **未核实**。
- **共性**：
  - 右上角放图层 / 指南针；右下角放定位和主要创建动作。
  - 可选控件允许隐藏（Gaia）。把"+"放在哪一侧交给用户选，是解决左右手问题的现成做法 [G4]。
  - 按钮是纯图标还是带文字标签，帮助中心不写，**未核实**。

### 3.2 定位按钮与跟随 / 朝向

- Gaia 的做法最完整 [G1]：
  - 按钮三态：Off（空心箭头，地图不动、北朝上）→ Compass（实心箭头，地图跟随、北朝上、箭头随手机转）→ Course（实心箭头 + 视野扇形，地图跟随并随手机旋转，箭头恒朝上）。
  - 双指旋转地图后，右上角出现指南针图标，红针指北，点它回北朝上并消失。
  - 注意：Gaia 的"Compass Mode"是北朝上跟随，不是地图旋转。命名容易混淆，我们做文案时应直接用"跟随 / 朝向"。
- AllTrails 用两个控件拆开 [A1]：
  - 导航时默认居中、北朝上；点红灰指南针切到行进方向朝上，再点切回。
  - 平移或缩放后，左下角出现白色回中箭头，点它回到当前位置，"保留当前朝向和缩放"。
- Komoot：导航和记录时，侧栏指南针图标在北朝上与行进方向朝上之间切换 [K1][K2][K3]。
- 两步路（2014）："我的位置"点一下锁定、再点解锁 [L1]。
- **可借鉴**：
  - 单按钮循环"居中跟随 → 朝向"，靠图标形态（空心 / 实心 / 带扇形）表示状态。
  - 用户拖动地图即退出跟随。
  - 地图不是北朝上时，才显示一个可点的指南针用来回北。

### 3.3 标注方式

- 当前位置标注有两种实现：
  - "地图中心准星 + 跟随态"：两步路默认当前位置，拖地图可改 [L1]；记录中地图中心出现图钉工具 [L2]。Gaia 要求先点定位让地图居中再建航点，位置可拖圆环调整 [G5]。
  - "直接取 GPS 坐标"：AllTrails 在记录中从抽屉进入 Add Waypoint，先选预设类型，再显示坐标供核对，可设隐私 [A4]。是否就是当前 GPS 点，文档没明说，**未核实**。
- 地图点选是另一条路径：Gaia 点地图弹出底部抽屉，顶部"保存"即成航点 [G3][G5]。
- 记录中与非记录中的差异：
  - AllTrails 的记录中航点挂在本次活动上，不挂在所跟随的路线上 [A4]。
  - 两步路记录中的标注支持拍照 / 文字 / 语音 / 视频 [L2]。
  - Komoot 记录中侧栏只有"拍照"[K3]。
- **可借鉴**：
  - 一键标注默认用当前 GPS 位置，并显示精度 / 坐标供核对。
  - 允许拖动微调，作为从"一键"到"精确"的过渡。
  - 预设少量类型（水源、岔路口、宿营点），比自由输入更适合活动中操作。

### 3.4 多轨迹叠加与视觉层级

- 自己的数据：
  - Gaia 默认显示所有已存数据，用"Map Overlays"按类型批量开关，用"眼睛"逐条开关，文件夹一键开关整组 [G7]。
  - 每条轨迹 / 路线可从已存列表的缩略图改颜色 [G8]；结束记录时也可改色 [G6]。
- 平台数据：
  - AllTrails 把个人热力图、社区热力图、天气、坡度等都作为 overlay，从图层菜单打开；地图类型一次只能选一个，extras 可多选 [A5][A6]。
  - Komoot：一个底图 + 一个运动图层 + 可选热力图，可以组合 [K4]；热力图"越亮越热门"[K5]。
  - Strava：个人与全球热力图可同时打开，个人热力图可以改颜色 [S3]。
  - 两步路可调"轨迹路网颜色"和"透明度"，使其"不遮挡其他地图要素"（2017）[L4]。
- 跟随状态的层级：AllTrails 计划线绿、已走线蓝 [A2]；Komoot 偏航时路线变黑 [K2]。
- 各家如何设置默认颜色和线宽来区分"自己的轨迹"和"平台路网 / 热力图"，帮助中心没有说明，**未核实**。
- **可借鉴**：
  - 平台内容（路网 / 热力图）进"图层"面板，默认弱化并可调透明度。
  - 自己的轨迹进"我的轨迹"列表，逐条眼睛开关 + 逐条颜色。
  - 当前跟随的轨迹和正在记录的轨迹用固定的高对比色。

### 3.5 入口与展开方式

| 内容 | 常见展开方式 | 依据 |
|---|---|---|
| 点地图 / 点对象的详情 | 底部抽屉（可上拉看全） | Gaia [G3]，AllTrails 步道卡片 [A9] |
| 图层 | 从地图角落图标打开的面板（抽屉还是整页，**未核实**） | [A5][K4][S3][G2] |
| 记录 / 导航中的次要操作（暂停 / 结束、设置、分享位置、航点、海拔图、GPS 详情） | 底部抽屉，上拉展开 | AllTrails [A1][A7]，Strava [S1][S2]；Komoot 底部面板 [K1] |
| 已存轨迹 / 路线 / 航点列表 | 整页 tab，有"Map"按钮回地图 | Gaia [G7]，AllTrails Saved [A2] |
| 离线下载 | 地图上框选 → 命名页；管理在 Saved 整页 | Gaia [G9]，AllTrails [A8] |
| 队伍 | 两步路：地图右下角队伍图标或"我 → 队伍"（2017），展开形式 **未核实** | [L3][L5] |

**可借鉴**：看完要回到地图的内容用抽屉，比如详情、图层、队伍概览、记录中的操作。要编辑、管理或长列表的内容用整页，比如轨迹库、离线包管理、队伍设置。

### 3.6 规划状态与记录状态

- 规划：
  - Gaia 明说网站用来规划，手机 App 用于活动中 [G2]。
  - Komoot 的规划器是单独入口（Routes → + Plan new），规划后"Navigate"进入导航 [K7][K1]。
  - AllTrails：先在详情页点"Map"把路线载入 Navigate，再按 Start [A2]。
- 记录中首屏的变化：
  - 数据面板：Komoot 可左右滑切换指标，点指令或数据可放大 [K1][K3]；Strava 可在地图和全屏数据之间切换 [S1]；Gaia 顶部统计栏变成计时器 [G6]。
  - 大按钮：Start / Pause 在底部 [S1][A3]。
  - 防误触：AllTrails "revamped the process for pausing your activity" 以防误暂停 [A1]；Komoot Android 长按 Pause [K1]。
  - 屏幕：三家都提供"保持屏幕常亮"[A1][K1][S1]。App 内"锁屏 / 防误触锁"本次没有查到，**未核实**。
  - 自动暂停：AllTrails 可选，Komoot 默认开启 [A1][K3]。
  - 自动提示：AllTrails 检测到用户已在沿路线移动但没按 Start 时，会提示开始记录 [A1]。
- **可借鉴**：
  - 记录是同一张地图上的一种状态：底部换成大号暂停 / 结束和一行关键数据，次要操作收进抽屉。
  - 结束走两步（先暂停、再结束），避免误触。
  - 提供屏幕常亮开关。

### 3.7 安全相关

- 分享位置：
  - AllTrails Live Share：Navigate 底部抽屉 → Live Share，开始记录后才共享 [A7]。失去信号时，对方会看到"信号不稳"的提示和最后位置，可订阅恢复在线的短信 [A7]。
  - Strava Beacon：记录页上拉抽屉 → "Share Live Location"，对方收到短信链接，能看到当前位置、电量等 [S2]。没有自动求救，无蜂窝网时不更新 [S2]。
  - Komoot Live Tracking：设置里打开后，开始活动时自动通知安全联系人；记录中也可开关；对方能看到电量和"最后更新时间"[K6]。
- 队伍与失联：
  - 两步路队伍：成员位置可带名字和定位时间标签显示；点成员看距离、经纬度、海拔、速度、更新时间；离线成员显示"尾迹"，用来"预计队员当前所在的方向和位置"（2017）[L3]。
  - 两步路"一键求助"的呈现方式 **未核实**（#2 记录有此功能 [P2]）。
- **可借鉴**：
  - 安全动作放在记录状态的一级位置，并在开始记录时自动生效。
  - 所有"他人位置"都标上次更新时间；失联时显示"最后已知位置 + 多久前"，不要直接消失。
  - SOS 类按钮各家都没有公开的呈现规范可参照，要由我们自己定：文字按钮、长按或二次确认。

### 3.8 抽屉档位与动画（平台规范）

- Material 3 两种抽屉：
  - 标准抽屉与主界面共存，可以同时查看和操作两边，常用于主区域"频繁滚动或平移"的场景，适合地图 [M1]。
  - 模态抽屉在遮罩之上，阻断其余操作，点外部即关闭 [M4]。
- Compose Material3 默认值：
  - `BottomSheetDefaults.SheetPeekHeight = 56.dp`，`SheetMaxWidth = 640.dp` [M2]。
  - `SheetValue` 有 `Hidden` / `PartiallyExpanded` / `Expanded` 三个档位；`skipPartiallyExpanded` 可跳过中档 [M2]。
  - 拖动吸附动画默认 `tween(300ms, FastOutSlowInEasing)`。展开和收起在 `BottomSheetScaffold` 中分别用 `motionScheme.defaultSpatialSpec()` 和 `fastEffectsSpec()` [M2][M1]。
- Material 组件库（View）：`halfExpandedRatio` 默认 0.5；标准抽屉默认不可隐藏，模态默认可隐藏 [M4]。
- Material 3 时长 token [M3]：
  - short1–4：50 / 100 / 150 / 200ms
  - medium1–4：250 / 300 / 350 / 400ms
  - long1–4：450–600ms
  - emphasized 缓动为 `cubic-bezier(0.2, 0, 0, 1)`
  - 各时长适用于哪类过渡的文字指引 **未核实**。
- Apple HIG [H1]：
  - 系统档位有 medium（约全高一半）和 large 两个，可自定义。
  - iPhone 上建议支持 medium，做渐进展示。
  - 可调高度的抽屉应带把手（grabber）。把手可点击循环档位，并支持 VoiceOver。
  - 复杂或长流程考虑改用全屏。
- 触控目标：至少 48×48dp，间距 ≥ 8dp（约 9mm）[M5]。戴手套场景没有官方数值，建议取更大，属于推断。

## 4. 未覆盖 / 局限

- 纯图标还是图标 + 文字、按钮尺寸、实际的左右分布，需要截图或真机核对；帮助中心不写，本文件大多标 **未核实**。
- 国内三家的一手资料陈旧（两步路官方帖多为 2014–2017 年），六只脚只有商店描述，奥维没查。
- CalTopo、Outdooractive 没有纳入。

## 5. 来源

**Gaia GPS**
- [G1] Locate and Orient Yourself on the Map：https://help.gaiagps.com/hc/en-us/articles/360047951533
- [G2] How to Use Gaia GPS：https://help.gaiagps.com/hc/en-us/articles/9067661557399
- [G3] Using the Main Map on Android：https://help.gaiagps.com/hc/en-us/articles/4409332168727
- [G4] Adjusting the Main Map Display Layout in Android：https://help.gaiagps.com/hc/en-us/articles/20017997195927
- [G5] Create and Edit Waypoints in the App：https://help.gaiagps.com/hc/en-us/articles/360050583453
- [G6] Record and Resume Tracks：https://help.gaiagps.com/hc/en-us/articles/360048651574
- [G7] Show or Hide Saved Data on the Map in the App：https://help.gaiagps.com/hc/en-us/articles/360049505234
- [G8] Change the Color Of Your Saved Routes and Tracks on Android：https://help.gaiagps.com/hc/en-us/articles/115003638788
- [G9] Download Maps for Offline Use：https://help.gaiagps.com/hc/en-us/articles/360047131513

**AllTrails**
- [A1] Navigate: feature overview：https://support.alltrails.com/hc/en-us/articles/360059000272
- [A2] Using the Navigate feature：https://support.alltrails.com/hc/en-us/articles/37228358315668
- [A3] How to track and record an activity：https://support.alltrails.com/hc/en-us/articles/360019244391
- [A4] Add a waypoint to your activity recording：https://support.alltrails.com/hc/en-us/articles/50160193271700
- [A5] AllTrails map types, overlays, and extras：https://support.alltrails.com/hc/en-us/articles/37228180990228
- [A6] Community Heatmaps：https://support.alltrails.com/hc/en-us/articles/36898308536852
- [A7] How to use Live Share：https://support.alltrails.com/hc/en-us/articles/37212858771348
- [A8] Download custom areas for offline use：https://support.alltrails.com/hc/en-us/articles/37758009767444
- [A9] How to use map view to search for trails：https://support.alltrails.com/hc/en-us/articles/360034969432

**Komoot**
- [K1] Navigate a saved route：https://support.komoot.com/hc/en-us/articles/10207935661338
- [K2] Navigation FAQ：https://support.komoot.com/hc/en-us/articles/10605424981402
- [K3] Record an activity：https://support.komoot.com/hc/en-us/articles/10207928747546
- [K4] Map Layers：https://support.komoot.com/hc/en-us/articles/10194649347098
- [K5] Heatmaps FAQ：https://support.komoot.com/hc/en-us/articles/9468742939802
- [K6] Live Tracking：https://support.komoot.com/hc/en-us/articles/10365451254298
- [K7] New Route Planner on Android：https://support.komoot.com/hc/en-us/articles/11223871075738

**Strava**
- [S1] Recording an Activity：https://support.strava.com/en-us/articles/15402137-recording-an-activity
- [S2] Strava Beacon：https://support.strava.com/en-us/articles/15401829-strava-beacon
- [S3] Personal Heatmaps：https://support.strava.com/en-us/articles/15402028-personal-heatmaps

**两步路**（社区官方账号帖）
- [L1] 两步路·户外助手常见问题解答（一），APP助手管家，2014-05-29：https://www.2bulu.com/community/gotohuatinfo.htm?id=qXWu1oO5tss%3D
- [L2] #APP功能#标注点（一），两步路官方小助手，2017-08-15：https://www.2bulu.com/community/gotohuatinfo.htm?id=19452
- [L3] #APP功能#组队功能（一），两步路官方小助手，2017-06-29：https://www.2bulu.com/community/gotohuatinfo.htm?id=17044
- [L4] 这些实用的地图小功能，你知道用吗？，两步路官方小助手，2017-08-21：https://www.2bulu.com/community/gotohuatinfo.htm?id=19728
- [L5] 队伍功能说明，两步路官方小助手，2017-10-12：https://www.2bulu.com/community/gotohuatinfo.htm?id=115795

**六只脚**
- [F1] App Store 描述：https://apps.apple.com/cn/app/id543465749

**平台规范**
- [M1] Compose Material3 `BottomSheetScaffold.kt`（`dedb1f9`）：https://github.com/androidx/androidx/blob/dedb1f95f391f7754bcd86395be719fae7159a09/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/BottomSheetScaffold.kt
- [M2] Compose Material3 `SheetDefaults.kt`（`dedb1f9`）：https://github.com/androidx/androidx/blob/dedb1f95f391f7754bcd86395be719fae7159a09/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/SheetDefaults.kt
- [M3] Compose Material3 `MotionTokens.kt`（`dedb1f9`）：https://github.com/androidx/androidx/blob/dedb1f95f391f7754bcd86395be719fae7159a09/compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/tokens/MotionTokens.kt
- [M4] Material Components Android `BottomSheet.md`：https://github.com/material-components/material-components-android/blob/master/docs/components/BottomSheet.md
- [M5] Android 无障碍帮助：触控目标大小：https://support.google.com/accessibility/android/answer/7101858
- [H1] Apple HIG，Sheets：https://developer.apple.com/design/human-interface-guidelines/sheets

**本仓库**
- [P2] #2 竞品调研：https://github.com/zibyn/stars-outdoor/blob/research/competitors/docs/research/competitors.md
