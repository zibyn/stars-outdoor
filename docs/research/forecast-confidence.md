# 调研：预报可信度的计算方法

调研日期：2026-10-06。对应问题：#241（属于 #237）。要把 **天气** 每天的预报分成高 / 中 / 低三档 **预报可信度**（见 `CONTEXT.md`），不展示多个模型的曲线。现状见 `server/weather.go`：和风给逐小时数据和预警，Open-Meteo 补阵风和格点海拔，和风失败时由 Open-Meteo 兜底；每个格点每小时缓存一次。

**来源说明**
- 只用一手来源：meteoblue 官方文档和产品页、Open-Meteo 官方文档和源码、ECMWF Forecast User Guide。
- 二手来源标 **UNVERIFIED**，找不到的写 **未找到**。
- meteoblue 的文档站和价格页是前端渲染的，用 headless Chrome 渲染后读取正文。
- Open-Meteo 源码固定在提交 `39a0b8c`：<https://github.com/open-meteo/open-meteo/blob/39a0b8c44b2ec2c86b26e89acaa2a7bb9340776f/>，下文简写 `OM`。
- §4 的数字是 2026-10-06 对 Open-Meteo 免费接口的一次实测（黄山附近格点，海拔 1466 m），只用来看量级，不能当阈值依据。

## 1. 摘要

- **meteoblue 的 predictability 是现成的，但算法不公开。**
  - 它表示 meteoblue 多模型预报（mLM）里各模型输出的一致程度，按天给出百分比，另有一个简化的 `predictability_class` [MB1]。
  - 网站把它分成 5 档，每档 20% [MB2]。
  - 自己没法复现。只有选 meteoblue 做专业数据时才顺带拿到。
- **自己算，最好用集合预报，不要直接用几个确定性模型的差值。**
  - ECMWF：集合离散度（spread）越大，集合平均的预期误差越大。但这只是统计关系，单次预报可能碰巧好，也可能碰巧差 [EC1]。
  - 实测（§4）：8 个确定性模型在第 1 天的最高气温就差 2.8 °C，阵风标准差达 7.7 km/h，而且不随预报时效增大。这些差值主要来自各模型地形高度和分辨率不同造成的系统偏差，不是天气本身的不确定性。ECMWF 集合（51 个成员）在同一格点第 1 天的气温离散度只有 0.1 °C，第 10 天到 2.5 °C，随时效增长得很平滑。
- **三个变量分开算，取最差的一档。**
  - 气温和阵风：用成员间的标准差。
  - 降水：用「日降水 ≥ 1 mm 的成员比例」，不用标准差。ECMWF 建议降水、风这类偏态分布的变量看中位数，不看平均 [EC1]。
- **阈值没有现成的标准答案。**
  - meteoblue 的 5 档只给了百分比边界，没给怎么从离散度换算成百分比 [MB2]。
  - ECMWF 的做法是和该地过去 30 天的离散度比，得到「归一化标准差」[EC1]。
  - 下面草案（§5）先用绝对阈值，作为可调的初值。
- **成本**：用 Open-Meteo 的话，集合预报接口要 Professional 套餐（$99/月，每月 500 万次）[OM3][OM4]。按源码的计费公式，取 51 个成员的 3 个日变量算 15.3 次调用 [OM-src]。按几十个用户估算，每月在 37 万次左右，远低于额度（§6）。

## 2. meteoblue predictability

### 2.1 定义

- 文档原文：「Predictability indicates the level of agreement among the forecast model outputs for a specific location and time. When forecast results show a high level of agreement, predictability is correspondingly high. Predictability is expressed as a percentage and is also provided as a `predictability_class`, which offers a simplified representation of the percentage value.」[MB1]
  - 释义：predictability 表示某地某时各预报模型输出的一致程度，越一致越高；用百分比表示，另给一个简化的分档 `predictability_class`。
