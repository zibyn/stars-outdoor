# 调研：可再分发的开放轨迹与路网数据（周边路网）

> 对应 issue #6。调研日期 2026-09-27。结论取自许可证原文、OSMF 官方指南、各国官方数据门户，每条结论后附来源编号。标注「未核实」的为推断或未能直接读到一手来源的内容。本文不是法律意见。

## 结论（TL;DR）

- **周边路网的骨干只能是 OSM。** ODbL 允许复制、分发、改编和商用，条件是署名 OSM 贡献者、说明数据以 ODbL 提供，改编后的数据库按 ODbL 共享 [O1][O2]。中国大陆的 OSM 数据量足以打底：当前有 `highway=path|track|footway|bridleway` 路段约 110 万条，`route=hiking|foot` 关系 685 条（2026-09-27 Overpass 实测）[O9]。
- **用户公开轨迹（UGC）可以不受 ODbL 的 share-alike 约束，前提是它和 OSM 路径分开存放，互不引用，也不拿 OSM 几何去合并、纠偏或去重。** 这符合 OSMF 的 Collective Database 与 Horizontal Map Layers 两份指南 [O3][O4]。一旦把 UGC 吸附（snap）到 OSM 路径上，或拿它补全、修正 OSM 路径，就成了派生数据库，整体都要按 ODbL 共享。
- **OSM 公开 GPS traces 不能作为导入源**：它没有单独写明许可证，只能经编辑 API 拿到，而编辑 API 明确禁止只读用途和批量拉取 [O5][O6][O7]。全量的 planet.gpx 停在 2013 年 [O8]。
- **可以直接导入并再分发的官方开放数据**（全部要求署名）：香港渔护署郊野公园远足径（DATA.GOV.HK，可商用）[H1][H2]、台湾林业保育署自然步道轨迹 KML（政府资料开放授权条款第 1 版，兼容 CC BY 4.0）[T1][T2]、美国 USGS National Digital Trails 与 NPS 步道（公有领域）[U1][U2]、瑞士 swisstopo 徒步路网（OGD，可商用，注明来源即可）[S1][S2]。
- **不能直接导入、需要单独授权的**：法国 GR®（FFRandonnée 对线路主张著作权和商标，第三方复制须签许可合同）[F1]；Te Araroa（Trust 声明的「Creative Commons 4.0 New Zealand」并不存在，具体是哪种 CC 条款说不清，商用前需书面确认）[N1][N2]。
- **中国大陆没有找到可再分发的官方步道矢量数据。** 国家登山健身步道没有公开的 GPX 或开放数据门户（未找到，未核实是否存在内部数据）。所以大陆的周边路网 = OSM + 用户公开轨迹。
- **建议**：MVP 以 OSM（Geofabrik 分区 PBF，每日更新，自行抽取徒步路径）+ UGC 独立图层为主；海外经典线路先接 OSM `route=hiking` 关系（例如 GR、Te Araroa 在 OSM 里都有社区绘制的关系），官方数据按上面的白名单逐个导入。

## 对比表

| 来源 | 覆盖 | 许可 | 再分发 / 与 UGC 混合 | 获取方式 | 更新频率 |
|---|---|---|---|---|---|
| OSM 路径（`highway=path/track/footway`）+ `route=hiking` 关系 | 全球；中国大陆约 110 万条路段、685 条徒步关系 [O9] | ODbL 1.0 [O1] | 可以，需署名；改编后的数据库须按 ODbL 提供；UGC 分图层存放时不受传染 [O3][O4] | Geofabrik 分区 PBF（中国约 1.5 GB）[G1]；planet + 分钟级 diff [O6]；Overpass（公共实例约 1 万次/天、1 GB/天）[O10] | Geofabrik 每日；planet 分钟级 diff |
| Waymarked Trails | 同 OSM 徒步关系 | 数据为 OSM/ODbL；软件 GPL [W1][W2] | 同 OSM | 网站按线路导出 GPX；未见公开的第三方 API 使用政策（未核实）；后端开源，可自建 [W2] | 跟随 OSM（未核实具体延迟） |
| OSM 公开 GPS traces | 全球，稀疏 | 未单独写明，受 Contributor Terms 约束 [O5] | **不建议** | 只能走编辑 API，而该 API 禁止只读批量使用 [O6]；planet.gpx 停在 2013 [O8] | — |
| 香港渔护署郊野公园远足径 | 香港 | DATA.GOV.HK 使用条款：可商用、须注明来源、须承认政府知识产权 [H1] | 可以 | CSDI API [H2] | 有新条目时更新 [H2] |
| 台湾林业保育署自然步道轨迹 | 台湾约 160 条步道（未逐一核实） | 政府资料开放授权条款第 1 版，兼容 CC BY 4.0 [T1][T2] | 可以，须署名 | data.gov.tw KML 下载 [T2] | 标注「每 4 年」[T2] |
| USGS National Digital Trails / NPS 步道 | 美国 | 公有领域 [U1] | 可以 | The National Map 下载（Shapefile/GDB）、WFS 服务 [U1]；NPS IRMA / ArcGIS Open Data [U2] | 持续汇总（未核实具体周期） |
| swisstopo swissTLM3D Wanderwege | 瑞士、列支敦士登 | OGD：免费、可商用，唯一条件是注明来源 [S1][S2] | 可以 | opendata.swiss / STAC API / WMS·WMTS [S1] | 每年全量更新，年内零散修正 [S1] |
| 法国 FFRandonnée GR® | 法国 | 著作权 + 注册商标；GPX 下载仅限私人使用 [F1] | **不可以**，须签许可合同 | 向 FFRandonnée 申请 | — |
| Te Araroa Trust | 新西兰 | GPX 元数据写的是「Creative Commons 4.0 New Zealand」，这个条款不存在，含义不明 [N2] | 须先向 Trust 书面确认 | 官网 GPX/KMZ [N1] | 每年 2、5、8、11 月更新 [N1] |
| 新西兰 DOC 小屋/营地 | 新西兰 | CC BY 4.0 [N2] | 可以，须署名 | DOC 开放数据 | — |
| 中国大陆官方步道 | — | 未找到开放数据 | — | — | — |

