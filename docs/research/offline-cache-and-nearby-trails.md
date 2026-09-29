# 调研：地图缓存、离线下载与周边轨迹（同类应用做法）

调研日期：2026-09-29。对应我们的方案：#59（地图缓存）、#58（`/nearby-tracks`）、`docs/spec/mvp.md` §2.2 / §2.3 / §2.8、ADR 0002 / 0005。

**来源说明**
- 只用一手来源：官方帮助中心、官方博客、源码、厂商商店描述、条款。
- 二手来源一律标 **UNVERIFIED**。找不到写 **未找到**。
- Gaia GPS、AllTrails 的帮助页有 Cloudflare 拦截，是通过 Zendesk JSON API（`/api/v2/help_center/en-us/articles/<id>.json`）读到正文的。下文引用的仍是公开文章 URL。
- 源码引用的固定提交：
  - MapLibre Native `ea6ae05`：<https://github.com/maplibre/maplibre-native/blob/ea6ae05fe69793ecf27792fa4b333944b226a27f/>，下文简写 `MLN`。
  - OsmAnd `27a1a1d`：<https://github.com/osmandapp/OsmAnd/blob/27a1a1dea8247d8e8da2eeb831121e92d2b27b87/>，简写 `O`。
  - OsmAnd 文档源 `ec4e5e8`：<https://github.com/osmandapp/web/blob/ec4e5e87b1b4c1959a9d6924b913612447477c64/main/docs/user/>，简写 `D`，发布在 osmand.net/docs/user/…。
  - Organic Maps `5fc904b`：<https://github.com/organicmaps/organicmaps/blob/5fc904b0bf2499900d6773476ac0d61d15ec12b6/>，简写 `OM`。
  - Organic Maps 网站 `1658458`：<https://github.com/organicmaps/organicmaps.github.io/blob/16584581c97ad227a476faf5816ab29e6b806f20/>，简写 `W`。
  - 注意：我们实际用的是 maplibre-compose 0.18 背后的 `maplibre-native-ffi`，所含 MapLibre Native 版本可能比 `main` 旧。下面引用的缓存语义多年未变，但升级时值得再核一次。

## 1. 摘要

- **被动缓存和主动下载分开，是行业通行做法；"缓存不保证离线"也是通行说法。**
  - Gaia GPS 的每个图源都有"临时缓存"和"下载文件夹"两处存储，官方原话是要保证离线就得下载 [G1]。
  - CalTopo 说离线时"有时"能看到缓存瓦片 [C1]。
  - Locus Map 把"缓存"和"下载"写成两个概念 [L1]。
  - 没有一家提供"按区域列出缓存"或"在地图上画缓存覆盖范围"。画出来的只有 **下载** 的覆盖范围：Komoot 用紫色覆盖、Mapy.com 给区域上色、CalTopo 给格子上色 [K2][M1][C1]。
  - #59 的"不做"清单与行业一致。
- **缓存上限普遍是一个固定值或一个简单滑杆，很少分级。**
  - Gaia 固定 10 万块瓦片，启动时删掉最久未用的 1 万块 [G1]。
  - Locus 固定 50 MB，工作人员在论坛上的说法 [L3]。
  - Organic Maps 的卫星缓存默认 100 MB，可调 1–1000 MB [OM1]。
  - 我们默认 1 GB、可选 256 MB / 1 GB / 4 GB，比所有同类都大方，没有问题。
- **卫星影像几乎都受许可约束，只许缓存，不许（或限量）下载。**
  - Locus："Satellite maps can be cached for up to 360 days and can't be downloaded" [L2]。
  - Mapy.com：航拍图只能在线显示 [M2]。
  - CalTopo：Mapbox 卫星层"cannot be printed or downloaded for offline use" [C3]。
  - Gaia：Mapbox 卫星每次下载 1 万块，全部 Mapbox 图源合计 16 万块 [G4]。
  - 两步路官方帖：高德卫星图"暂不支持离线地图下载" [LB2]。
  - 我们对天地图"只被动缓存、不做主动缓存此区域"，与 Locus 的做法几乎一样，是稳妥的选择。
- **点击地图上的线，在线返回"经过这里的轨迹"列表，是主流交互。**
  - OsmAnd 点击路线符号时列出该点所有路线，选中后按 GPX 显示整条，可保存、可导航 [OA5]。
  - 两步路有"指定点周边"搜索附近轨迹 [LB4]。
  - CalTopo 点对象会弹出详情 [C4]。
  - 离线时能点出详情的，都是 **随下载一起打包的数据**：AllTrails 的离线区域带轨迹记录 [A3]，OsmAnd 的路线在 .obf 里 [OA5]。
  - 没有任何一家文档说能从 **被动缓存** 里点出完整轨迹。我们"离线只列离线包"的做法与行业一致。
