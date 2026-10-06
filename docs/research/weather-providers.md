# 调研：天气服务商横评（专业户外字段、精度、价格、条款）

调研日期：2026-10-06。对应 #238（地图 #237）。现状见 `server/weather.go`、ADR 0009 / 0010 / 0011、`docs/spec/mvp.md` §2.9。

**来源说明**
- 只用一手来源：官方 API 文档、官方 OpenAPI 文件、官方价格页、官方条款。引用编号见文末。
- 拿不到正文的（登录墙、403、JS 渲染）写 **未找到**；只在搜索摘要或旧公告里出现、没能在现行页面核对的数字标 **UNVERIFIED**。
- meteoblue 的字段和每个包的 credits 来自它的官方 OpenAPI 文件 `https://my.meteoblue.com/packages/openapi.json`（文档站 redoc 加载的就是这份）[MB1]。

## 1. 摘要

- **字段最全的是 meteoblue**：`asl` 按实际海拔修正、0°C 层高度、雷暴概率、CAPE、对流云底 / 云顶（以气压给出）、低中高云、按气压层的温度 / 风剖面、`predictability` 可信度（0–100 % 加 5 档），体感温度明确综合风寒、湿度、辐射 [MB1][MB2]。缺点是贵、条款严（商用存储需书面同意）[MB4]。
- **Open-Meteo 覆盖了大部分字段，而且便宜**：90 m DEM 统计降尺度（可传 `elevation`）、低中高云、0°C 层高度、CAPE / 抬升指数 / CIN、19 个气压层的温度 / 风 / 位势高度、体感温度（风寒、湿度、辐射）[OM1]。**没有**云底 / 云顶高度、雷暴概率、现成的可信度指标；可信度要靠 Ensemble API 或多模型自己算 [OM1][OM3]。数据 CC BY 4.0，可缓存。
- **和风没有任何“专业”字段**：新版 `/weather/v1/hourly` 是 1 km、最长 240 h，带阵风、总云量、露点、体感，但没有分层云、0°C 层、CAPE、剖面，也没有按海拔修正的参数 [QW1][QW2]。继续用它做国内预警和基础预报合适。
- **Windy Point Forecast 在中国只能用 GFS**（ECMWF 因许可不提供），而且条款**禁止存储数据** [WD1][WD2]，和我们按格点缓存的做法直接冲突。不推荐。
- **Tomorrow.io**：有云底、云顶、雷暴概率，但没有分层云、CAPE、剖面；免费版每小时 25 次、只给 5 天，正式版要找销售 [TM1][TM2][TM3]。**彩云**：字段和和风同级，9–13 km，价格要登录才能看 [CY1][CY2]。
- **中国大陆山区精度：没有任何一家公开中国或山区的验证数据。** meteoblue 每月按大洲（含亚洲）发准确度报告，不分国家、不分山地 [MB6]；Open-Meteo、和风、彩云、Windy、Tomorrow.io 都没找到公开验证。
- **钱**：按几十个用户估算，和风基本免费到每月百元以内；Open-Meteo 在免费额度内（前提是算非商用，见 §4），商用 Standard 约 $29/月（UNVERIFIED，2023 年公告价）；meteoblue 只够用最低档 €2,400/年，而且只够“低用量 + 三个包”。
- **初步建议**（供 #237 的 ADR 讨论，不是结论）：和风保留做基础预报和预警；专业字段先接 **Open-Meteo**（商用版），可信度用 Ensemble API 或多模型离散度自己算档位；云底 / 雷暴概率若是硬需求，再评估 meteoblue（先谈试用、问清缓存许可）。

## 2. 字段对照

✅ 有；⚠️ 有但有限制；❌ 没有；? 未找到。

