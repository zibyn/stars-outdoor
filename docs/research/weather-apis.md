# 调研：国内外网格天气 API（沿途天气）

> 对应 issue #5。调研日期 2026-09-27。数据取自各服务商官方文档 / 价格页 / 条款，每条结论后附来源。标注「未核实」的为推断或未能直接访问一手来源的内容。

## 结论（TL;DR）

- **主选：和风天气（QWeather）逐小时预报（按经纬度，1 km，全球，最长 240 h）+ 和风预警 API。** 个人可注册，每月前 5 万次免费，超出 ¥0.0007/次，国内可达、覆盖海外，预警含中国及多个海外国家。
- **备选 / 补充：Open-Meteo。** 免费额度大（1 万次/天），支持一次请求多坐标，有 CAPE、闪电潜势、能见度等对流字段；但免费版仅限非商业（「无订阅、无广告」的 App 可算非商业，本项目无广告且不收费时大概率符合），商用订阅约 $29/月起。
- **日出日落不需要 API**：给定经纬度和日期可在本地用天文算法算出（例如 NOAA 太阳位置公式），离线可用。
- 不推荐：彩云（免费额度小，预警要企业级）、心知（经纬度网格只在最高档）、met.no（海外全球模型只有 9 km，雷暴概率仅限北欧）、OpenWeather（逐小时预报只有 48 h）、Tomorrow.io（免费 500 次/天）。

## 对比表

| 服务 | 大陆可达 | 空间分辨率 | 逐小时预报时长 | 雷暴/对流 | 预警 | 商用许可 | 免费额度 / 价格 | 个人注册 |
|---|---|---|---|---|---|---|---|---|
| 和风 QWeather | 是（国内公司） | 1 km，全球任意经纬度 [Q1] | 最长 240 h [Q1] | 天气现象代码（含雷阵雨） | 中国 + 多个海外国家/地区，官方预警 [Q3] | 付费按量即可商用 | 前 5 万次/月免费；5 万–100 万次 ¥0.0007/次；再往后 ¥0.0005/次 [Q2] | 可以（个人认证，无需营业执照）[Q4] |
| 彩云 Caiyun | 是 | 1 km，全球经纬度 [C1] | 免费/个人 2–5 天；专业以上 15 天 [C2] | skycon 含雷阵雨 [C1] | 仅中国、日本，**企业级**才开放 [C3] | 付费 | 免费 1000 次/天；个人档约 ¥1200/月（价格页与 PDF 价目表数字不一致）[C2] | 需个人/企业认证 |
| 心知 Seniverse | 是 | 经纬度/网格仅最高档「高精度」[S1] | 逐小时只在中高档 | — | 付费档含 | 开发者套餐 ¥99/年**禁止商用** [S1] | 免费档仅国内 370 城 [S1] | 可以 |
| Open-Meteo | 未实测（欧洲服务器，未见被封报道，未核实） | 1–25 km，按地区自动选最优模型（含 ECMWF 9 km、CMA GRAPES、ICON、GFS）[O1] | 最长 16 天 [O1] | CAPE、lightning potential、WMO 雷暴代码 [O1] | 无官方预警 | 数据 CC BY 4.0；**免费 API 仅非商业** [O2][O3] | 免费 1 万次/天、30 万次/月；Standard 100 万次/月约 $29、Professional 500 万次/月约 $99 [O2]（价格来自第三方转引，官方页需结账时才显示，未核实） | 免费版无需注册 |
| MET Norway (met.no) | 未实测 | 北欧 2.5 km；其余地区 ECMWF 约 9 km [M1] | 约 10 天 [M1] | `probability_of_thunder` 等仅北欧/北极区域 [M1] | 仅挪威（MetAlerts，未展开） | CC BY 4.0，可商用免费 [M2] | 免费；≤20 req/s，必须带可联系的 User-Agent、必须缓存，坐标 ≤4 位小数 [M2] | 无需注册 |
| OpenWeather | 未实测 | 未公开具体 km | One Call 3.0：48 h 逐小时 + 8 天逐日 [W1] | 天气代码含雷暴 | 各国政府预警 [W1] | 付费 | One Call：每天前 1000 次免费，超出按次计费（需绑卡）[W1][W2] | 可以 |
| Tomorrow.io | 未实测 | — | — | — | — | 免费版限个人 | 免费 500 次/天 [T1] | 可以 |

## 按需求逐项分析

### 1. 需求量估算

一条轨迹按「每 ~5 km 或每 1 小时预计到达点」取样，一日线约 10–30 个点。

