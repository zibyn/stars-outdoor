# 调研：地名/山峰搜索数据源（含离线）

> 对应 issue #18（依据 #16 原型的搜索入口）。调研日期：2026-09-27。仅采信官方文档、价格页、许可证原文与**本次实测**；二手来源单独标注。
> 前提（见 #1）：Android 优先（MapLibre Compose）、不上国内商店、无 ICP、个人业余、后端倾向香港 VPS；离线地图为按区域自托管的 Protomaps PMTiles，全链路 WGS-84。

## 结论（TL;DR）

1. **离线搜索：每个离线区域包附带一个 SQLite 地名索引**，由 Photon 官方 JSON 导出（OSM 数据，已带行政区层级与 importance）过滤生成。实测**中国全境户外相关子集 65.6 万条，SQLite 41.6 MB，zstd 压缩后 24 MB**；普通 `LIKE '%词%'` 全表扫描约 40–50 ms（桌面 CPU），MVP 不需要全文索引。
2. **在线搜索：自托管 Photon（Apache-2.0）于香港 VPS**，只导入需要的国家（`-country-codes`）和语言（`-languages zh,en`）。Photon 支持边输边搜、`osm_tag=natural:peak` 过滤、位置偏置、反查；中文山峰名实测可用。
3. **OSM 中文山峰覆盖足以起步**：Overpass 实测中国境内 `natural=peak` 共 59,567 个节点，其中有 `name` 的 34,694 个；Photon 中国导出中有名字的山峰 34,882 个，其中 14,513 个带海拔。短板是**景区/山体整体名称**（如"泰山"在 OSM 是主峰"玉皇顶"，"武功山"首条命中日本）——需要按 importance/距离排序和别名补丁。
4. **天地图地名搜索 V2.0 只做可选补充**（大陆景点/POI 更全，CGCS2000≈WGS-84，免费 Key），但配额未公开、**不可离线**、Key 须经后端代理。海外无意义。
5. **不用 Nominatim**：自建需 128 GB RAM / 1 TB NVMe（全球），公共实例**明令禁止自动补全**，App 流量总和上限 1 req/s。
6. **商业 Geocoding（MapTiler / Stadia）不需要**：免费档都**禁止商用**且大陆可达性未知；只作 Photon 挂掉时的备选。
7. **坐标输入纯本地解析**（十进制度、度分秒、`lat,lon` 顺序判定），不依赖任何服务。

## 逐项对比

| 方案 | 覆盖（中文山峰） | 许可 | 价格 | 离线 | 体积 / 资源 | 大陆可达 |
|---|---|---|---|---|---|---|
| **离线 SQLite 索引（由 Photon 导出生成）** | 同 OSM：中国有名山峰 ~3.5 万 | ODbL（OSM 衍生数据库，需署名 + 同样开放） | 0 | ✅ | 中国全境 41.6 MB（压缩 24 MB） | ✅ 本地 |
| **自托管 Photon** | 同 OSM，实测中文前缀匹配可用 | 代码 Apache-2.0；数据 ODbL | VPS 费用 | ❌（服务端） | 全球库 ~95 GB、建议 64 GB RAM；中国导出 513 MB（jsonl.zst） | 取决于 VPS（香港，需实测） |
| photon.komoot.io 公共实例 | 同上 | 同上 | 免费 | ❌ | — | 需实测 |
| 自托管 Nominatim | 同 OSM | GPL-2.0 / ODbL | VPS 费用高 | ❌ | 全球 ≥1 TB 盘、建议 128 GB RAM | 取决于 VPS |
| nominatim.openstreetmap.org | 同 OSM | ODbL | 免费 | ❌ | 全 App ≤1 req/s，**禁自动补全** | 需实测 |
| **天地图地名搜索 V2.0** | 大陆 POI/景点较全（未量化） | 天地图服务条款 | 免费 Key，配额未公开 | ❌ | — | ✅ 国内官方 |
| MapTiler Geocoding | 全球（OSM 等） | 商业 ToS | Free 1k 搜索会话/月（非商用）；Flex $30/月含 3k，超出 $2.5/千 | ❌ | — | 需实测 |
| Stadia Geocoding | 全球（Pelias/OSM 等） | 商业 ToS | Free 20 万 credit/月（非商用），v1 搜索 20 credit/次 ≈ 1 万次；Starter $20/月 | ❌（Free/Starter 结果只能临时存储） | — | 需实测 |
| GeoNames | 中国山体 3,921 条，其中带汉字名 2,590 | CC BY 4.0 | 免费 | ✅ | CN.zip 31 MB | ✅ 本地 |

## 详细说明

### 1. 离线索引（推荐 MVP 主方案）

**做法**：构建脚本读取 Photon 官方按国家导出的 JSON dump，过滤出户外用户关心的类别，写成每区域一个 `.sqlite`，与该区域的 `.pmtiles` 一起下载。