- **撤回、隐私生效靠定期重建，而且是月级。**
  - Strava 全球热力图、Komoot 热力图、AllTrails 社区热力图都是每月刷新，只统计近 12 个月的公开活动 [S1][K4][A4]。
  - Komoot 明说隐私变更"at the next monthly update"才生效 [K4]。
  - 我们的轨迹瓦片 `max-age` 60 s，联网后很快更新；只有离线快照和缓存会滞后。这比行业快得多。
  - 值得借鉴的一点是 **在条款或帮助里写明滞后**，Komoot 和 AllTrails 都写了。
- **最值得修正的一处在 #59 的实现细节。**
  - MapLibre 的 `setMaximumAmbientCacheSize` 文档要求"should always be called before using the database"，所以要在地图第一次加载之前调用 [MLN2]。
  - `clearAmbientCache()` 在 autopack 打开时（默认打开）会自动 `VACUUM`，文件会变小 [MLN1][MLN2]。#59 担心的"要不要 pack"不需要额外代码。

## 2. 各问题对照

### 2.1 被动缓存与主动下载；卫星图能否缓存或下载

| 应用 | 被动缓存看过的瓦片 | 缓存管理（用量 / 上限 / 清除 / 按区域） | 主动下载 | 卫星图离线 |
|---|---|---|---|---|
| **Gaia GPS** | 有，与下载分开存 [G1] | iOS 显示存储用量、可清缓存；Android 可"Clear Automatic Map Cache"；上限固定 10 万块、启动时删 1 万块，用户不可调；不按区域列出 [G1][G2][G3] | 矩形或沿轨迹走廊，加缩放范围；每个下载单独列出，并显示块数 [G1][G5] | Mapbox 卫星每次 1 万块，全部 Mapbox 图源合计 16 万块；Esri 影像已下线；USDA 航拍无总量限制 [G4][G6] |
| **CalTopo** | 有，官方说"sometimes"可离线看，而且只含部分缩放级 [C1] | 设置里有清缓存；用量和上限 **未找到** [C1] | 按 15′ 网格选象限，显示体积和剩余空间，用颜色显示已下载范围；需付费；每账号每月 200 GB、每年 300 GB [C1][C2] | Mapbox 卫星"cannot be … downloaded"；Vantor/NAIP 影像可下载 [C3] |
| **AllTrails** | 作为功能 **未找到**，只在"Clear all cached data"入口里出现 [A1] | 可清缓存数据、删除下载 [A1] | 按单条轨迹，或按自定义区域；每个区域 ≤500 条轨迹，区域太大时要求放大 [A2][A3] | 卫星是图层之一；能否单独下载卫星 **未找到** [A5] |
| **Strava** | **未找到** | **未找到** | 只能离线单条路线（订阅功能） [S5] | **未找到** |
| **Komoot** | **未找到**；官方说"no global offline switch… must be downloaded in advance" [K1] | 可"Clear all offline data"；已下载区域以紫色覆盖显示 [K1][K2] | 按区域或单条路线 [K1] | **未找到** |
| **Outdooractive** | **未找到** | 设置 → 下载或存储管理；退出登录时删除 [OD1] | 按约 40×40 km 的块；可选全部缩放级或只下最后两级；下载前显示体积 [OD1] | Pro 有卫星；能否离线 **未找到** [OD2] |
| **Locus Map** | 有，是刻意设计的时效缓存："LoMap … cached for 90 days … Satellite … up to one year" [L1] | 每张图可设"Cache timeout"（小时）并"Clear Cache"；旧版信息页显示缓存 MB；上限固定 50 MB 滚动淘汰（工作人员说法），用户能否调 **未找到** [L2][L3] | 可按区域、轨迹、点下载在线图，显示块数和体积；每天 ≤1 万块 [L1] | "can be cached for up to 360 days and can't be downloaded"，仅 Premium Gold [L2] |
| **Mapy.com** | **未找到** | 已下载区域在地图上上色并列表显示，可删除 [M1] | 按国家或地区；免费版 1 个国家 [M1] | 航拍图"can only be displayed if you are connected" [M2] |
| **OsmAnd** | 在线栅格图源看过即缓存，每个图源一个 SQLite 文件，列表显示文件大小 [OA1] | 有"Clear all tiles"；可按图源设过期分钟数，默认永不过期；**无** 容量上限 [OA1][OA2] | 长按地图 → 下载地图，选缩放范围，显示块数和体积；只限并发 50，**没有** 块数上限；文档提醒"some services may block large packet downloads" [OA3] | 内置 Bing、ArcGIS 影像图源，走同一下载流程 [OA4] |
| **Organic Maps** | 矢量图纯离线，按区域 .mwm；2026-06 起有实验性卫星图，要用户自填瓦片 URL [OM2] | 卫星缓存是 LRU，默认 100 MB，可调 1–1000 MB；换 URL 就清空缓存 [OM1] | 只有矢量区域下载 [OM2] | 只被动缓存，**没有** 区域预下载 [OM1] |
| **两步路** | 有；官方帖原文是"通过'我——设置——清除缓存'来清除地图缓存" [LB1] | 有清除入口；用量和上限 **未找到** [LB1] | 沿轨迹下载，或自定义多边形；按层级下载 [LB2] | 高德卫星"暂不支持离线地图下载"；谷歌卫星可以用 MOBAC 在电脑端下好再导入 [LB2] |
| **六只脚** | **未找到** | **未找到** | "多种地图离线下载，可视化选择下载范围" [SJ1] | 地图类型含卫星；限额 **未找到** [SJ1] |
| **高德地图** | **未找到** | "软件设置→缓存"清空时会连离线地图一起删 [AM1] | 按城市下载 [AM1] | **未找到** |

