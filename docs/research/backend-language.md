# 调研：后端语言的 GIS 生态对比

> 对应 issue #19（父决定 #10）。调研日期 2026-09-27。库的维护状态、版本、许可证取自 GitHub 仓库 API、各包仓库（crates.io / PyPI）和仓库 README，编号见文末「来源」。标「未核实」的是推断，没有一手数据支撑（主要是内存占用数字）。

前提：香港轻量服务器（约 2C2G），单实例，一个人维护；PostgreSQL + PostGIS；WebSocket 做队伍实时位置和对话；Android 客户端是 Kotlin（MapLibre Compose，预留 KMP）。

## 结论（TL;DR）

- **重活放进 PostGIS，语言差距大半消失。** 视野 / 缓冲带查询（`ST_DWithin`、`&&`）、抽稀（`ST_Simplify`）、测地距离（`ST_Length(geography)`）、坐标转换（`ST_Transform`）、DEM 取值（`ST_Value`，需 postgis_raster）、MVT 生成（`ST_AsMVT` + `ST_AsMVTGeom`，PostGIS ≥ 2.4）都在 SQL 里完成 [P1]。应用层只剩三件 PostGIS 做不了的事：**GPX/KML/FIT 文件解析与导出、WebSocket 广播、业务 CRUD**。按场景估算，约 **2/3 的 GIS 工作量与语言无关**（估算）。
- 剩下三件里，**GPX/KML** 五种语言都有能用的库；**FIT** 只有 Garmin 官方 SDK 覆盖 Java / JS / Python（外加 C# 等），Go 和 Rust 只有社区库 [G1][R6][R8]；**WebSocket** 五种语言都成熟。
- 真正拉开差距的是 **「与 Android 客户端共享代码」**：只有 Kotlin 能做到。MapLibre 官方的 **Spatial K**（KMP，含 GeoJSON、Turf 移植、GPX、单位换算）可以前后端共用同一套模型和算法 [K1]。
- 另一个维度是 **2C2G 上的运维负担**：Go / Rust 单二进制、常驻内存最小；JVM 常驻最大但可以用 `-Xmx` 压住（未核实具体数字）；Node / Python 居中。
- **推荐：Kotlin（JVM）+ Ktor**，空间计算交给 PostGIS，应用层用 Spatial K + JTS（必要时）+ Garmin FIT Java SDK。理由：与客户端同语言、可共享模型、FIT 有官方 SDK、个人维护只需一门语言。2G 内存对单个 JVM 服务够用。**备选 Go**：如果更看重低内存和单二进制部署，并且接受不与客户端共享代码。

## 场景 × 语言：主力库

维护状态为 GitHub 最后 push 日期 / 最新 release（截至 2026-09-27）。