- **和风**：每点一次请求 → 每次查询约 20 次调用。5 万次/月免费 ≈ 2500 次沿途天气查询/月；超出后每次查询约 ¥0.014。对个人项目成本可忽略。
- **Open-Meteo**：一次 HTTP 请求可带多个坐标（逗号分隔）[O1]，但计费按「>10 个变量或 >2 周按比例算多次调用」[O2]；多坐标是否按坐标数计次，官方未明确（未核实，保守按每坐标 1 次估算）。
- 建议后端做代理 + 按「网格点（经纬度取 2 位小数，约 1 km）× 预报发布时次」缓存，多个用户同一片区可复用；这也满足 met.no 的缓存要求。

### 2. 各规则提醒所需字段

| 提醒 | 和风 | Open-Meteo |
|---|---|---|
| 雷暴 | 天气现象代码（雷阵雨等）+ 预警 API | weather_code 95–99、CAPE、lightning potential |
| 强降水 | 逐小时降水量、降水概率 [Q1] | precipitation、precipitation_probability |
| 大风 | 风速、阵风 [Q1] | wind_speed、wind_gusts |
| 低温 | 温度、体感温度 [Q1] | temperature、apparent_temperature |
| 日落 | 本地计算（或和风天文 API [Q2]） | daily sunrise/sunset（或本地计算） |
| 能见度 | visibility [Q1] | visibility |

### 3. 许可要点

- **Open-Meteo 非商业定义**：「私人或非营利、**没有订阅和广告**的网站或 App」算非商业；带订阅的 App 算商用 [O3]。本项目无广告，若将来加订阅/付费则必须转商用订阅。免费版无可用性保证 [O2]。
- **met.no / Open-Meteo 数据**：CC BY 4.0，需要在 App 内署名 [M2][O2]。
- **和风**：2026 年起逐步停用公共域名 `api.qweather.com`，改为每个账号专属 API Host [Q4][Q5]；支持 JWT 与 API Key 认证 [Q5]。Key 不应放在 App 里，经后端代理调用。

### 4. 大陆可达性

国内三家（和风、彩云、心知）均为国内服务，可达性无疑问。海外服务（Open-Meteo、met.no、OpenWeather、Tomorrow.io）未在大陆网络实测，且本身不在一手文档里说明；若走后端代理（后端部署在大陆可达的海外节点），客户端只访问自己的后端，可规避此问题。

## 建议

1. MVP 直接用 **和风逐小时预报（1 km/240 h）+ 预警 API**，一个供应商同时覆盖国内与海外，个人可注册，免费额度够起步。
2. 后端缓存层接口保持供应商无关，**Open-Meteo 作为备用源**（和风故障或需要 CAPE 等对流指标时）。
3. 日出日落本地计算，不消耗调用额度，离线也能提醒。

未做：各服务实际预报精度对比、大陆网络实测海外 API 延迟。需要时再补。

## 来源

- [Q1] 和风 Weather API 列表与逐小时预报：https://dev.qweather.com/en/docs/api/weather/ ，https://dev.qweather.com/en/docs/api/weather/weather-hourly-forecast/
- [Q2] 和风价格：https://dev.qweather.com/docs/finance/pricing/
- [Q3] 和风预警：https://dev.qweather.com/en/docs/api/warning/weather-alert/ ，支持地区：https://dev.qweather.com/en/docs/resource/warning-info/
- [Q4] 个人注册及公共域名停用（第三方教程转述，未在官方页找到原文，未核实）：https://mdnice.com/writing/d38a398a3eca4be4baf2dc4d4fa1c9ee ，https://www.cnblogs.com/iuniko/p/19254198
- [Q5] 和风开始使用（API Host、JWT/API Key）：https://dev.qweather.com/en/docs/start/
- [C1] 彩云 API 文档：https://docs.caiyunapp.com/weather-api/
- [C2] 彩云价格：https://www.caiyunapp.com/api/pricing.html ；PDF 价目表：https://caiyunapp.com/api/caiyun_api_service_price.pdf
- [C3] 彩云预警 API：https://docs.caiyunapp.com/weather-api/v3/meteorology/alert.html
- [S1] 心知价格：https://www.seniverse.com/pricing
- [O1] Open-Meteo 文档：https://open-meteo.com/en/docs
- [O2] Open-Meteo 价格与调用计数：https://open-meteo.com/en/pricing ；订阅价格转引：https://openmeteo.substack.com/p/api-subscriptions-for-commercial
- [O3] Open-Meteo 条款：https://open-meteo.com/en/terms
- [M1] met.no Locationforecast 文档与数据模型：https://api.met.no/weatherapi/locationforecast/2.0/documentation ，https://docs.api.met.no/doc/locationforecast/datamodel.html （经搜索摘要引用，页面未直接抓取）
- [M2] met.no 服务条款：https://api.met.no/doc/TermsOfService
- [W1] OpenWeather One Call 3.0：https://openweathermap.org/api/one-call-3
- [W2] OpenWeather 价格：https://openweathermap.org/price
- [T1] Tomorrow.io 免费限额：https://support.tomorrow.io/hc/en-us/articles/20273728362644-Free-API-Plan-Rate-Limits （经搜索摘要引用）