- 背景：meteoblue 用统计和机器学习方法把多个数值模式合成「learning multi-model forecast（mLM）」，「An outcome of this approach is the ability to estimate the expected accuracy of the forecast」（这种做法的一个产物，就是能估计预报的预期准确度）[MB1]。
- 另一份说明里写，它「considers uncertainties in pressure, precipitation, temperature, wind, as well as larger scale patterns and climate inconsistencies」（考虑气压、降水、气温、风的不确定性，以及更大尺度的形势和气候上的不一致），按 50×50 km 的区域计算 [MB2]。
- **具体算法：未找到。** 两份文档都没写用哪些模型、怎么把离散度换算成百分比。

### 2.2 分档

meteoblue 网站上的 5 档 [MB2]：

| 百分比 | 原文 | 释义 |
|---|---|---|
| 80–100% | Reliable forecast, very unlikely to change | 可靠，基本不会变 |
| 60–80% | Fairly reliable forecast, unlikely to change | 较可靠，不太会变 |
| 40–60% | Somewhat uncertain forecast, changes possible | 有些不确定，可能会变 |
| 20–40% | Fairly uncertain forecast, likely to change | 较不确定，很可能变 |
| 0–20% | Uncertain forecast, very likely to change | 不确定，极可能变 |

- `predictability_class` 的取值范围和对应关系：一手文档 **未找到**。openHAB 的 meteoblue 插件文档写的是 0–5，0 为 very low，5 为 very high（**UNVERIFIED**）。文档里的示例响应给的是 `predictability: 80`、`predictability_class: 2`，看起来是随手填的示例值，不能拿来推映射 [MB1]。
- 合成 3 档的建议：≥ 60% 为高，40–60% 为中，< 40% 为低（判断）。中档正好对应原文的「changes possible」。

### 2.3 怎么取得、多少钱

- 在 Packages API 的 **basic** 包里，`data_day` 下有 `predictability`（单位 percent）和 `predictability_class`。basic 包给 7 天的预报 [MB1]。
- 计费按 credits：`basic-day` 每次 4000 credits，`basic-1h` 8000。同一次请求同时要 `basic-1h` 和 `basic-day`，只按贵的那个收，即 8000 [MB1]。
- 价格 [MB3]：
  - 免费版：一年 1000 万 credits，约 1000–5000 次调用。产品页的对比表在 Commercial use 一栏打了勾，但同一页的 FAQ 又说是给「non-commercial use and development projects」（非商用和开发项目）用的，前后矛盾，以合同为准。
  - Prepaid 版按量付费，单价页面上 **未找到**；Enterprise 版「From €100 / month」起。
- 结论：如果 #238 选 meteoblue 做专业数据，每次请求 basic 包就顺带拿到 predictability，不另外花钱。为了这一个字段单独接 meteoblue 不划算（判断）。

## 3. 用集合预报或多个模型自己算

### 3.1 依据（ECMWF）

- 「On average, larger spread implies larger expected error of the ensemble mean」（平均而言，离散度越大，集合平均的预期误差越大）[EC1]。
- 但这只是统计上的关系：「any individual ensemble mean forecast may by chance be good or bad」（单次的集合平均预报可能碰巧好，也可能碰巧差）[EC1]。所以档位要说成「靠不靠得住」，不要说成「准不准」，和 `CONTEXT.md` 现在的措辞一致。
- 离散度是对集合平均说的，不适用于中位数或控制预报 [EC1]。
- 「The ensemble median is more suited to parameters like wind speeds and precipitation because these usually have skewed distributions」（风速、降水通常是偏态分布，更适合看集合中位数）[EC1]。这是降水改用概率、不用标准差的依据之一。
- ECMWF 用「Normalised Standard Deviation」标出离散度异常大或异常小的区域，即和该地过去 30 天的离散度相比 [EC1]。这是阈值的另一种做法：不用固定数值，而是看「比这里平常更乱还是更稳」。

### 3.2 Open-Meteo 能给什么