**说明**
- 用户能"读到缓存用量、调上限、一键清除"三项齐全的只有 Organic Maps（卫星缓存） [OM1]。Gaia 有用量和清除，上限固定 [G1][G2]。Locus 每张图有清除和时效 [L2]。我们的设置行覆盖了这三项，属于做得周到的一侧。
- 按许可给缓存设时效的，只有 Locus（LoMaps 90 天，卫星 360 天） [L1][L2]。MapLibre 默认按 HTTP 缓存头处理，离线时照用过期瓦片，除非响应带 `must-revalidate`（`MLN/src/mln/tile/tile_loader_impl.hpp:119-131`）。天地图公开的服务条款里没有关于缓存、离线或批量下载的规定，只有"禁止对网站进行技术性破坏""应注明来源"等 [TDT1]。天地图的配额数字只在需要登录的控制台里，**未找到** 公开数字；博客上"个人 Key 每日 1 万次"之类的说法 **UNVERIFIED** [TDT2]。

### 2.2 周边轨迹、他人轨迹、热力图、官方路线

| 应用 | 在线怎么取 | 离线是否可用 | 点击地图上的线 |
|---|---|---|---|
| **Gaia GPS** | 多为栅格瓦片 [G1]；公开轨迹是叠加图层（深绿线） [G7] | 公开轨迹叠加层能否离线 **未找到**；离线的办法是先"Save / Save Offline"成自己的副本 [G7]；离线寻路数据随地图下载 [G8] | 点线后在抽屉里列出，再点进详情，可保存 [G7]；点地图还会列出附近的步道和登山口 [G9] |
| **CalTopo** | "Shared Maps"叠加层，显示所有公开地图的对象；取数方式（瓦片还是查询）**未找到** [C4] | 该层不在"可下载"之列，离线 **未找到** [C4] | 点对象弹出详情：距离、时间、高程剖面、加入当前地图、导出 [C4][C5] |
| **AllTrails** | 社区热力图叠加（Plus/Peak） [A4]；附近登山口图钉 [A5] | 离线区域 **打包了轨迹记录**：离线时"tap on individual trails to view details" [A3]；热力图离线 **未找到** | 离线时点轨迹能看详情 [A3]；热力线能否点 **未找到** |
| **Strava** | 四种热力图：全球图每月更新，周图每天更新 [S1] | 热力图离线 **未找到**；只有路线能离线 [S5] | **未找到** |
| **Komoot** | 热力图每月从零重建 [K4]；Highlights 是橙色线 [K5]；可对可见区域搜路线，或"Select a specific point … to find routes nearby" [K6] | 只有下载的路线离线可用（带地图预览、Highlight 照片） [K2]；发现路线、查看热力离线 **未找到** | 点 Highlight 打开详情页，可保存或加入路线 [K5] |
| **Outdooractive** | Route Finder 搜索；步道网叠加层 [OD3][OD4] | 下载的路线含地图片段和全部文字、媒体 [OD1] | **未找到** |
| **Mapy.com** | 路标步道画在底图里 [M2] | 随区域离线包一起 [M1][M2] | 点地点有"Trip suggestions" [M3]；点线 **未找到** |
| **OsmAnd** | OSM 路线关系在 .obf 矢量图里 [OA5] | 在 .obf 里，所以离线可用（据数据位置推断，文档没有逐字写明） | 点路线符号 → **列出该点附近 / 重叠的所有路线** → 选中后按 GPX 显示整条（距离、爬升、剖面），可"Save as GPX"、可按轨迹导航 [OA5] |
| **Organic Maps** | Hiking 图层，数据在 .mwm 里 [OM3] | 随区域地图离线 | 2026-04 起点路线会高亮并显示高程剖面 [OM3]；能否保存或循迹 **未找到** |
| **两步路** | "轨迹云→周边轨迹→指定点周边"，在线搜出附近所有轨迹 [LB4]；商店页写"轨迹路网 … 热门程度一目了然""热力图下载" [LB3] | 商店页称可以下载热力图 [LB3]；路网在 2023 年取消的说法 **UNVERIFIED** [LB6] | **未找到** |
| **六只脚** | "查看附近的行程和搜索行程" [SJ1]；"路网循迹" [SJ2] | 离线轨迹导航 [SJ2]；周边轨迹离线 **未找到** | **未找到** |