| 字段 | meteoblue | Open-Meteo（Forecast） | Open-Meteo Ensemble | 和风（v1） | Windy Point Forecast | Tomorrow.io | 彩云 v2.6 |
|---|---|---|---|---|---|---|---|
| 按实际海拔降尺度 | ✅ `asl`，默认 80 m DEM；中欧以外是“较简单的海拔修正” [MB1] | ✅ `elevation`，默认 90 m DEM 统计降尺度，`nan` 关闭 [OM1] | ? | ❌ 文档没提 [QW1] | ❌ | ? | ❌ |
| 垂直剖面（气压层温度、风） | ✅ profiletemp / profilewind 包，1000–150 hPa 共 21 层 [MB1] | ✅ 1000–30 hPa 共 19 层的温度、湿度、云量、风、位势高度；ECMWF 9 km 没有气压层，0.25° 才有 [OM1][OM2] | ? | ❌ | ✅ surface + 1000–150 hPa 共 13 层 [WD1] | ❌ | ❌ |
| 0°C 层高度 | ✅ `freezinglevelheight`（air 包，海拔高度）[MB1] | ✅ `freezing_level_height` [OM1] | ✅ [OM3] | ❌ | ⚠️ 没有直接字段，可用各层 `temp` + `gh` 自算 [WD1] | ❌ | ❌ |
| 分层云量（低 / 中 / 高） | ✅ clouds 包；低云 0–4 km、中云 4–8 km、高云 8–15 km [MB1][MB2] | ✅ 低云 ≤3 km、中云 3–8 km、高云 ≥8 km [OM1] | ⚠️ 只有总云量 [OM3] | ❌ 只有总云量 [QW1] | ✅ lclouds / mclouds / hclouds [WD1] | ❌ 只有总云量 [TM1] | ❌ 只有总云量 [CY1] |
| 云底 / 云顶高度 | ⚠️ 只有**对流云**的云底、云顶，单位是气压（hPa）[MB1] | ❌ | ❌ | ❌ | ⚠️ 只有云底 `cbase` [WD1] | ✅ `cloudBase` / `cloudCeiling`（离地）[TM1] | ❌ |
| CAPE / 雷暴概率 | ✅ CAPE、抬升指数、K 指数、CIN、**雷暴概率** [MB1] | ⚠️ CAPE、抬升指数、CIN；无雷暴概率（15 分钟数据有闪电潜势 LPI，仅部分模型）[OM1] | ⚠️ CAPE [OM3] | ❌ 只有雷电预警 [QW1] | ⚠️ CAPE [WD1] | ⚠️ 雷暴概率；无 CAPE [TM1] | ❌ |
| 体感温度定义 | 风寒 + 湿度 + 辐射 + 低风速加热 [MB1][MB2] | 风寒 + 相对湿度 + 太阳辐射 [OM1] | 同左 [OM3] | 只写“体感温度”，没给定义 [QW1] | 没有该字段 | 气温 + 湿度 + 风速（不含辐射）[TM1] | 只写“体感温度” [CY1] |
| 多模型 / 可信度 | ✅ `predictability` 0–100 % + `predictability_class` 5 档，按多模型一致程度算；另有 multimodel、trend（集合）包 [MB1][MB2] | ⚠️ 没有现成指标；可在 `models=` 里同时请求多个模型自己算离散度（按模型数计次）[OM1] | ✅ 各成员原始值，自己算离散度 / 概率 [OM3] | ❌ | ❌ | ❌ | ❌ |
| 逐小时到第 7 天 | ✅ basic-1h 是 7 天逐小时 [MB1] | ✅ 默认 7 天、最长 16 天；但 ECMWF 原生 90 h 后是 3 h、144 h 后是 6 h，逐小时是插值 [OM1][OM2] | ✅ | ✅ 最长 240 h [QW1] | ? 文档没写步长和时长 | ⚠️ 免费 5 天，企业 14 天 [TM2] | ⚠️ 最长 360 h，实际上限看套餐 [CY1] |

补充：
- meteoblue 的雷暴概率定义是“一定区域、一定时段内雷暴形成的机会”，0–100 % [MB1]。
- meteoblue 的 clouds 包还有雾概率、能见度、日照时长 [MB1]；Open-Meteo 有能见度、边界层高度、降雪高度 [OM1]。
- 和风 v1 逐小时自带阵风 `windGust` [QW1]，现在从 Open-Meteo 取阵风的那一步可以考虑去掉（`server/weather.go` 的注释写的是 v7 的 168h 接口没有阵风）。

## 3. 中国大陆山区：模型、分辨率、公开验证