**Ensemble API**（`/v1/ensemble`，主机 `ensemble-api.open-meteo.com`）[OM1]
- 返回每个成员的值，变量名形如 `temperature_2m_member01`。已实测，`daily=temperature_2m_max` 也会按成员返回。
- 跟本题有关的模型：

| 模型 | 成员数 | 分辨率 | 时效 | 更新 |
|---|---|---|---|---|
| ECMWF IFS 0.25° | 51 | 25 km | 15 天 | 每 6 小时 |
| ECMWF AIFS 0.25° | 51 | 25 km | 15 天 | 每 6 小时 |
| NOAA GFS（GEFS）0.25° | 31 | 25 km | 10 天 | 每 6 小时 |
| DWD ICON-EPS | 40 | 26 km | 7.5 天 | 每 12 小时 |
| Google WeatherNext 2 | 64 | 25 km | 15 天 | 每 12 小时 |

- 变量有气温、降水、阵风、云量、CAPE、0 °C 层高度等。

**Ensemble Mean API**（同一主机，模型名带 `_ensemble_mean`，如 `ecmwf_ifs025_ensemble_mean`）[OM2]
- 直接给集合平均和「spread (standard deviation)」（离散度，即标准差），变量名如 `temperature_2m_spread`。
- 已实测：逐小时的 spread 能取到；日变量没有 spread（`temperature_2m_max_spread` 报错）。所以用它只能拿逐小时的离散度近似日值，降水也算不出「≥ 1 mm 的成员比例」。

**多个确定性模型**（`/v1/forecast?models=a,b,c`）[OM5]
- 已实测：返回的变量名后面带模型名，如 `temperature_2m_ecmwf_ifs025`。
- 可选模型有 ECMWF IFS、GFS、ICON、CMA GRAPES、JMA、GEM、Météo-France、UKMO、KMA 等。
- 缺点（实测，§4）：
  - 只有几个成员。
  - 各模型时效不同，第 7 天后只剩一半。
  - 地形高度和分辨率不同造成的系统偏差混在离散度里。

### 3.3 三个变量怎么算

| 变量 | 算法 | 理由 |
|---|---|---|
| 气温 | 当天最高气温在各成员间的标准差 σ_T | 近似正态分布，ECMWF 默认就是看平均和标准差 [EC1] |
| 降水 | 当天降水量 ≥ 1 mm 的成员比例 p | 偏态分布，标准差会被少数大雨成员拉大 [EC1]。用户关心的是「下不下」，p 接近 0 或 1 说明成员意见一致 |
| 风 | 当天最大阵风在各成员间的标准差 σ_G | 和现在天气页展示的阵风对应（`server/weather.go` 取的是 `wind_gusts_10m`） |

- 「≥ 1 mm 算有雨」这个线是判断，不是查来的。

## 4. 实测：两种做法的离散度量级

格点：30.25°N, 118.25°E，Open-Meteo 格点海拔 1466 m。2026-10-06 取数，时区为 Asia/Shanghai。T 是日最高气温，单位 °C；G 是日最大阵风，单位 km/h；P≥1 是日降水 ≥ 1 mm 的成员比例。

| 日期 | ECMWF 集合 σ_T | P≥1 | σ_G | 确定性模型数 | 多模型 σ_T | 极差 T | 多模型 σ_G |
|---|---|---|---|---|---|---|---|
| 10-06 | 0.1 | 0% | 0.9 | 8 | 0.9 | 2.8 | 7.7 |
| 10-08 | 0.3 | 0% | 3.1 | 8 | 1.3 | 4.2 | 7.6 |
| 10-10 | 0.5 | 0% | 3.3 | 7 | 1.5 | 4.9 | 9.3 |
| 10-12 | 1.1 | 0% | 2.5 | 6 | 1.6 | 5.0 | 7.4 |
| 10-14 | 2.1 | 22% | 6.9 | 4 | 2.6 | 6.8 | 4.4 |
| 10-15 | 2.5 | 8% | 4.1 | 4 | 2.8 | 7.5 | 9.3 |