**说明**
- 两类做法并存：
  - **第一类：随下载打包。** AllTrails 离线区域、OsmAnd、Organic Maps、Mapy.com 属于这一类。离线时能点出完整线路，和我们离线包里的 `platform.geojson` / `public-tracks.geojson` 快照是同一路线。
  - **第二类：只在线的聚合层。** Strava、Komoot、AllTrails 的热力图，Gaia 公开轨迹，CalTopo Shared Maps 属于这一类。离线要么没有，要么没写。
- 没有一家把"被动缓存到的线"当作离线可查询的数据。
- "点一处 → 列出经过这里的多条 → 选一条看整条 / 保存 / 循迹"：OsmAnd 的交互与我们几乎相同 [OA5]。两步路的"指定点周边"是同一种在线查询形态 [LB4]。#58 把两次请求合成一次，对交互没有影响，只是省一次往返。

### 2.3 隐私、删除、撤回

| 应用 | 聚合或公开层纳入什么 | 撤回后多久生效 | 起终点保护 | 他人已存副本 |
|---|---|---|---|---|
| **Strava** | 只纳入"Everyone"的活动；排除被地图可见性隐藏的部分和选择退出的用户；多人经过才显示热度 [S1][S2] | 全球图"updated monthly"，撤回在下次月更时生效的逐字依据 **UNVERIFIED**；周热力图退出后"at the next daily update"移除 [S1][S3] | 起终点半径最多 1 英里内隐藏；按地址设置的规则回溯到历史活动，另两项只对以后的活动生效 [S4] | 注销时删除全部个人数据；创建的公开路段可能保留 [S6] |
| **Komoot** | 排除"Only you"和隐私区；"cut the first and last few hundred metres of every activity"；多人重叠才显示 [K4] | "removes any affected track segments at the next monthly update" [K4] | 砍掉首尾数百米 [K4] | 删除即永久移除 [K7]；他人副本 **未找到** |
| **AllTrails** | 只纳入公开记录，近 12 个月 [A4] | "Heatmaps are refreshed monthly, so changes may not be immediate" [A4] | **未找到** | 他人下载后变成自己"Custom routes & maps"里的副本 [A6]；作者删除后的影响 **未找到** |
| **Gaia GPS** | 可见性分 Everyone / Followers / Only You，默认 **公开** [G10][G11] | **未找到** | **未找到** | 保存他人公开轨迹得到一份"View Copy"副本（据此推断撤回不影响副本，文档没有明说） [G7] |
| **CalTopo** | 地图改为非公开，"removes all its objects from the Shared Maps layer"；默认不公开 [C5] | **未找到** | **未找到** | **未找到** |
| **Outdooractive** | 默认私有，发布后注册用户可见，可随时改回 [OD5] | **未找到** | **未找到** | **未找到** |
| **两步路** | 上传时可选"作为私有轨迹上传" [LB4] | **未找到** | **未找到** | 注销后"删除……或进行匿名化处理" [LB5]；他人副本 **未找到** |
| **六只脚** | 用户协议授予平台"全球范围内、免费、非独家、可转授权"的使用权 [SJ3] | **未找到** | **未找到** | **未找到** |

**说明**
- 没有一家文档写"别人已下载 / 已缓存的数据会被远程抹掉"。行业默认的做法是：聚合层按周期重建，个人副本不追。我们的"联网刷新或清除为止"是同一口径，而且联网后 60 s 内就会反映到在线瓦片上。
- **起终点隐藏** 是行业共识：Strava 最多 1 英里，Komoot 砍掉数百米 [S4][K4]。我们在公开时隐藏起点和终点各 200 m，同一思路，只是距离偏小。
- **多人重叠才显示** 是热力图的做法（Strava、Komoot） [S1][K4]。我们的公开轨迹是逐条显示的，与 Gaia 公开轨迹、CalTopo Shared Maps 同类，本来就不适用这条规则。

## 3. 对照我们的方案

