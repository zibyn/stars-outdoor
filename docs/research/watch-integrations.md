# 调研：佳明、高驰、颂拓、苹果表的双向集成

调研日期：2026-09-29。对应 issue #82。

**来源说明**
- 只用一手来源：厂商开发者文档、帮助中心、开发者协议、官方 SDK 包、官方商店页。
- 二手来源（媒体、第三方聚合商）只作线索，结论里标 **未核实**。没有出处的细节同样标 **未核实**。
- 佳明开发者站（developer.garmin.com）的指南页由脚本渲染，抓不到正文。能读到的是 API 参考页、FAQ、开发者协议 PDF，以及 Maven Central 上的 Android SDK 包本身。
- 高驰帮助中心有 403 拦截，正文是通过 Zendesk JSON API（`/api/v2/help_center/en-us/articles/<id>.json`）读到的。下文引用的仍是公开文章 URL。

## 1. 结论

**可行性矩阵**（✅ 有官方途径；⚠️ 有途径但有硬门槛或未核实；❌ 没有官方途径）

| | 佳明 Garmin | 高驰 COROS | 颂拓 Suunto | 苹果 Apple Watch |
|---|---|---|---|---|
| 路线进表（格式） | ✅ Courses API 推送；用户也可自行导入 GPX [G1] | ✅ Partner API `route/push`，GPX/KML [C1] | ✅ Cloud API 推路线，经 Suunto App 同步到表 [S1] | ⚠️ 系统无 GPX 导入（**未核实**），要靠自己的 watchOS App |
| 表 → App：活动后同步（云 API） | ✅ Activity API，FIT/GPX/TCX [G2]；**仅限企业**，免费，约 2 个工作日答复 [G3] | ✅ Partner API，FIT 下载 + Webhook [C1]；**需注册公司 + 已有用户规模** [C1] | ✅ Cloud API，FIT 导出 [S1]；**仅限公司/组织**，约 2 周答复 [S1] | ⚠️ HealthKit 只能在 iPhone 上读 [A2]，我们的 Android App 读不到 |
| 表 → App：中国区 | ⚠️ 有独立的"中国大陆服务器"申请表和中国版协议（佳明上海签约、个人信息出境标准合同）[G4][G5]；中国区具体开放哪些 API **未核实** | ⚠️ 国内 FAQ 只列出悦跑圈、咕咚、Keep 等已合作平台 [C4]，Partner API 是否覆盖国区账号 **未核实** | ⚠️ 官方 FAQ："准备好后联系我们，我们会给你中国 API 的访问权限" [S1] | ⚠️ 国区 App Store 上架需 ICP 备案 [A5，**未核实**] |
| 表 → App：活动中实时（定位/距离） | ✅ Connect IQ 数据栏或 App 读位置/距离，经蓝牙发到手机 App [G6][G7] | ❌ 没有表端开发平台 [C1][C3] | ⚠️ SuuntoPlus 能读 GPS/距离、能连 BLE 设备 [S3]；与手机 App 通信 **未找到** 官方接口 | ✅ watchOS 运动会话 + WatchConnectivity [A3][A4]，但只到 iPhone |
| App → 表：显示我们的信息（沿轨里程、队友、求助、消息） | ✅ Connect IQ 可接收手机消息并显示 [G6] | ❌ 只能推路线和训练计划 [C1] | ⚠️ 自研 SuuntoPlus 应用可显示自算数据 [S3]，但拿不到队友数据（**未核实**）；SuuntoPlus 指南是预先同步的静态内容，不能活动中更新 [S4] | ✅ 自己的 watchOS App，但前提是先有 iOS App |
| 表端开发门槛 | 免费 SDK，Monkey C；个人可上架；商店审核 [G8] | — | JavaScript + HTML，VS Code 插件；2026-03 起向所有开发者开放，上架需加入合作伙伴计划并由颂拓审核 [S2][S3] | Swift/SwiftUI，Apple 开发者账号，App Store 审核 |
| 表端中国区 | ✅ 有中国区 Connect IQ 商店 apps.garmin.cn [G9]；中国版 Garmin Connect 与国际版同包名 [G10]；中国区付费应用不可用（论坛，**未核实**） | — | 中国区是独立 App（`com.stt.android.suunto.china`）[S5]；SuuntoPlus 商店在国区是否可用 **未核实** | 需 iPhone；国区上架需 ICP 备案 [A5，**未核实**] |
| 耗电 | **未核实**（没有官方数字） | — | **未核实** | **未核实** |