| 场景 | Kotlin / JVM | Node.js | Go | Rust | Python |
|---|---|---|---|---|---|
| PostGIS 访问 | JDBC / Exposed 1.5.0（2026-08）[J6]；hibernate-spatial；几何用 WKB ↔ JTS | node-postgres（2026-09）[N5] / postgres.js 3.4.9 [N6]；几何多用 `ST_AsGeoJSON` 直出 | pgx v5.11.0（2026-09）[O4]；go-geom 自带 WKB/EWKB 编码 [O2] | sqlx 0.9.0（2026-05）[R7]；geozero 做 WKB ↔ geo [R3]（`postgis` crate 最后发布 2021，已停滞 [R9]） | GeoAlchemy2 0.20.0（2026-05）[Y5] + psycopg；Shapely 读 WKB |
| 几何算法（抽稀 / 距离 / 缓冲） | **JTS** 1.20.0（EPL-2.0 / EDL-1.0，2026-09 仍在提交）[J1]；**Spatial K turf**（KMP）[K1] | **Turf** v7.4.0（MIT，2026-08）[N1] | **orb** v0.13.0（MIT，2026-03；含 simplify、geo 距离、MVT 编码）[O1]；go-geom [O2]；simplefeatures [O3] | **georust/geo** 0.33.1（MIT/Apache，2026-04；含 RDP 抽稀、Haversine/测地距离）[R1] | **Shapely** 2.1.2（BSD-3）[Y1]；GeoPandas 1.1.4 [Y2]（后端用不上，偏分析） |
| GPX / KML | **JPX** v4.0.1（Apache-2.0，2026-09，**需 Java 25**）[J3]；Spatial K gpx [K1]；KML 用 GeoTools 或手写 | **@tmcw/togeojson** v7.1.2（BSD-2，KML/TCX/GPX→GeoJSON，最后发布 2025-05）[N3] | gpxgo（Apache-2.0，2026-08 有提交，无 release）[O5]；KML 需手写 | `gpx` 0.10.0（MIT，最后发布 2023-12）[R2]；KML 基本空白 | gpxpy 1.6.2（Apache-2.0，最后发布 2023-11，仓库 2026-09 仍活跃）[Y4]；KML 用 fastkml / GDAL |
| FIT | **Garmin 官方 FIT Java SDK** 21.217.0（2026-09）[G1] | **Garmin 官方 FIT JS SDK** 21.217.0 [G1] | tormoder/fit（MIT，最后 release 2023-10）[O6]，社区 | fitparser 0.11.0（MIT，2026-05）[R6]，社区，只读 | **Garmin 官方 FIT Python SDK** 21.217.0 [G1]；fitdecode 0.11.0（2025-08）[Y6] |
| 坐标系转换（应用层） | proj4j v1.4.3（Apache-2.0，2026-06）[J4] | proj4js v2.22.0（2026-08）[N4] | go-proj（cgo 绑 PROJ）[O7] | proj 0.31.0（绑 PROJ）[R4] | **pyproj** 3.8.0（MIT，2026-09）[Y3] |
| DEM / 栅格（应用层） | GeoTools 35.1（LGPL-2.1，2026-08）[J2]，偏重 | 基本空白（geotiff.js 等，未核实成熟度） | 需 cgo 绑 GDAL | gdal 0.19.0（绑 GDAL，2025-12）[R5] | GDAL / rasterio，最成熟 [Y7] |
| MVT（应用层生成） | no.ecc java-vector-tile（未核实维护状态） | **geojson-vt** v5.0.2（ISC，2026-09）+ vt-pbf（最后 release 2021）[N2] | orb `encoding/mvt` [O1] | `mvt` 0.15.0（2026-08）[R10]；geozero 也能写 MVT [R3] | mapbox-vector-tile（未核实） |
| WebSocket | **Ktor** 3.6.0（Apache-2.0，2026-09）[J5] | **ws** 8.22.0（MIT，2026-09）[N7] | coder/websocket v1.8.15（ISC，2026-06）[O8]；gorilla/websocket（最后 release 2024-06）[O9] | axum 0.8.9 / tokio-tungstenite 0.30.0 [R11] | FastAPI 0.141.1（Starlette WebSocket）[Y8] |
| 与 Android 共享 | **可以**：Spatial K、kotlinx.serialization 数据类放 KMP 公共模块 | 不行（只能共享 JSON Schema / OpenAPI） | 不行 | 不行（理论上可 uniffi，过重） | 不行 |
| 部署形态 | fat jar + JRE（或 GraalVM native，未核实与 Ktor 的兼容细节） | node + node_modules | **单静态二进制** | **单静态二进制** | 解释器 + venv；GDAL 等原生依赖 |

## 逐项分析

### 1. PostGIS 能吃掉多少

PostGIS 文档中与本项目直接相关的函数 [P1]：

- 视野 / 缓冲带查询：`geom && ST_MakeEnvelope(...)`、`ST_DWithin(geography, ..., meters)`，配 GiST 索引。
- 抽稀：`ST_Simplify`（Douglas-Peucker）/ `ST_SimplifyPreserveTopology`。
- 距离：`ST_Length(geom::geography)` 得测地米数。
- 爬升统计：`ST_DumpPoints` 取 Z 值后用窗口函数累加正差值，SQL 能写，但不如应用层直观。
- 坐标转换：`ST_Transform`（背后是 PROJ）。
- 高程：DEM 以 raster 导入（`raster2pgsql`），`ST_Value(rast, point)` 取点位海拔；剖面 = 沿线 `ST_LineInterpolatePoints` 取样后逐点 `ST_Value`。
- MVT：`ST_AsMVT(ST_AsMVTGeom(geom, ST_TileEnvelope(z,x,y)))` 一条 SQL 出一张瓦片。应用层只需把 `/tiles/{z}/{x}/{y}` 转成这条 SQL，**任何语言 20 行左右**（估算）。也可以直接部署 **Martin**（Rust，maplibre 官方，v1.16.1 2026-09，Apache-2.0）[T1]，零代码；pg_tileserv 最后 release 是 2024-02，活跃度较低 [T2]。