| 我们的做法 | 行业是否相同 | 判断 |
|---|---|---|
| 地图缓存与离线包分开，缓存"不保证可用"（CONTEXT、§2.3） | 相同：Gaia、CalTopo、Locus 都这样分，都这样说 [G1][C1][L1] | 保持 |
| 缓存用 MapLibre 自带的 LRU；上限 256 MB / 1 GB / 4 GB，默认 1 GB | Gaia 固定 10 万块 [G1]；Organic Maps 默认 100 MB、可调 1–1000 MB [OM1]；Locus 50 MB [L3] | 保持。我们的默认值更大，对山区卫星图有意义 |
| 设置行显示用量、上限，可清除；用 `clearAmbientCache` 而不是 `invalidate` | Gaia、Organic Maps 类似 [G2][OM1]；源码证实 `invalidate` 只把瓦片的 `expires` 置 0、`must_revalidate` 置 1，不删行 [MLN1] | 保持。选 `clear` 是对的 |
| 不做主动"缓存此区域"（天地图许可） | 相同：Locus 卫星只缓存不下载 [L2]；Mapy 航拍只在线 [M2]；CalTopo 的 Mapbox 卫星不可下载 [C3]；两步路说高德卫星不能离线 [LB2] | 保持。这是行业里对受限影像的标准处理 |
| 不按区域列出缓存，不画缓存覆盖 | 相同：没有一家做 | 保持 |
| 缓存放 `cacheDir`，系统可能清掉 | AllTrails 明说 iOS 会随机清缓存 [A1]；Gaia 称之为"temporary cache" [G1] | 保持 |
| 离线时周边路网同时画缓存瓦片和包内快照（#59） | 没有同类可比（别家的公开层离线多半不可用） | 可以。叠深是已知代价 |
| 离线时"经过这里的轨迹"只列离线包，提示原因 | 相同：离线能点出详情的只有打包数据 [A3][OA5] | 保持 |
| 下架或撤回的轨迹在离线快照、缓存里滞留到刷新 | 行业是月级重建，个人副本不追 [S1][K4][A4] | 保持。只欠一句用户可见的说明（见 O3） |
| #58 一次请求返回两类，带 `kind` | 形态同 OsmAnd"点一处列出所有路线" [OA5]、两步路"指定点周边" [LB4] | 保持；"不急"的判断也合理 |
| 公开时隐藏起点和终点各 200 m | 同一思路，Strava 最多 1 英里、Komoot 数百米 [S4][K4] | 可以，见 O6 |

### 可优化点

**O1. 在地图首次加载 *之前* 设置缓存上限（小）**
- 依据：`setMaximumAmbientCacheSize` 的文档原文是"This method should always be called before using the database, otherwise the default maximum size will be used"（`MLN/include/mln/storage/database_file_source.hpp` 约 105–125 行）。默认上限是 `DEFAULT_MAX_CACHE_SIZE = 50 * 1024 * 1024`（`MLN/include/mln/util/constants.hpp:53`）。
- #59 写的是"启动时"。实现时要确保调用发生在 `Application.onCreate` 或第一个 `MaplibreMap` 组合之前，而不是在地图回调里。改上限后源码会自动裁剪（同文件注释："potentially expensive because it will try to trim"），所以在设置页里改完直接调用即可，不必重启。

**O2. 清除后不需要自己 pack 或 vacuum，但清除要放到后台（小）**
- 依据：`clearAmbientCache()` 删掉不属于离线区域的 `tiles` / `resources` 行后，会执行 `if (autopack) vacuum();`（`MLN/platform/default/src/mln/storage/offline_database.cpp:754-783`）。`vacuum()` 会切换到 `auto_vacuum = INCREMENTAL` 并执行 `VACUUM`（同文件 229-238）。autopack 默认开启（`database_file_source.hpp:56-61`："By default, packing is enabled"）。
- 结论：#59 验收里的"清除后确认文件确实变小"应当能直接通过，不需要额外代码。
- 需要注意两点：
  - 头文件提醒这个操作"potentially slow"。1–4 GB 的库执行 `VACUUM` 可能要好几秒，UI 上给一个"清除中…"状态就够了。
  - 如果 maplibre-native-ffi 所带的版本不同，要用一次真机测量确认。

**O3. 在帮助文字和条款里写明两件事（小，只改文案）**
- 依据：
  - Gaia 写"in order to guarantee that maps will be available offline they should be downloaded" [G1]，CalTopo 写"sometimes" [C1]。
  - AllTrails 写"Heatmaps are refreshed monthly, so changes may not be immediate" [A4]，Komoot 写"at the next monthly update" [K4]。