**要点**
1. **佳明是唯一值得做深度双向集成的一家。** 一个 Connect IQ 数据栏就能同时做到"表 → 手机实时传位置和距离"和"手机 → 表显示队友、求助、消息"。前提是用户手机上装了 Garmin Connect App，它是蓝牙中转 [G7]。
2. **活动后同步，三家都有云 API，但全部要求企业资质。** 佳明、颂拓明确不对个人开放 [G3][S1]；高驰还要求"已有用户规模" [C1]。高驰另有免申请的 MCP 通道，但没有 Webhook、没有 GPX 路线，也没有双向活动同步 [C2]。能否拿到轨迹点 **未核实**。
3. **中国区是真正的门槛。** 佳明中国是另一套服务器，签的是另一份协议（佳明上海，适用中国法律，个人信息出境要签标准合同）[G4][G5]。颂拓的中国 API 要单独申请 [S1]。高驰国区是否开放 API 没找到。
4. **高驰没有表端开发平台**，只能推路线、拿活动数据；"App → 表"只能做到路线级别。
5. **颂拓的表端能力在变强**（2026-03 起向所有开发者开放 SuuntoPlus 运动应用，支持 BLE 传感器 [S2][S3]），但没有文档化的"与手机 App 通信"接口。实时双向目前 **未核实/不可行**。
6. **苹果表必须配 iPhone** [A1]。HealthKit 和 WatchConnectivity 都只在 iOS 上 [A2][A4]。我们是 Android 优先，苹果表集成的前提是先有 iOS App，所以在 iOS App 立项前排除。
7. **Health Connect 是 Android 上零资质的备选**：媒体报道佳明、高驰、颂拓都会把运动写进 Health Connect。但是否包含 GPS 轨迹、气压海拔 **未核实**；国内无 GMS 的手机上能否用 **未核实**。

**建议顺序**：(1) 路线进表：先让用户导出 GPX/FIT 自行导入，零成本；(2) 佳明 Connect IQ 数据栏（实时双向，个人开发者即可上架，中国区有商店）；(3) 公司主体就绪后，再申请佳明中国 + 颂拓中国的云 API，做活动后同步；(4) 苹果表等 iOS App 立项后再做。

## 2. 各家细节

### 2.1 佳明 Garmin

**路线进表**
- Courses API："Publish courses to make them available to users for automatic syncing to their compatible wearable or cycling computer" [G1]。
- 用户也可以在 Garmin Connect 里导入 GPX 等文件后同步到表，具体支持的格式 **未核实**（支持页由脚本渲染）。

**表 → App，活动后**
- 开发者计划包括 Health、Activity、Women's Health、Training、Courses 五个 API [G1]。
- Activity API 提供 .FIT、GPX、.TCX 格式的完整活动文件 [G2]，支持 Ping/Pull 或 Push 两种接入方式 [G2]。FIT 文件里通常带 GPS、海拔、心率，但表型和设置不同，具体字段会不同（**未核实**逐项）。
- 资质："available for enterprise use……only for business use"；"no licensing or maintenance fees"；"confirm the status of your application within two business days"；部分指标商用"may require a license fee payment or minimum device order quantity" [G3]。
- 中国区：
  - 有单独的《Garmin Connect 开发者计划申请表（中国大陆服务器）》[G4]。页面由脚本渲染，表单字段 **未核实**。
  - 中国版协议的签约方是 "Garmin China Shanghai RHQ Co., Ltd." [G5]。许可"solely for internal business purposes"，须遵守中国监管 [G5 §4.1、§5.5]。个人数据出境时要签《个人信息出境标准合同》[G5 §15.9]。
  - 结论：中国区能申请，但要中国公司主体。国际区和中国区账号各自独立，要分别对接（**未核实**是否能用同一套凭据）。