## 分析

### 1. ODbL 对周边路网到底约束什么

- **署名**：App 地图界面和数据下载处都要显示「© OpenStreetMap contributors」，并说明数据以 ODbL 提供 [O1][O11]。
- **Share-alike 只针对数据库，不针对 App 本身**：公开使用改编后的 OSM 数据库，就必须以 ODbL 提供这份数据库 [O2]。渲染出来的地图、路线规划结果是 Produced Work，可以用别的条款发布，但按 ODbL §4.6，要能按请求提供背后的派生数据库，或者提供从 OSM 生成它的方法 [O2][O12]。对本项目的实际含义：离线包、周边路网的矢量数据只要是从 OSM 抽取或加工的，就按 ODbL 对外提供（最简单的做法是公开抽取脚本和标签过滤规则），不会增加额外负担。
- **UGC 怎么保持独立**（指南原文见 [O3][O4]）：
  - 公开轨迹作为单独的要素类型（「用户轨迹」）存放在单独的表或图层里。它不引用 OSM 的 way/relation id，入库时也不吸附到 OSM 几何上。
  - 在客户端一起渲染、一起参与「附近有什么路」的检索没有问题，这属于 horizontal layers。
  - 会触发 share-alike 的做法：拿 UGC 去修正 OSM 路径几何、给 OSM 路径补属性（例如用 UGC 统计出的难度或耗时去标注 OSM way，这一条是推断），或者把两者合并成同一个「路径」要素集。
- **UGC 本身的授权**：用户协议里要写明，用户把轨迹设为「公开」时，授予平台展示和再分发的许可。建议授予平台非独占许可，而不是给 UGC 套 ODbL，这样以后仍可选择把它贡献给 OSM。这一点属于产品和法务决策，不是许可证硬性要求。

### 2. 为什么不用 OSM GPS traces

- 可见性分为 identifiable / trackable（以及已弃用的 public / private）。无论选哪一种，点位都能通过 API 拿到 [O5]。但 OSM 没有为 traces 单独写许可证，Contributor Terms 里只笼统地称为「Contents」[O7]。
- 编辑 API 的使用政策写明：API「not for read-only purposes」，大量读取应改用 planet 或 extracts [O6]。traces 没有这样的替代出口，planet.gpx 最新一版是 2013 年 [O8]。
- 这些 traces 本身是原始 GPS 点，没有路名和线路结构，拿来当周边路网的价值很低，同时还有贡献者隐私问题。

### 3. 中国用户的覆盖

- **大陆**：OSM 数据量可用，但质量参差（未逐区核实）。热门山区路径大多来自社区绘制，冷门线路稀疏，所以用户公开轨迹是补齐大陆覆盖的主力，而且会越用越多。OSM wiki 提醒大陆未经授权的测绘活动违法，国内服务商的数据多为 GCJ-02 偏移坐标，不能混入 [O13]。按 #1 的决定，平台不在国内商店上架，也不做审图，但**导入任何国内来源的数据前都必须确认它是 WGS84**。
- **港台**：各有政府开放数据，可以直接导入（见上表），许可和署名都很清楚。
- **国家登山健身步道**：已建成 30 条左右 [C1]，但没有找到官方 GPX 或开放数据。第三方 App（例如两步路）的合集属于竞品内容，按 #1 不能抓取。

### 4. 海外经典线路