- 建议：
  - 在设置行下加一行小字："缓存不保证离线可用，要离线请下载离线包。"
  - 在公开轨迹的条款或帮助里写明："撤回公开后，他人已下载的离线包或缓存中可能仍显示，直到其联网刷新。"这和 ADR 0005 要求写进条款的"晋升"说明可以放在一起。

**O4. 轨迹瓦片和天地图代理的 URL 要保持稳定（小，只是一条约束）**
- 依据：MapLibre 的缓存以资源 URL 为键。Organic Maps 的卫星缓存在更换 URL 时整个清空（`OM/libs/map/raster_tile_provider.hpp:76-80`），道理相同：URL 一变，旧缓存就等于作废。
- 建议：`/v1/tiles/tianditu/...`、`/tiles/platform-tracks/...`、`/tiles/public-tracks/...` 的路径里不要带版本号、key、时间戳或会话参数。缓存失效交给 `max-age` / ETag 处理。现在的路径看起来已满足（`Basemaps.kt` 用的是 `$apiUrl/v1/tiles/tianditu/$layer/{z}/{x}/{y}`），把这条写进 #59 的注意事项或 ADR 即可。
- 同理，#58 删掉 `/public-tracks` 时，**瓦片** 路径不要跟着改名。

**O5. 离线包覆盖范围画在地图上（中，#59 之外，可选）**
- 依据：Komoot 用紫色覆盖显示已下载区域 [K2]；Mapy.com 在地图上给已下载区域上色 [M1]；CalTopo 用颜色标出已下载格子和需要重下的格子 [C1]。
- 我们刻意不画 **缓存** 范围，这没问题。但离线时用户最需要知道的是"哪里有离线包、可以点出轨迹"，这正好配合 #59 的提示"离线中：只能列出离线包内的轨迹"。
- 实现：每个包的 bbox 或缓冲带多边形已在包元数据里，加一个 GeoJSON 线图层，只在离线时或离线地图页显示，大约几十行。
- 不做也不影响 #59。

**O6. 起终点隐藏距离可以复议（小，只改一个常数，但要先想清楚）**
- 依据：Strava 允许最多 1 英里（约 1.6 km）[S4]；Komoot 对热力图砍首尾"few hundred metres"[K4]。
- 我们固定 200 m，公开轨迹又是逐条显示的，比热力图更容易反推住址。
- 可以考虑提高到 500 m，或者让用户在 200 / 500 / 1000 m 之间选。这是产品决定，不是本调研能定的。

**O7. 不建议做的**
- 与 #59 的"不做"一致，本调研没有找到反对理由：
  - 从缓存瓦片里 `queryRenderedFeatures` 拼出"经过这里的轨迹"：瓦片里的线经过简化和裁剪，拿不到完整轨迹，也没有同类这样做。
  - 按许可给缓存设时效（Locus 式）：天地图条款没有要求 [TDT1]，MapLibre 也没有按图源设时效的接口，要做就得自己改 SQLite 表。
  - 缓存上限改成用户可以任意填写：Organic Maps 用的是 1–1000 MB 滑杆 [OM1]，我们的三档已经足够。

**补充观察（不算优化点）**
- 当前 `Basemaps.kt:50` 离线时去掉了 `dem-remote`，所以在线看过的山体阴影、分层设色在没有离线包的地方离线不会显示。CONTEXT 里地图缓存的列举（底图、卫星、等高线、周边路网）本来就不含山体阴影，二者一致。若将来想让它也"尽力显示"，会遇到和周边路网相同的"叠两次颜色变深"问题；山体阴影叠深比轨迹更难看，维持现状更合理。

## 4. 来源

**MapLibre Native**（`MLN` = `https://github.com/maplibre/maplibre-native/blob/ea6ae05fe69793ecf27792fa4b333944b226a27f/`）
- MLN1：`platform/default/src/mln/storage/offline_database.cpp:721-750`（invalidate）、`:754-783`（clear + autopack vacuum）、`:229-238`（vacuum）、`:1255-1260`（LRU 淘汰说明）
- MLN2：`include/mln/storage/database_file_source.hpp:56-61`、`:80-125`；`include/mln/util/constants.hpp:53`；`src/mln/tile/tile_loader_impl.hpp:119-131`