**表 → App，活动中实时**
- Connect IQ `Communications.transmit()` 通过蓝牙向手机发数据，`registerForPhoneAppMessages()` 接收手机消息。两者都支持"Data Field、Watch App、Widget、Background"等类型 [G6]。数据栏在前台使用该模块，要求 API 5.0.0 起 [G6]。
- 数据栏跑在原生徒步活动里：原生活动照常记录 GPS/气压/心率；数据栏把位置和距离发给我们的 App，同时显示我们的 App 发来的内容。用户不用换掉原生活动，是最合适的形态。数据栏读取 `Activity.Info` 里位置和距离字段的细节 **未核实**。
- `Position.enableLocationEvents` 只允许 Device App 和 Widget 使用 [G7a]。如果要自己开 GPS，就得做成完整 App，那样会替代原生活动。
- Android 端用 `com.garmin.connectiq:ciq-companion-app-sdk`（2.2.0，Maven Central）[G7]。拆开 AAR 可以看到，SDK 绑定的是 `com.garmin.android.apps.connectmobile` 的 `ConnectIQService`，没装时会跳转 `market://details?id=com.garmin.android.apps.connectmobile` [G7]。也就是说，**手机上必须装 Garmin Connect App**，由它做蓝牙中转。
- 中国版 Garmin Connect 在应用宝上的包名同样是 `com.garmin.android.apps.connectmobile`，开发者是上海佳明航电 [G10]，因此 SDK 理论上可用。实机 **未核实**。
- 手机不开 GPS 算沿轨里程：拿表的定位就够了。发送频率、蓝牙延迟、丢包 **未核实**，需要原型实测。

**App → 表**
- 手机 App 发消息 → 数据栏/App 在 `registerForPhoneAppMessages` 回调里收到 → 绘制到表上 [G6]。沿轨里程、队友距离、一键求助提醒、短消息都能这样做。
- 数据栏屏幕小、内存小，只适合显示几个数字或一行文字。复杂界面（队友列表、消息）要做成 Watch App，而 Watch App 会替代原生活动。具体内存上限 **未核实**。
- 开发与上架：
  - SDK 免费，语言是 Monkey C。
  - 有 App Review Guidelines [G8]。个人开发者可以上传（**未核实**，指南页脚本渲染）。
  - 中国区有独立商店 apps.garmin.cn [G9]。
  - 论坛非官方回复说中国区卖家用不了佳明的收费功能 [G11，**未核实**]。
- 耗电：没有官方数字，**未核实**。

### 2.2 高驰 COROS

- **Partner API** [C1]：
  - 要求："Established platform with demonstrated user base"、"Registered company with authorized technical representative"。
  - 能力：OAuth 2.0 多用户；Webhook 约 5 分钟推送；FIT 下载（`getWorkoutDetailFit`）；`route/push`（GPX/KML，≤50 MB）；训练计划推送；1000 次/分钟。
  - 查询限制：单次最多 30 天，最多回溯 3 个月。
  - 申请：邮件 api@coros.com 并填表 [C1][C3]。
- **COROS MCP** [C2]：
  - 免申请，OAuth 2.0，单用户，22 个数据字段。
  - 明确列出的限制："No webhook"、"No GPX route import/export"、"No two-way activity sync"。
  - 是否含轨迹点 **未核实**；对国区账号是否可用 **未核实**。
- **表端开发**：高驰没有第三方表端应用平台。官方文档里找不到 SDK（**未找到**）。媒体普遍这么描述 [C5，**未核实**]。所以"活动中实时"和"表上显示我们的信息"都做不到。
- **中国区**：
  - 国区 FAQ 列出的第三方平台是悦跑圈、咕咚、咪咕善跑、马拉马拉、郁金香、Keep [C4]，没有提 API 申请。
  - 国区有独立 App（`com.yf.smart.coros.dist` 同时出现在 Google Play 和应用宝）[C6]，账号是否分区、Partner API 是否覆盖国区 **未核实**。

### 2.3 颂拓 Suunto