结论：表中「几何算法 / 坐标转换 / DEM / MVT」四行，只要按上面的方式走 PostGIS，就不再是选语言的依据。GeoTools、GDAL 绑定这类重依赖可以完全避开。

### 2. PostGIS 吃不掉的：文件解析

- **GPX**：五种语言都有库。JVM 的 JPX 最规范，但 v4 需要 Java 25 [J3]；如果服务端不想升到 25，可以用 JPX 3.x，或 Spatial K 的 gpx 模块 [K1]（与客户端共用）。
- **KML**：只有 Node（togeojson）[N3] 和 Python / JVM（经 GDAL / GeoTools）有现成方案；Go / Rust 基本要自己写 XML 映射。KML 结构简单，手写几十行也行。
- **FIT**：Garmin 官方 SDK 只覆盖 Java、JavaScript、Python 等，同一天发版（21.217.0，2026-09-22）[G1]。许可证是 Garmin 的「FIT Protocol License」，不是 OSI 开源许可，但允许免费使用和分发 [G1]。Go（tormoder/fit，最后 release 2023-10）[O6] 和 Rust（fitparser，只读）[R6] 是社区库，新机型消息定义可能滞后。
- 另一个选项：**GPX/FIT 在客户端解析**，上传 GeoJSON。客户端是 Kotlin，可用 Spatial K gpx + Garmin FIT Java SDK（Android 能用）。这样服务端连文件解析都不用做，只有 Kotlin 后端能和客户端共用这段代码。

### 3. WebSocket 实时广播

规模很小（一个队伍 2–20 人，5–30 秒一次上报，见 #7 调研）。五种语言的主力库都足够：Ktor [J5]、ws [N7]、coder/websocket [O8]、axum [R11]、FastAPI/Starlette [Y8]。单进程内存里维护 room map 即可。Go 的 gorilla/websocket 最后 release 是 2024-06 [O9]，新项目优先 coder/websocket。**这一项不构成差异。**

### 4. 与 Android 客户端共享

- 只有 Kotlin 能做：把 `Track`、`Waypoint`、`TeamMessage` 等数据类（kotlinx.serialization）和 Spatial K 的 GeoJSON 模型放进 KMP 公共模块，客户端和 Ktor 服务端直接引用。
- Spatial K 由 MapLibre 组织维护（v0.8.0，2026-09-14，MIT）[K1]，与客户端正在用的 maplibre-compose（v0.18.0，2026-09-25）[K2] 同源。
- 其它语言只能靠 OpenAPI / JSON Schema 生成代码，多一层维护。

### 5. 2C2G 上的运维负担（未核实具体数字）

- **Go / Rust**：单静态二进制，常驻内存通常几十 MB，`scp` + systemd 即可部署。Rust 编译慢、学习曲线陡，对一个人维护的业余项目性价比一般。
- **JVM（Kotlin）**：常驻内存最大，一个 Ktor 服务加 `-Xmx256m` 左右通常够用（未核实，需上线后观察）；和 PostgreSQL（shared_buffers 按 2G 机器配 256–512 MB）同机也放得下。启动慢几秒，对单实例常驻服务无所谓。
- **Node**：内存中等，依赖目录大，版本升级偶有破坏性变化。
- **Python**：GIS 生态最全（Shapely / pyproj / GDAL / rasterio），但这些优势都在「PostGIS 已经覆盖」的那几行；原生依赖（GDAL）在小服务器上装起来麻烦。

## 各语言短板一览