**Gaia GPS**
- G1：<https://help.gaiagps.com/hc/en-us/articles/115003523787-How-Do-Maps-Work-in-Gaia-GPS>
- G2：<https://help.gaiagps.com/hc/en-us/articles/115003639108>、<https://help.gaiagps.com/hc/en-us/articles/115003525507>
- G3：<https://help.gaiagps.com/hc/en-us/articles/115003524727>
- G4：<https://help.gaiagps.com/hc/en-us/articles/360000915488-Individual-Offline-Map-Tile-Limits>
- G5：<https://help.gaiagps.com/hc/en-us/articles/360047131513-Download-Maps-for-Offline-Use>
- G6：<https://help.gaiagps.com/hc/en-us/articles/8107043929111-Map-Download-Limit-for-Mapbox-Layers>
- G7：<https://help.gaiagps.com/hc/en-us/articles/115003526307-View-and-Add-Public-Tracks-to-your-Account>
- G8：<https://help.gaiagps.com/hc/en-us/articles/4406144762135>
- G9：<https://help.gaiagps.com/hc/en-us/articles/360046448474>
- G10：<https://help.gaiagps.com/hc/en-us/articles/115003523847>
- G11：<https://help.gaiagps.com/hc/en-us/articles/4403979404311-Privacy-and-Data-controls>

**CalTopo**
- C1：<https://training.caltopo.com/all_users/mobile/offline>
- C2：<https://training.caltopo.com/all_users/desktop/download>
- C3：<https://blog.caltopo.com/2026/04/22/new-imagery/>
- C4：<https://training.caltopo.com/all_users/overlays/overlay-desc>
- C5：<https://blog.caltopo.com/2026/03/27/update-discover-new-routes-maps-with-shared-maps/>
- 另：<https://blog.caltopo.com/2025/08/25/new-feature-offline-mode/>
- 另：<https://blog.caltopo.com/2014/04/25/custom-map-layers/>
- 另：Strava 热力图作为 CalTopo 自定义图层的用法 **UNVERIFIED**：<https://alonsalant.medium.com/how-to-strava-heatmap-in-caltopo-b87d0ede763d>

**AllTrails**
- A1：<https://support.alltrails.com/hc/en-us/articles/37214048532372-How-to-delete-a-downloaded-map>、<https://support.alltrails.com/hc/en-us/articles/37213988725780>
- A2：<https://support.alltrails.com/hc/en-us/articles/37213318235028>
- A3：<https://support.alltrails.com/hc/en-us/articles/37758009767444-Download-custom-areas-for-offline-use>
- A4：<https://support.alltrails.com/hc/en-us/articles/36898308536852-Community-Heatmaps>
- A5：<https://support.alltrails.com/hc/en-us/articles/37228180990228>
- A6：<https://support.alltrails.com/hc/en-us/articles/37213312179220>
- 另：<https://support.alltrails.com/hc/en-us/articles/4410205748244>

**Strava**
- S1：<https://support.strava.com/en-us/articles/15401880-the-global-heatmap-and-strava-metro>、<https://support.strava.com/en-us/articles/16046277-a-guide-to-strava-heatmaps>
- S2：<https://support.strava.com/en-us/articles/15401987-activity-privacy-controls>、<https://support.strava.com/en-us/articles/15402017-product-improvements>
- S3：<https://support.strava.com/en-us/articles/15401630-weekly-heatmap>
- S4：<https://support.strava.com/en-us/articles/15402012-edit-map-visibility>
- S5：<https://support.strava.com/en-us/articles/15402044-strava-subscription-features>、<https://support.strava.com/en-us/articles/15401660-creating-routes-on-mobile>
- S6：<https://support.strava.com/en-us/articles/15401906-how-do-i-delete-my-strava-account>、<https://support.strava.com/en-us/articles/15401945-strava-segments>
- FATMAP：2024-10-01 停运，并入 Strava：<https://press.strava.com/articles/fatmap-is-transitioning-to-strava>

**Komoot**
- K1：<https://support.komoot.com/hc/en-us/articles/10356476920986>
- K2：<https://support.komoot.com/hc/en-us/articles/10621431252250>
- K4：<https://support.komoot.com/hc/en-us/articles/9468742939802>
- K5：<https://support.komoot.com/hc/en-us/articles/10194639751450>
- K6：<https://support.komoot.com/hc/en-us/articles/10207999797530>
- K7：<https://support.komoot.com/hc/en-us/articles/10384661449626>
- 另：<https://support.komoot.com/hc/en-us/articles/10197721520794>
- 另：<https://support.komoot.com/hc/en-us/articles/10194649347098>

**Outdooractive**
- OD1：<https://www.outdooractive.com/en/knowledgepage/saving-routes-and-maps-offline/37514067/>
- OD2：<https://www.outdooractive.com/en/membership/plans.html>
- OD3：<https://www.outdooractive.com/en/knowledgepage/route-finder-the-perfect-route-for-your-next-adventure/45656677/>
- OD4：<https://www.outdooractive.com/en/knowledgepage/map/804475446/>
- OD5：<https://www.outdooractive.com/en/knowledgepage/my-data-and-privacy-settings/52442769/>