- 2022 年 Amer Sports 把颂拓出售给中国公司 Liesheng（广东）[S6]。
- **Cloud API**（API Zone）[S1]：
  - 资质："companies/organizations……we do not provide this for personal use"；合作伙伴计划审核约两周。
  - 能力：取 workouts（FIT）与日常活动，推 routes 和 workouts；"routes are shown in Suunto App and can be then synched to Suunto watches for navigation"。
  - 中国区："Once you are ready, contact us. We will then provide you the access to our Chinese API." 说明国区是另一套 API。
- **SuuntoPlus 运动应用** [S2][S3]：
  - 2026-03-11 起向"Developers, athletes, and the wider Suunto community"开放。
  - 开发：JavaScript（ES5）+ HTML 模板，VS Code 插件 SuuntoPlus Editor。
  - 能读 GPS、心率、海拔、距离、速度、气压等，支持"BLE Device Connection"。
  - 上架：经合作伙伴计划提交、颂拓审核后进入 SuuntoPlus Store。
  - 与手机 App 通信、联网请求：官方页面 **未找到**。理论上可以让我们的 App 当 BLE 外设、由 SuuntoPlus 应用去连，**未核实**，而且蓝牙可能和 Suunto App 的连接冲突（**未核实**）。
- **SuuntoPlus 指南**（Guides）[S4]：合作方通过 API 推送 JSON 配置，活动前同步到表，活动中按时间/距离/位置切换步骤。**不能在活动中实时更新**，所以只适合放"参考轨迹分段"之类的静态内容。
- **中国区**：
  - 国区是独立 App `com.stt.android.suunto.china` [S5]。
  - SuuntoPlus 商店、Editor 在国区是否可用，**未核实**。

### 2.4 苹果 Apple Watch

- 设置和使用 Apple Watch 必须配 iPhone（watchOS 26 要求 iPhone 11 及以上、iOS 26）[A1]。**Android 用户根本用不了 Apple Watch**。所以对 Android 优先的我们，苹果表的受众只可能是"将来的 iOS 版用户"。
- 表 → App：
  - HealthKit 提供 `HKWorkoutRoute`（运动路线）等样本 [A2]，平台只有 iOS/iPadOS/watchOS/macOS 等 Apple 系统 [A2]。
  - 实时：watchOS 上用 `HKWorkoutSession`/`HKLiveWorkoutBuilder` 运行运动会话 [A3]，用 WatchConnectivity `sendMessage` 立即发给配对的 iPhone [A4]。
- App → 表：只能做自己的 watchOS App（同样通过 WatchConnectivity）。WorkoutKit 只做"workout compositions"，不含路线 [A6]。
- 上架：国区 App Store 要 ICP 备案号 [A5，**未核实**]。我们的 Android 版本来就要备案，这一条不算额外门槛。
- 耗电：**未核实**。

### 2.5 Android 的 Health Connect（附带）

- 媒体报道佳明在 Android 14+ 上单向把步数、心率、运动（类型、时长、距离）写进 Health Connect [H1，**未核实**]；高驰、颂拓也列出了 Health Connect 同步 [H2，**未核实**]。
- Health Connect 有 `ExerciseRoute`，但各家是否写入路线、气压海拔 **未核实**。国产手机无 GMS 时能否使用 **未核实**。
- 如果能用，这是唯一不需要企业资质的"表 → App 活动后同步"途径，值得用一台佳明表实测。

## 3. 来源