- 集合的离散度从第 1 天开始随时效增长，符合预期。
- 多模型的阵风离散度第 1 天就到 7.7 km/h，并且不随时效增长，说明主要是系统偏差。
- 多模型第 1 天的气温极差 2.8 °C，如果直接拿去套阈值，第 1 天也会被判成「中」。
- 只取了一个点、一天，只能说明量级。

## 5. 规则草案（可直接实现）

**数据**：Open-Meteo Ensemble API，`models=ecmwf_ifs025`，`daily=temperature_2m_max,precipitation_sum,wind_gusts_10m_max`，`forecast_days=7`，`wind_speed_unit=ms`，`timezone` 取格点所在时区。

**每天、每个格点算 3 个量**（只统计非空的成员）：

- `σT`：51 个成员当天最高气温的标准差，单位 °C。
- `p`：当天降水量 ≥ 1 mm 的成员比例，取值 0–1。
- `σG`：51 个成员当天最大阵风的标准差，单位 m/s。

**分档**（初值，都应做成服务端配置，上线后按实际调）：

| 变量 | 高 | 中 | 低 |
|---|---|---|---|
| 气温 σT | ≤ 1.5 °C | 1.5–3 °C | > 3 °C |
| 降水 p | ≤ 0.2 或 ≥ 0.8 | 0.2–0.35 或 0.65–0.8 | 0.35–0.65 |
| 阵风 σG | ≤ 2 m/s | 2–4 m/s | > 4 m/s |

**当天档位** = 三个变量里最差的一档。

**缺数据**：当天有效成员少于 26 个（一半）或请求失败时，不给档位，而不是猜一个。

- 阈值的依据：实测集合 σT 在第 1–5 天为 0.1–0.8 °C，第 9–10 天为 2–2.5 °C，第一版阈值让前几天大多落在「高」、第 9–10 天落到「中」左右。这只是判断，没有官方数值可依；文档里也 **未找到** 任何服务商公开的离散度阈值。
- 以后可以改成相对阈值：Ensemble Mean API 保存了 2026 年 3 月以来的历史平均和离散度 [OM2]，可以照 ECMWF 的「归一化标准差」[EC1]，把当天的离散度和该格点过去 30 天同一时效的离散度相比。等积累到真实的用户反馈再做。
- 雷暴、云海不进这条规则：meteoblue 也说雷暴概率因为定义不精确，算不出可信度 [MB2]。

**备选做法**
- 便宜的做法：用 Ensemble Mean API 的逐小时 spread，取白天（08–18 时）`temperature_2m_spread` 和 `wind_gusts_10m_spread` 的最大值，代替 σT 和 σG。降水改用 `precipitation_spread` 的白天总和，和 `precipitation` 的白天总和相比。
  - 好处：成本约为成员法的 1/15。
  - 缺点：降水概率要近似，和日最高气温也不完全是一回事。
- 选了 meteoblue：直接用 `basic-day` 的 `predictability`，按 §2.2 合成 3 档，不用自己算。

## 6. 数据源与成本

**Open-Meteo 的计费公式**（源码）[OM-src]

- 一次调用算 `max(1, 变量数 × 成员数 / 10 × max(1, 天数 / 14))` 次（`OM/Sources/App/Helper/Writer/ForecastApiResult.swift` 第 247–258 行）。
- 「变量数 × 成员数」的出处：`OM/Sources/App/Controllers/ForecastapiController.swift` 第 309 行，`nVariables = (nParamsHourly + … + nParamsDaily) * domains.reduce(0, { $0 + $1.countEnsembleMember })`。
- `_ensemble_mean` 模型的成员数按 1 算：同文件第 2853–2900 行的 `countEnsembleMember` 里没有列出 mean 模型，落到 `default: return 1`。