**Locus Map**
- L1：<https://docs.locusmap.app/doku.php?id=manual:user_guide:maps_download>
- L2：<https://docs.locusmap.app/doku.php?id=manual:user_guide:maps_online>、<https://docs.locusmap.eu/doku.php?id=manual:user_guide:maps_online>
- L3：官方帮助台工作人员回复：<https://help.locusmap.eu/topic/38140-offline-use-of-satelliteaerial-maps-understanding-caching-of-tiles>

**Mapy.com**
- M1：<https://help.mapy.com/offline-maps/>
- M2：<https://help.mapy.com/map/map-types/>、<https://help.mapy.com/map/layers/>
- M3：<https://help.mapy.com/place-detail/>

**OsmAnd**（`O`、`D` 见文首固定提交）
- OA1：<https://osmand.net/docs/user/map/raster-maps#clear-tile-cache>；`D` map/raster-maps.md:246、:248、:270、:282-304；`O` OsmAnd/src/net/osmand/plus/resources/TilesCache.java:155-170、:215-231
- OA2：`O` OsmAnd/src/net/osmand/plus/resources/SQLiteTileSource.java:69（`expirationTimeMillis = -1; // never`）；`O` OsmAnd/res/values/strings.xml:4240
- OA3：<https://osmand.net/docs/user/map/raster-maps#download--update-tiles>；`D` map/raster-maps.md:306-339；`O` OsmAnd/src/net/osmand/plus/plugins/rastermaps/DownloadTilesFragment.java:433-446、DownloadTilesTask.java:29、:174
- OA4：`O` OsmAnd-java/src/main/java/net/osmand/map/TileSourceManager.java:789；在线列表 <https://download.osmand.net/tile_sources>（没有固定版本）
- OA5：<https://osmand.net/docs/user/map/routes>；`D` map/routes/index.md:20、:38、:83-87、:119-153、:173-175
- OA6：地图更新：<https://osmand.net/docs/user/personal/maps-resources#updates-menu>、`#live-updates`；`D` personal/maps-resources.md:331-345、:398-409

**Organic Maps**（`OM`、`W` 见文首固定提交）
- OM1：`OM` libs/map/raster_tile_provider.hpp:18-27、:42-55、:76-80；libs/map/framework.hpp:735-736；iphone/Maps/UI/Settings/MapTiles/MapTilesSettingsInteractor.swift:43-49；data/strings/strings.txt:6951、7013、7137、7249
- OM2：`W` content/news/2026-06-15/600/index.md:12、content/news/2026-06-29/610/index.md:25；`OM` README.md:6、:45
- OM3：`W` content/news/2026-04-07/570/index.md:17、:27；content/news/2025-10-23/460/index.md:27；`OM` data/strings/strings.txt:25308

**两步路**
- LB1：<https://www.2bulu.com/community/gotohuatinfo.htm?id=17384>（官方小助手，2017）
- LB2：<https://www.2bulu.com/community/gotohuatinfo.htm?id=18154>（官方小助手，2017）
- LB3：应用宝商店页：<https://sj.qq.com/appdetail/com.lolaage.tbulu.tools>
- LB4：<https://www.2bulu.com/community/gotohuatinfo.htm?id=qXWu1oO5tss%3D>（官方 FAQ，2014，可能已过时）
- LB5：<https://2bulu.com/about/privacy_app.htm>
- LB6：UNVERIFIED 个人博客：<https://zhiqiang.org/outdoor/get-back-2bulu-roads.html>
- 另：<https://2bulu.com/community/gotohuatinfo.htm?id=19728>

**六只脚**
- SJ1：<https://www.foooooot.com/app/iphone/>、<https://sj.qq.com/appdetail/com.topgether.sixfoot>
- SJ2：<https://app.mi.com/details?id=com.topgether.sixfoot>
- SJ3：<https://image1-oss.v.lvye.com/cert/app-sixfoot-yonghuxieyi.html>（通过搜索"六只脚用户协议"找到，托管在 lvye.com）
- 六只脚几乎没有功能级帮助文档，本文大部分格子为"未找到"。

**高德地图**
- AM1：<https://wap-org.amap.com/userhelpV7/function7.html>、<https://lbs.amap.com/api/android-sdk/guide/create-map/offline-map>

**天地图**
- TDT1：服务条款：<https://www.tianditu.gov.cn/about/service>（SPA，正文取自 <https://www.tianditu.gov.cn/about/static/js/main.a15946b3.js>）
- TDT2：<https://lbs.tianditu.gov.cn/server/MapService.html>；配额数字需登录 <https://console.tianditu.gov.cn/api/key> 才能查看，**未找到**；博客数字 **UNVERIFIED**：<https://blog.csdn.net/qq_40772640/article/details/130920905>