**佳明**
- G1：<https://developer.garmin.com/gc-developer-program/overview/>
- G2：<https://developer.garmin.com/gc-developer-program/activity-api/>
- G3：<https://developer.garmin.com/gc-developer-program/program-faq/>
- G4：<https://www.garmin.cn/zh-CN/forms/GarminConnectDeveloperAccess-China/>（页面标题"Garmin Connect 开发者计划申请表（中国大陆服务器）"；表单正文由脚本渲染，未读到）
- G5：<https://www8.garmin.com/en-US/GARMINCONNECTDEVELOPERPROGRAMAGREEMENT/GARMINCONNECTDEVELOPERPROGRAMAGREEMENT_EN.pdf>（第 1 页签约方；§4.1、§5.5、§15.9、附件 A）
- G6：<https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications.html>（`transmit`、`registerForPhoneAppMessages`、`makeWebRequest` 的 Supported App Types；"made available to foreground data fields with API 5.0.0"）
- G7：<https://github.com/garmin/connectiq-android-sdk>（提交 `728189b`）；AAR：<https://repo1.maven.org/maven2/com/garmin/connectiq/ciq-companion-app-sdk/2.2.0/>（`AndroidManifest.xml`、`com/garmin/android/connectiq/ConnectIQ.class` 中的 `com.garmin.android.apps.connectmobile` 字符串）
- G7a：<https://developer.garmin.com/connect-iq/api-docs/Toybox/Position.html>（"Only Device Apps and Widgets may use this API"）
- G8：<https://developer.garmin.com/connect-iq/app-review-guidelines/>（脚本渲染，只确认页面存在）
- G9：<https://www.garmin.com.cn/products/apps/connect-iq/>、<https://apps.garmin.cn/>
- G10：<https://sj.qq.com/appdetail/com.garmin.android.apps.connectmobile>
- G11：**未核实**，论坛非官方回复：<https://forums.garmin.com/developer/connect-iq/f/discussion/432251/how-to-apply-for-publishing-an-app-in-the-china-region>

**高驰**
- C1：<https://support.coros.com/hc/en-us/articles/53181766856724-Partner-API-Access>（更新于 2026-09-28）
- C2：<https://support.coros.com/hc/en-us/articles/53181619102996-Build-on-COROS-MCP>（更新于 2026-09-10）
- C3：<https://support.coros.com/hc/en-us/articles/17085887816340-Submit-an-API-Application>、<https://support.coros.com/hc/en-us/articles/53181260265492-Connect-Your-Data>
- C4：<https://faq.coros.com/help/6723a1cf73c5e5de43c50e50/635a319ad2abfcac3946be40?locale=zh-CN>
- C5：**未核实**：<https://the5krunner.com/2026/04/24/coros-wahoo-partnership/>
- C6：<https://sj.qq.com/appdetail/com.yf.smart.coros.dist>、<https://play.google.com/store/apps/details?id=com.yf.smart.coros.dist>

**颂拓**
- S1：<https://apizone.suunto.com/faq>
- S2：<https://www.suunto.com/sports/News-Articles-container-page/open-suuntoplus-built-by-the-community.-powered-by-suunto>（2026-03-11）
- S3：<https://apizone.suunto.com/suuntoplus>、<https://apizone.suunto.com/suuntoplusEditor>
- S4：<https://apizone.suunto.com/suuntoplus-guide-description>
- S5：<https://sj.qq.com/appdetail/com.stt.android.suunto.china>
- S6：<https://www.amersports.com/newsroom/amer-sports-develops-its-brand-portfolio-divests-suunto-to-liesheng/>

**苹果**
- A1：<https://support.apple.com/en-us/118490>、<https://support.apple.com/guide/watch/apdde4d6f98e/watchos>
- A2：<https://developer.apple.com/documentation/healthkit/hkworkoutroute>、<https://developer.apple.com/documentation/healthkit>
- A3：<https://developer.apple.com/documentation/healthkit/hkworkoutsession>、<https://developer.apple.com/documentation/healthkit/hkliveworkoutbuilder>
- A4：<https://developer.apple.com/documentation/watchconnectivity/wcsession/sendmessage(_:replyhandler:errorhandler:)>
- A5：**未核实**（Apple 官方帮助页未读到，以下为二手来源）：<https://developer.apple.com/forums/thread/743661>、<https://www.appfilingchina.com/blog/app-filing-number-is-needed-for-apps-to-be-listed-on-the-apple-app-store-in-china>
- A6：<https://developer.apple.com/documentation/workoutkit>

**Health Connect**
- H1：**未核实**：<https://www.androidcentral.com/wearables/garmin/heres-everything-garmin-will-and-wont-share-with-google-health-connect>
- H2：**未核实**：<https://us.suunto.com/pages/suunto-app>、<https://customer.wellnesscoach.live/knowledge/syncing-coros-health-data-with-health-connect>