- **Kotlin/JVM**：内存最大；JPX v4 要求 Java 25 [J3]；应用层 MVT 库冷门（但用 `ST_AsMVT` 就不需要）。
- **Node.js**：无法与客户端共享；DEM 无成熟库；vt-pbf 多年未发版 [N2]。
- **Go**：FIT / KML 只有社区库或空白 [O6]；无法共享。
- **Rust**：FIT 只读社区库、`gpx` crate 2023 后未发版 [R2]、`postgis` crate 停滞 [R9]；开发效率对个人项目偏低；无法共享。
- **Python**：部署依赖重；异步 WebSocket 与 ORM 组合不如其它语言顺手（主观判断）；无法共享。

## 推荐

1. **首选 Kotlin（JVM）+ Ktor + PostgreSQL/PostGIS。**
   - 空间计算、抽稀、高程剖面、MVT 全部写成 SQL（`ST_Simplify` / `ST_Value` / `ST_AsMVT`）。
   - 应用层：Exposed 或纯 JDBC；Spatial K（GeoJSON、turf、gpx）与客户端共享；FIT 用 Garmin 官方 Java SDK；需要复杂几何时再加 JTS。
   - 路网 / 公开轨迹如果要做矢量瓦片，先直接部署 Martin，不在 Ktor 里写瓦片接口。
2. **备选 Go + pgx + orb + coder/websocket**：内存最省、部署最简单；代价是 FIT 用社区库、模型无法与客户端共享。
3. 不推荐 Rust（个人维护成本高、文件格式库偏弱）和 Python（优势被 PostGIS 覆盖，部署重）。Node 可行但没有突出优势。

## 下一步（建议）

- 在目标 VPS 上跑一个最小 Ktor + WebSocket + PostGIS 服务，确认 JVM 实际 RSS，验证 2G 内存余量。
- 用一条真实轨迹试 `ST_Simplify` + `ST_Value` 剖面，以及 `ST_AsMVT` 出瓦片的耗时。
- 决定 GPX/FIT 解析放客户端还是服务端（放客户端可以进一步减轻后端）。

## 来源

GitHub 数据来自 `gh api repos/<owner>/<repo>` 与 `/releases/latest`，查询日期 2026-09-27。