| 服务商 | 在中国用的模型 / 分辨率 | 公开验证 |
|---|---|---|
| meteoblue | mLM 多模型（统计 + 机器学习融合几十个模型，加站点、雷达、卫星订正）；自家 NEMSGLOBAL 30 km，另有 ICON 13 km、GFS 25 km 等全球模型；高分辨率区域模型（最细 700 m）只在阿尔卑斯和中欧，**中欧以外只做较简单的海拔修正** [MB1][MB3] | 每月准确度报告（气温、露点、风速的 MAE / RMSE），按大洲分，含亚洲；全球气温 MAE 第 1 天 1.04 °C、第 6 天 1.65 °C；亚洲气温比最好的单一全球模型 MAE 低约 32–33 %。**不分中国、不分山地** [MB6] |
| Open-Meteo | 默认 Best match 按地点自动选分辨率最高的模型 [OM1]；覆盖中国的有 ECMWF IFS HRES 9 km、ECMWF IFS 0.25°、GFS、ICON Global、CMA GRAPES Global 15 km（3 小时步长、10 天；文档写着 CMA 开放数据服务“严重过载、几乎无法可靠下载”）[OM1][OM2][OM4] | 未找到 |
| 和风 | 自称 1 km 分辨率、全球任意地点；模型来源未公开 [QW1] | 未找到 |
| 彩云 | 大部分数据 9–13 km；前 2 小时降水可细到 1 km [CY1] | 未找到 |
| Windy Point Forecast | 中国只能用 GFS（arome、iconEu、nam 都是区域模型，不覆盖中国；ECMWF 因许可不提供）[WD2] | 未找到 |
| Tomorrow.io | 自研模型，官网只有“hyperlocal”之类说法 [TM2] | 未找到 |

结论：所有家在中国山区都是 9–30 km 的全球模型加海拔修正，**能比的只有“有没有按实际海拔修正”和“修正有多细”**。meteoblue 和 Open-Meteo 都有，和风、彩云没说。真要看山区精度，只能自己拿 CMA 站点数据（如有）做小规模对比，这超出本次调研。

## 4. 价格与月费估算

### 4.1 我们的用量怎么算

- 服务端按 0.01° 格点（约 1 km）缓存，**每个格点每个钟点最多拉一次**（整点清空缓存），沿途天气最多 8 个点，每台设备每天上限 3000 个格点（`server/weather.go`）。
- 现在每次拉一个格点：和风 2 次（`/v7/weather/168h` + `/weatheralert/v1/current`）+ Open-Meteo 1 次。和风失败时只有 Open-Meteo。
- 下面用“每月格点拉取次数 F”做估算（缓存未命中才算）。几十个用户（按 50 人）给三档：
  - **低 F = 1 万**：大多数人每天看几次自己位置的天气，周末少数人看沿途。
  - **中 F = 3 万**：再加上每周几次全天记录（记录时每 2 小时重问 10–30 个点，§2.9）。
  - **高 F = 10 万**：约每天 3300 次，作上限参考。

### 4.2 计费规则（一手）

| 服务商 | 免费额度 | 付费 | 计次规则 |
|---|---|---|---|
| 和风 | 天气预报、预警等“基础”类每月前 5 万次免费 [QW3] | 5 万–100 万次 ¥0.0007 / 次，再往上递减；按月结算 [QW3] | 每个请求 1 次 |
| Open-Meteo | 免费版仅限**非商用**：600 次/分、5000 次/时、1 万次/天、30 万次/月 [OM5][OM6] | Standard 100 万次/月、Professional 500 万次/月、Enterprise 5000 万次+/月 [OM5]；现行价格页不显示价格，2023 年官方公告是 Standard $29/月、Professional $99/月（**UNVERIFIED** 是否仍是现价）[OM7]。**Ensemble、历史数据要 Professional 起** [OM5] | 超过 10 个变量或超过 2 周按比例多计：15 个变量 2 周算 1.5 次 [OM5]；多模型、多地点也加权 [OM3] |
| meteoblue | Free Weather API：1 年 1000 万 credits，部分包且多数只给 3 h 分辨率（basic 有 1 h），对比表标“可商用” [MB5] | Premium Weather API 起价 **€2,400/年**，对应每月约 4 万次 ≈ 3.2 亿 credits；更高档（15 万次、50 万次）价格不公开；另需 Bronze 支持 [MB4]。Prepaid 按量、Enterprise 从 €100/月起 [MB5] | 按包扣 credits：basic-1h、clouds-1h、air-1h 各 8000；trend-1h 16000；profiletemp-1h、profilewind-1h 各 16000；multimodel-1h 16000；同一包多种分辨率只扣最贵一档 [MB1] |
| Windy Point Forecast | Testing 版每天 500 次，**数据被随机打乱**，只许开发 [WD2] | Professional €990/年，每天 1 万次 [WD2] | 每次请求 |
| Tomorrow.io | 免费版每天 500 次、每小时 25 次、每秒 3 次 [TM3] | 企业版找销售 [TM2] | 每次请求 |
| 彩云 | 未找到（价格在登录后的管理平台）[CY2] | 按量 / 包月 / 企业，价格未公开 [CY2] | 未找到 |