- 数据来源：GraphHopper 为 Photon 提供每周导出，按大洲/国家切分；中国 `photon-dump-china-1.0-latest.jsonl.zst` **513.1 MB**（2026-09-21），亚洲 2.8 GB，全球 11.2 GB（jsonl.zst）。[Photon 下载服务](https://download1.graphhopper.com/public/)、[China 目录](https://download1.graphhopper.com/public/asia/china/)
- dump 格式有公开规范（`name` 字典含 `name:zh`/`name:en` 等，另有 `importance`、`address` 层级、`centroid`）。[json-dump-format-0.1.0](https://github.com/komoot/photon/blob/master/docs/json-dump-format-0.1.0.md)。用它而不是直接跑 osmium 解析 PBF，省去自己拼"所属省/县"这一步。
- **本次实测**（2026-09-19 数据）：中国 dump 共 4,019,185 个地点。保留 `place=*`、`natural=peak/volcano/saddle/ridge/cliff/cave_entrance/glacier/spring/water/valley/mountain_range`、`tourism=attraction/viewpoint/camp_site/alpine_hut/wilderness_hut/picnic_site`、`waterway=waterfall`、`mountain_pass`、`leisure=nature_reserve`、`boundary=national_park/protected_area`、`amenity=shelter` 后得 **656,279 条**：村 28.7 万、自然村 19.4 万、山峰 3.5 万、镇 3.2 万、景点 1.0 万、观景点 3,263……
  - 仅存 `name, name_zh, name_en, kind, lon, lat, ele` 七列：**41.6 MB**；`zstd -19` 后 **24.4 MB**。
  - 加 FTS5 trigram 全文索引后 71.9 MB——**不值得**：`LIKE '%玉皇顶%'` 全表扫描实测 41–47 ms（桌面），手机慢几倍仍可接受。
  - 若日后要索引：androidx `sqlite-bundled` 编译时开启了 `SQLITE_ENABLE_FTS5`，[build.gradle](https://github.com/androidx/androidx/blob/androidx-main/sqlite/sqlite-bundled/build.gradle)；trigram 分词器可直接加速 `LIKE`，[SQLite FTS5 §4.3.4](https://www.sqlite.org/fts5.html#the_trigram_tokenizer)（按其设计，少于 3 个字符的查询无法走 trigram 索引，中文二字名仍需扫描）。
- 与 Protomaps 底图本身的 POI 图层相比：Protomaps `pois` 层包含 peak 等并带 `name:*` 多语言字段，但**按 QRank 显著度决定出现的最小 zoom**，低级别瓦片里并不包含全部山峰，也不是可查询的索引。[Protomaps Layers](https://docs.protomaps.com/basemaps/layers)。所以不要从瓦片里"搜"，另出索引文件。
- 许可：OSM 数据以 ODbL 发布，把过滤后的数据库分发给用户属于衍生数据库，需署名 OSM 并以 ODbL 提供该数据库（本项目本来就开放，无额外负担）。

### 2. 在线：自托管 Photon

- Apache-2.0；支持**边输边搜**、多语言、位置偏置、容错、按 OSM 标签和 bbox 过滤、反向地理编码。需 Java 21+，内置或外接 OpenSearch 3.x。[komoot/photon README](https://github.com/komoot/photon)
- 资源：全球库约 95 GB（2026），**建议至少 64 GB RAM**。[README](https://github.com/komoot/photon)。香港轻量 VPS 装不下全球库。
- 缩小办法（官方参数）：`-country-codes` 只导入指定国家；`-languages` 指定翻译语言（默认英/德/法/意，**需改为 `zh,en`**）；`-reverse-only` 更小。[usage.md](https://github.com/komoot/photon/blob/master/docs/usage.md)。只导入中国 + 港台 + 若干海外线路国家的**内存需求未见官方数字，需实测**。
- 查询：`/api?q=...&lat=..&lon=..&osm_tag=natural:peak&lang=zh`，`layer=` 过滤地点层级。[api-v1.md](https://github.com/komoot/photon/blob/master/docs/api-v1.md)
- **中文实测**（公共实例 photon.komoot.io，`osm_tag=natural:peak`，偏置点 34N 108E）：`贡嘎山`、`拔仙台`、`玉皇顶`、`梧桐山`、`Everest→珠穆朗玛峰` 均正确命中；`泰山` 命中浙江同名小山（OSM 中泰山主峰叫"玉皇顶"）；`武功山` 首条为日本長崎；`太白山` 首条为日本宫城；`四姑娘山幺妹峰` 无结果。不带标签过滤时 `鳌太` 能搜到鳌太线路径。→ 中文分词可用，**排序与"山名≠峰名"是主要问题**。
- 公共实例条款："合理数量"内可用，大量使用会被限流或封禁，不保证可用性。[README · Demo server](https://github.com/komoot/photon#demo-server)。可作开发期/早期兜底，但要走后端代理并可随时切换。

### 3. Nominatim（不推荐）

- 自建：最少 2 GB RAM；全球导入**建议 128 GB RAM、≥1 TB NVMe**，导入 2.5–5 天。[Nominatim Installation](https://nominatim.org/release-docs/latest/admin/Installation/)
- 公共实例：**绝对上限 1 req/s，且按整个 App 的所有用户合计**；**禁止自动补全**；App 必须能在不发版的情况下切走；建议走代理并缓存。[Nominatim Usage Policy](https://operations.osmfoundation.org/policies/nominatim/)
- 数据同为 OSM，相对 Photon 没有覆盖优势，只是更重。

### 4. 天地图地名搜索 V2.0（可选补充）

- 接口 `http://api.tianditu.gov.cn/v2/search?postStr={...}&tk=Key`，含普通搜索、视野内、周边、多边形、行政区、分类、统计七类；返回 `name`、`address`、`lonlat` 等，每页 1–300 条。[天地图地名搜索V2.0](http://lbs.tianditu.gov.cn/server/search2.html)
- 需在控制台免费申请 Key，可申请个人开发者；2020 版起实行**调用配额管理**，高需求需认证企业开发者。[天地图开发许可说明](http://lbs.tianditu.gov.cn/authorization/authorization.html)。**具体配额官方未公开**；二手资料称免费 1 万次/日（[CSDN 问答](https://ask.csdn.net/questions/9426258)，**未核实**，以控制台为准）。
- 坐标 CGCS2000，与 WGS-84 差异可忽略（见 [底图调研](https://github.com/zibyn/stars-outdoor/blob/research/basemaps/docs/research/basemaps.md)）。
- 不可离线；每个用户直连会共用一个 Key 的配额且 Key 暴露在 APK 中 → **必须经后端代理 + 缓存**。仅对大陆、仅在 Photon 结果少时补查。
- 覆盖面对中文山峰的优势**未量化**（本次环境对该站接口实测受 WAF 拦截），列入待办。

### 5. 商业 Geocoding

- **MapTiler**：Free 每月 1k 搜索会话、仅非商用；Flex $30/月含 3k 搜索会话，超出 $2.50/千会话。[MapTiler Pricing](https://www.maptiler.com/cloud/pricing/)
- **Stadia Maps**：Free 20 万 credit/月（不可商用）；地理编码/自动补全 v1 每次 20 credit，Autocomplete v2 每次 1 credit；Starter $20/月 100 万 credit；**Free/Starter 的地理编码结果只能临时存储**，Standard（$80）起才可永久存储。[Stadia Pricing](https://stadiamaps.com/pricing/)
- 两者都以 OSM 为主要数据源，中文覆盖不会优于自托管 Photon；且无离线能力。只在"自托管不可用"时作切换目标。

### 6. GeoNames（不单独使用）

- CC BY 4.0，每日导出，CN.zip 31 MB。[GeoNames dump](https://download.geonames.org/export/dump/)、[readme](https://download.geonames.org/export/dump/readme.txt)
- 实测 CN.txt 96.2 万行中山/峰类（`MT/PK/MTS/PKS`）仅 3,921 条，带汉字名 2,590 条——远少于 OSM。可作**山体/山脉别名补丁**的来源之一（如"泰山""武功山"整体名称），不作主源。

### 7. 坐标输入

纯本地解析，不需要服务：支持 `30.123, 103.456`、`N30°07'22" E103°27'21"`、`30°07.37'N` 等格式；两数中绝对值 >90 的判为经度。全部视为 WGS-84（与 #3 结论一致）。无外部依赖，写一个带单元测试的解析函数即可。

## 建议落地顺序

1. MVP：离线区域包内置 SQLite 地名索引 + 本地坐标解析；无网时搜索仍可用。排序 = 名称完全匹配 > 前缀 > 包含，同级按 `importance`、距当前视野中心距离。
2. 在线：后端代理 `/search` → 先转发 photon.komoot.io（缓存），用户量上来后换成香港 VPS 上只导入 `CN,HK,TW,+海外线路国家`、`-languages zh,en` 的自托管 Photon；App 端不感知切换。
3. 补丁：维护一张小的"山体别名表"（泰山→玉皇顶、太白山→拔仙台…），离线索引与在线结果都先查它。
4. 可选：大陆结果少于 N 条时由后端补查天地图。

## 待办 / 未核实

- 香港 VPS 上仅导入中国 + 若干国家的 Photon 所需内存与磁盘（**需实测**）。
- photon.komoot.io、MapTiler、Stadia 在大陆网络下的可达性与延迟（本环境无法测）。
- 天地图地名搜索的真实配额与对山峰/景区的覆盖（接口实测被 WAF 拦截）。
- 离线索引在中低端 Android 手机上的 `LIKE` 扫描耗时（桌面 ~45 ms，手机需实测；超 200 ms 再上 FTS5 trigram）。
- OSM 数据计数时间：Overpass（kumi.systems 镜像）2026-09-27 查询；Photon dump 数据时间戳 2026-09-19。