- [P1] PostGIS 文档：https://postgis.net/docs/ （`ST_AsMVT`、`ST_AsMVTGeom`、`ST_TileEnvelope`、`ST_Simplify`、`ST_DWithin`、`ST_Transform`、`ST_Value`、`ST_LineInterpolatePoints`）；仓库 https://github.com/postgis/postgis （GPL-2.0）
- [T1] maplibre/martin：https://github.com/maplibre/martin （martin-v1.16.1，2026-09-09，Apache-2.0）
- [T2] CrunchyData/pg_tileserv：https://github.com/CrunchyData/pg_tileserv （v1.0.11，2024-02-02）
- [K1] maplibre/spatial-k：https://github.com/maplibre/spatial-k （v0.8.0，2026-09-14，MIT；README 列出 geojson / turf / units / gpx / pmtiles 模块，支持 KMP 与 Java）
- [K2] maplibre/maplibre-compose：https://github.com/maplibre/maplibre-compose （v0.18.0，2026-09-25）
- [J1] locationtech/jts：https://github.com/locationtech/jts （1.20.0，2024-08-30；README：EPL-2.0 / EDL-1.0 双许可）
- [J2] geotools/geotools：https://github.com/geotools/geotools （35.1，2026-08-21，LGPL-2.1）
- [J3] jenetics/jpx：https://github.com/jenetics/jpx （v4.0.1，2026-09-18，Apache-2.0；README：「needs Java 25 to compile and run」）
- [J4] locationtech/proj4j：https://github.com/locationtech/proj4j （v1.4.3，2026-06-02）
- [J5] ktorio/ktor：https://github.com/ktorio/ktor （3.6.0，2026-09-18，Apache-2.0）
- [J6] JetBrains/Exposed：https://github.com/JetBrains/Exposed （1.5.0，2026-08-26）
- [G1] Garmin FIT SDK：https://github.com/garmin/fit-java-sdk 、https://github.com/garmin/fit-javascript-sdk 、https://github.com/garmin/fit-python-sdk （均为 21.217.0，2026-09-22；LICENSE.txt 为 FIT Protocol License Agreement）
- [N1] Turfjs/turf：https://github.com/Turfjs/turf （v7.4.0，2026-08-03，MIT）
- [N2] mapbox/geojson-vt：https://github.com/mapbox/geojson-vt （v5.0.2，2026-09-03，ISC）；mapbox/vt-pbf：https://github.com/mapbox/vt-pbf （v3.1.3，2021-06-07）
- [N3] placemark/togeojson：https://github.com/placemark/togeojson （v7.1.2，2025-05-31，BSD-2-Clause）
- [N4] proj4js/proj4js：https://github.com/proj4js/proj4js （v2.22.0，2026-08-31）
- [N5] brianc/node-postgres：https://github.com/brianc/node-postgres （2026-09-24 有提交，MIT）
- [N6] porsager/postgres：https://github.com/porsager/postgres （v3.4.9，2026-04-05）
- [N7] websockets/ws：https://github.com/websockets/ws （8.22.0，2026-09-26，MIT）
- [O1] paulmach/orb：https://github.com/paulmach/orb （v0.13.0，2026-03-30，MIT）
- [O2] twpayne/go-geom：https://github.com/twpayne/go-geom （2026-09-24 有提交，BSD-2-Clause）
- [O3] peterstace/simplefeatures：https://github.com/peterstace/simplefeatures （2026-08-21 有提交，MIT）
- [O4] jackc/pgx：https://github.com/jackc/pgx （v5.11.0，2026-09-07，MIT）
- [O5] tkrajina/gpxgo：https://github.com/tkrajina/gpxgo （2026-08-29 有提交，Apache-2.0）
- [O6] tormoder/fit：https://github.com/tormoder/fit （v0.15.0，2023-10-01，MIT）
- [O7] twpayne/go-proj：https://github.com/twpayne/go-proj （2026-08-16 有提交，MIT）
- [O8] coder/websocket：https://github.com/coder/websocket （v1.8.15，2026-06-15，ISC）
- [O9] gorilla/websocket：https://github.com/gorilla/websocket （v1.5.3，2024-06-14）
- [R1] georust/geo：https://github.com/georust/geo ；crates.io `geo` 0.33.1（2026-04-20）
- [R2] crates.io `gpx` 0.10.0（2023-12-04）；https://github.com/georust/gpx
- [R3] crates.io `geozero` 0.15.1（2025-12-11）；https://github.com/georust/geozero
- [R4] crates.io `proj` 0.31.0（2025-08-29）
- [R5] crates.io `gdal` 0.19.0（2025-12-23）
- [R6] crates.io `fitparser` 0.11.0（2026-05-01）；https://github.com/stadelmanma/fitparse-rs
- [R7] crates.io `sqlx` 0.9.0（2026-05-21）
- [R8] Garmin 组织下的 FIT SDK 仓库：java / javascript / python / c / cpp / csharp / objective-c / swift，无 Go / Rust（`gh api orgs/garmin/repos`，2026-09-27）
- [R9] crates.io `postgis` 0.9.0（2021-09-23）
- [R10] crates.io `mvt` 0.15.0（2026-08-01）
- [R11] tokio-rs/axum：https://github.com/tokio-rs/axum （axum-v0.8.9，2026-04-14）；crates.io `tokio-tungstenite` 0.30.0（2026-07-11）
- [Y1] shapely/shapely：https://github.com/shapely/shapely （2.1.2，2025-09-24，BSD-3-Clause）
- [Y2] geopandas/geopandas：https://github.com/geopandas/geopandas （v1.1.4，2026-06-26）
- [Y3] pyproj4/pyproj：https://github.com/pyproj4/pyproj （3.8.0，2026-09-05，MIT）
- [Y4] tkrajina/gpxpy：https://github.com/tkrajina/gpxpy ；PyPI gpxpy 1.6.2（2023-11-29）
- [Y5] geoalchemy/geoalchemy2：https://github.com/geoalchemy/geoalchemy2 （0.20.0，2026-05-12）
- [Y6] polyvertex/fitdecode：https://github.com/polyvertex/fitdecode ；PyPI 0.11.0（2025-08-06）
- [Y7] OSGeo/gdal：https://github.com/OSGeo/gdal （v3.13.3，2026-08-18）
- [Y8] fastapi/fastapi：https://github.com/fastapi/fastapi （0.141.1，2026-07-29）