### 4.3 月费估算

| 方案（每次格点拉取要的内容） | 低 F=1 万 | 中 F=3 万 | 高 F=10 万 |
|---|---|---|---|
| 和风（现在的 2 次/格点） | 2 万次，免费 | 6 万次 → ¥7 | 20 万次 → ¥105 |
| Open-Meteo 现在的用法（6 个变量，1 次/格点） | 1 万次 | 3 万次 | 10 万次 |
| Open-Meteo 加专业字段（约 25 个变量含几层气压层，7 天 ≈ 2.5 次/格点） | 2.5 万次 | 7.5 万次 | 25 万次 |
| ↳ 费用 | 都在免费 30 万次/月内（若算非商用）；商用 Standard 100 万次够用，约 $29/月（UNVERIFIED） | 同左 | 同左 |
| Open-Meteo Ensemble（可信度） | 需 Professional，约 $99/月（UNVERIFIED）；每次按模型、变量加权，示例请求即 4.0 次 [OM3] | 同左 | 同左 |
| meteoblue basic + clouds + air（2.4 万 credits/格点） | 2.4 亿 credits/月，在 €2,400/年（约 €200/月）档内 | 7.2 亿/月，超出最低档，要 15 万次档（价格不公开） | 24 亿/月，要 50 万次档（价格不公开） |
| meteoblue 再加温度 + 风剖面（5.6 万 credits/格点） | 5.6 亿/月，已超最低档 | — | — |
| Windy Professional | €990/年（约 €83/月），每天 1 万次够用 | 同左 | 同左 |

要点：
- **和风和 Open-Meteo 在几十个用户时几乎不花钱**；真正的成本选择是 Open-Meteo 要不要买商用版、要不要买 Professional（Ensemble）。
- **meteoblue 是唯一会让月费上到几百欧的选项**。若要用，就得只在用户打开“专业”内容时才拉，或者把 meteoblue 的格点和缓存时长放粗——但这又碰到它的存储条款（§5）。

## 5. 条款

| 服务商 | 能否在 App 内展示 | 能否缓存 / 存储 | 署名 | 商用 |
|---|---|---|---|---|
| 和风 | 能 | 开发者许可协议正文没能读到，**未找到** | 必须显示“和风天气”并链接 qweather.com，推荐“天气服务由和风天气驱动”；预警和空气质量要完整显示 `refer.sources` [QW4] | 按量付费即可商用 [QW3] |
| Open-Meteo | 能 | 条款没提缓存；数据是 CC BY 4.0，允许使用和再分发，缓存不成问题 [OM5][OM6] | 必须署名并注明修改（CC BY 4.0）[OM5] | 免费版只许非商用：“集成进商业产品”算商用，“没有订阅和广告的私人 / 非营利网站”算非商用 [OM6]。我们免费、无广告，但算不算“商业产品”是灰色地带——**现在的 Open-Meteo 调用已经在这个问题里了** |
| meteoblue | 商业网站引用 meteoblue 服务需事先许可（9.03）；数据旁要有明显的 meteoblue 来源（9.05）[MB4b] | **非商用以外，未经同意不得存储、复制**（10.03）[MB4b]；地图产品默认不许缓存 [MB4] | “Data provided by www.meteoblue.com” 一类署名 [MB4b] | 付费 API 可商用；Free API 对比表标可商用 [MB5]，但另一份法律页把网站上的数据定为 CC BY-NC-ND [MB7]，两者矛盾，要问清 |
| Windy | 能，但 Logo 必须原样、可点击；移动 App 要在“关于”里写数据来源 [WD1b] | **禁止存储、提取、修改、分发数据**，禁止建派生数据库 [WD1b] | Logo + “Contains data from the Windy database” [WD1b] | 不能只放在 App 的付费部分 [WD1b] |
| Tomorrow.io | 未找到（支持中心 403） | 未找到 | 未找到 | 未找到 |
| 彩云 | 未找到 | 未找到 | 未找到 | 未找到 |

## 6. 对 #237 的影响（待 grilling 决定）