- 优先用 OSM `route=hiking` 关系。GR、Te Araroa、PCT、AT 等线路在 OSM 里一般都有社区绘制的关系（按线路逐条核对，未核实）。这样可以统一走 ODbL，不必逐家谈授权。
- 需要「官方线路」标记时，只从白名单里的开放许可源导入：USGS/NPS、swisstopo、DOC NZ、香港、台湾。**FFRandonnée GR 与 Te Araroa Trust 的官方 GPX 不导入**，除非拿到书面授权。

### 5. 获取与更新建议（低运维）

- 定期（例如每周）拉 Geofabrik 分区 PBF，用 osmium 按标签过滤出路径和徒步关系，再生成矢量瓦片或离线包。不直接调用公共 Overpass 或编辑 API 服务线上流量 [O6][O10]。
- 开放数据源的更新频率低（按年或按季），手动或季度脚本导入即可。每条要素上都要保存 `source` 和 `license` 字段，用于展示署名和下架。

## 来源

- [O1] OpenStreetMap Copyright and License — https://www.openstreetmap.org/copyright
- [O2] OSMF, Produced Work – Guideline — https://osmfoundation.org/wiki/Licence/Community_Guidelines/Produced_Work_-_Guideline
- [O3] OSMF, Collective Database Guideline（2016-06-17 董事会通过）— https://wiki.openstreetmap.org/wiki/Collective_Database_Guideline
- [O4] OSMF, Horizontal Map Layers – Guideline — https://osmfoundation.org/wiki/Licence/Community_Guidelines/Horizontal_Map_Layers_-_Guideline
- [O5] OSM Wiki, Visibility of GPS traces — https://wiki.openstreetmap.org/wiki/Visibility_of_GPS_traces
- [O6] OSMF, API Usage Policy — https://operations.osmfoundation.org/policies/api/
- [O7] OSMF, Contributor Terms — https://osmfoundation.org/wiki/Licence/Contributor_Terms
- [O8] OSM Wiki, Planet.gpx — https://wiki.openstreetmap.org/wiki/Planet.gpx
- [O9] Overpass API 实测（2026-09-27）：`area["ISO3166-1"="CN"][admin_level=2]` 内 `way[highway~"^(path|track|footway|bridleway)$"]` = 1,098,925；`rel[route~"^(hiking|foot)$"]` = 685
- [O10] Overpass API 使用约定 — https://dev.overpass-api.de/overpass-doc/en/preface/commons.html
- [O11] OSMF, Attribution Guidelines — https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- [O12] ODbL 1.0 原文 §4.6 — https://opendatacommons.org/licenses/odbl/1-0/
- [O13] OSM Wiki, China — https://wiki.openstreetmap.org/wiki/China
- [G1] Geofabrik, China extract — https://download.geofabrik.de/asia/china.html
- [W1] OSM Wiki, Waymarked Trails — https://wiki.openstreetmap.org/wiki/Waymarked_Trails
- [W2] waymarked-trails-site README（GPL；已拆分为 backend/api/website 仓库）— https://github.com/waymarkedtrails/waymarked-trails-site
- [H1] DATA.GOV.HK Terms and Conditions — https://data.gov.hk/en/terms-and-conditions
- [H2] DATA.GOV.HK, Hiking Trails in Country Parks — https://data.gov.hk/en-data/dataset/hk-afcd-afcdlist-hikingtrailscp
- [T1] 政府资料开放授权条款第 1 版 — https://data.gov.tw/license
- [T2] data.gov.tw 林业保育署所辖自然步道轨迹图（示例 #30695）— https://data.gov.tw/dataset/30695
- [U1] USGS, How to access or view the USGS Trails Dataset — https://www.usgs.gov/national-digital-trails/how-access-or-view-usgs-trails-dataset
- [U2] NPS National Trails Office GIS Data — https://www.nps.gov/orgs/1453/gis-data.htm
- [S1] opendata.swiss, swissTLM3D Wanderwege — https://opendata.swiss/en/dataset/swisstlm3d-wanderwege
- [S2] swisstopo, Free basic geodata (OGD) — https://www.swisstopo.admin.ch/en/free-geodata-ogd
- [F1] FFRandonnée, La propriété intellectuelle fédérale — https://www.ffrandonnee.fr/la-federation/qui-sommes-nous/la-propriete-intellectuelle-federale
- [N1] Te Araroa, Trail Maps — https://www.teararoa.org.nz/trail-maps/
- [N2] te-araroa-data DATA-LICENCE.md（引用 Trust GPX 元数据中的 license 字段；DOC 数据 CC BY 4.0）— https://github.com/eamon-b/te-araroa-data/blob/main/DATA-LICENCE.md
- [C1] 中国国家登山健身步道概况（二手，未找到官方数据门户）— http://www.china-npa.org/info/2895.jspx