| 做法 | 每格点每次 | 说明 |
|---|---|---|
| 成员法：ECMWF IFS，3 个日变量，7 天 | 3 × 51 / 10 = **15.3 次** | 本草案 |
| Ensemble Mean：6 个逐小时变量（3 个值 + 3 个 spread） | **1 次** | 备选做法 |
| 确定性多模型：8 个模型 × 3 个变量 | 2.4 次 | 不推荐，见 §4 |

**套餐**
- Open-Meteo 免费版不能商用，每天 1 万次 [OM3]。
- 集合预报、历史数据要 **Professional** 套餐 [OM3]。官网写 Standard 每月 100 万次、Professional 500 万次，价格在页面上由前端渲染，没能读到 [OM3]。官方博客（2023-06-12）写的是 Standard $29/月、Professional $99/月，集合预报只在 Professional 里 [OM4]。现价以官网为准。
- 是否算商用（App 免费、开发者出钱），交给 #239 一起看。

**用量估算**（判断）
- ECMWF 集合每 6 小时更新一次 [OM1]，可信度的缓存可以放宽到 6 小时，不跟着现在的逐小时缓存走。
- 假设每天有 200 个不同格点，每个取 4 次：200 × 4 × 15.3 ≈ 1.2 万次/天，约 37 万次/月，不到 Professional 额度的 8%。
- 改用 Ensemble Mean：约 800 次/天。
- 现在「每台设备每天 3000 个格点」的配额如果被用满，成员法会很贵：3000 × 4 × 15.3 ≈ 18 万次/天。可信度应该只给天气页上用户正在看的地点算，不跟着沿途格点算（待 #244 定）。

## 7. 留给后续决定的

- `CONTEXT.md` 说可信度「由多个模型之间的一致程度决定」。如果按本草案用 ECMWF 一个模型的集合，措辞要改成「由集合预报各成员之间的一致程度决定」。也可以同时取 ECMWF 和 GEFS 两个集合（共 82 个成员），保留「多个模型」的说法，成本翻倍左右。这一点留给 grilling 定。
- 分档阈值是初值，要有调节的地方（服务端配置），并在上线后对照实际天气看「高」档的日子是不是真的少变。

## 来源

- [MB1] meteoblue Packages API — Forecast Data（basic 包、Predictability 一节、credits 表），读取于 2026-10-06：<https://docs.meteoblue.com/en/weather-apis/packages-api/forecast-data>
- [MB2] meteoblue — Predictability（weather variables 说明）：<https://content.meteoblue.com/en/research-education/specifications/weather-variables/predictability/>
- [MB3] meteoblue — Free Weather API 产品页（套餐对比表、FAQ）：<https://business.meteoblue.com/products/weather-apis/free-weather-api>
- [EC1] ECMWF Forecast User Guide — Section 8.1.2 ENS Mean and Spread：<https://confluence.ecmwf.int/display/FUG/Section+8.1.2+ENS+Mean+and+Spread>
- [OM1] Open-Meteo — Ensemble API：<https://open-meteo.com/en/docs/ensemble-api>
- [OM2] Open-Meteo — Ensemble Mean API：<https://open-meteo.com/en/docs/ensemble-mean-api>
- [OM3] Open-Meteo — Pricing：<https://open-meteo.com/en/pricing>
- [OM4] Open-Meteo 博客 — API Subscriptions for Commercial Use（2023-06-12）：<https://openmeteo.substack.com/p/api-subscriptions-for-commercial>
- [OM5] Open-Meteo — Weather Forecast API（`models` 参数）：<https://open-meteo.com/en/docs>
- [OM-src] Open-Meteo 源码 `39a0b8c`：`Sources/App/Helper/Writer/ForecastApiResult.swift`、`Sources/App/Controllers/ForecastapiController.swift`
- openHAB meteoblue 插件文档（**UNVERIFIED**，`predictability_class` 0–5）：<https://www.openhab.org/addons/bindings/meteoblue>