- “云底、云顶高度”只有 Tomorrow.io（离地高度）和 meteoblue（仅对流云、以 hPa 给出）有。若改成用 Open-Meteo 的低云量 + 0°C 层 + 各层湿度来判断云海，需要在 CONTEXT.md 的 **云海** 里写清判断口径。
- “可信度档位”：meteoblue 现成；Open-Meteo 要么买 Professional 用 Ensemble，要么用 Forecast API 同时请求 3–4 个模型、按离散度分档（计次按模型数加倍，仍远低于额度）。
- 现有缓存是“每格点每钟点一次”；Open-Meteo 不需要改，meteoblue / Windy 要先解决条款。
- Open-Meteo 免费版的“非商用”判断现在就适用（`server/weather.go` 已在调用），建议在 ADR 里明确：要么按商用买 Standard，要么写下判断依据。

## 来源

- [MB1] meteoblue Forecast API OpenAPI（packages、`asl`、各包字段、credits 表、限速 500 次/分）：<https://my.meteoblue.com/packages/openapi.json>（文档入口 <https://my.meteoblue.com/packages/redoc>）
- [MB2] meteoblue 天气变量定义（体感温度、predictability、云层高度、CAPE）：<https://docs.meteoblue.com/en/meteo/variables/weather-variables>
- [MB3] meteoblue 数据源 / 模型：<https://docs.meteoblue.com/en/meteo/data-sources/datasets>
- [MB4] meteoblue 价格页：<https://business.meteoblue.com/pricing>
- [MB4b] meteoblue Terms & Conditions（最后修订 2018-05-24）：<https://business.meteoblue.com/terms-conditions>
- [MB5] meteoblue Free Weather API（含 Free / Prepaid / Enterprise 对比表）：<https://business.meteoblue.com/products/weather-apis/free-weather-api>；<https://docs.meteoblue.com/en/weather-apis/free-weather-api/overview>
- [MB6] meteoblue 月度准确度报告：<https://business.meteoblue.com/articles/forecast-transparency-meteoblue-monthly-accuracy-reports>
- [MB7] meteoblue 商用 / 非商用定义：<https://content.meteoblue.com/en/about-us/legal/commercial-non-commercial-use>
- [OM1] Open-Meteo Forecast API 文档：<https://open-meteo.com/en/docs>
- [OM2] Open-Meteo ECMWF API：<https://open-meteo.com/en/docs/ecmwf-api>
- [OM3] Open-Meteo Ensemble API：<https://open-meteo.com/en/docs/ensemble-api>
- [OM4] Open-Meteo CMA API：<https://open-meteo.com/en/docs/cma-api>
- [OM5] Open-Meteo 价格与 FAQ：<https://open-meteo.com/en/pricing>
- [OM6] Open-Meteo 条款：<https://open-meteo.com/en/terms>
- [OM7] Open-Meteo 官方博客，2023-06-12 商用订阅公告：<https://openmeteo.substack.com/p/api-subscriptions-for-commercial>
- [QW1] 和风逐小时预报（v1）：<https://dev.qweather.com/docs/api/weather/weather-hourly-forecast/>
- [QW2] 和风每日预报（v1）：<https://dev.qweather.com/docs/api/weather/weather-daily-forecast/>
- [QW3] 和风价格：<https://dev.qweather.com/docs/finance/pricing/>
- [QW4] 和风署名要求：<https://dev.qweather.com/docs/terms/attribution/>
- [WD1] Windy Point Forecast 文档：<https://api.windy.com/point-forecast/docs>
- [WD1b] Windy Map & Point Forecast 使用条款：<https://account.windy.com/agreements/windy-api-map-and-point-forecast-terms-of-use>
- [WD2] Windy Point Forecast 价格：<https://api.windy.com/point-forecast/pricing>
- [TM1] Tomorrow.io 数据层：<https://docs.tomorrow.io/reference/data-layers-core>
- [TM2] Tomorrow.io Weather API 页面（套餐）：<https://www.tomorrow.io/weather-api/>
- [TM3] Tomorrow.io 免费版限速：<https://support.tomorrow.io/hc/en-us/articles/20273728362644-Free-API-Plan-Rate-Limits>（正文 403，数字取自搜索摘要，**UNVERIFIED**）
- [CY1] 彩云 v2.6 逐小时：<https://docs.caiyunapp.com/weather-api/v2/v2.6/3-hourly.html>
- [CY2] 彩云计费：<https://docs.caiyunapp.com/weather-api/billing.html>
