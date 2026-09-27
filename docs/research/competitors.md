# 户外地图竞品调研：功能、交互与收费

> 对应 issue #2。调研日期 2026-09-27。
>
> 优先采用一手来源：官网、帮助中心、会员/定价页、应用商店页。
> 标"未核实"的是没能在一手来源确认的内容。
> 标"（二手）"的来自媒体、第三方评测或搜索摘要。
>
> 限制：
> - 部分官方页面（AllTrails、Gaia 帮助中心、komoot 支持站、CalTopo 定价页、知乎、8264）抓取时返回 403 或空页。
> - 抱怨样本偏少，只来自 App Store 能看到的评论、官方社区、Trustpilot 和媒体。
>
> 价格是调研时点的数据，会变动。

## 1. 对比总表

| | 两步路 | 六只脚 | 奥维互动地图 | AllTrails | Gaia GPS | Komoot | CalTopo | Outdooractive |
|---|---|---|---|---|---|---|---|---|
| **标注** | 记录中点"标注"，可加文字、图片、语音 | "脚印"，可加文字、图片、视频 | 点/线/面，可加图片、视频，可自定义图标；模板要 VIP | 航点只在网页端规划路线时添加 | 航点可加备注、照片，有图标库 | Highlights（带照片、评价）+ 路线航点 | Marker 可设标题、描述、颜色、图标；也有线和面 | 未核实 |
| **轨迹导入** | GPX / KML / KMZ | KML / GPX / PLT | GPX / KML / KMZ / PLT / ovobj 等；只能导出 OVKML | 只支持 GPX（≤20MB） | GPX / KML / KMZ / GeoJSON / FIT | GPX / FIT / TCX | GPX / KML 等 | GPX |
| **离线记录** | 免费 | 免费 | 免费 | 免费（二手） | 免费 | 免费 | 免费 | 免费；切后台易中断（评论） |
| **离线地图** | 多图源，可沿轨迹或多边形下载 | 免费离线下载（天地图、OCM 等） | 按县级区域或框选下载，≤18 级；卫星图要会员 | Plus 起 | Premium 起 | 按区域买，或 Premium 全球 | Mobile 档起（$20/年） | Pro 起 |
| **周边路网 / 发现** | 号称千万级 UGC 轨迹；路网图层 2023 年移除，改为热力图 | 约 1 亿条 UGC 轨迹 | 无公共库，偏工具型 | 45 万+ 条编辑整理的步道 | 自家 Gaia Topo + 规划 | 基于 OSM 路由并自动吸附，内容来自 Highlights | 无步道库，偏地形分析 | 官方机构线路 + 社区线路 |
| **队伍位置共享** | 队伍：实时位置、尾迹、聊天（文字/语音/图片）、队长指令、一键求助 | 未核实（只查到约伴） | "我的队伍"：用 ID 或二维码加入，有效期 1–31 天，有聊天 | Live Share 链接，面向安全联系人（Plus 起） | 官方不支持 | Live Tracking（Premium） | Team 账户，面向救援和团队（$500/年起） | BuddyBeacon（Pro 起） |
| **沿途天气** | 未核实 | 未核实 | 未核实 | Trail Conditions（Peak） | 预报叠加层（Premium，主要覆盖美国） | Weather on route（Premium） | NWS 图层（仅美国） | Pro+ |
| **广告** | 有开屏和第三方广告，VIP 可去；运营推广去不掉 | 未核实 | 未核实 | 免费档有 | 未核实 | 未核实 | 未核实 | 免费档有 |
| **价格** | VIP ¥18/月、¥198/年；SVIP ¥32/月 | 未核实 | VIP ¥72/年、SVIP ¥216/年 | Plus $35.99/年、Peak $79.99/年 | Premium $59.90/年 | Premium 约 €59.99/年；地图包 $3.99–29.99（一次性） | $20 / $50 / $100 每年 | Pro €29.99/年、Pro+ €59.99/年 |

## 2. 分产品要点与来源

### 两步路
- **标注**：记录轨迹时可以加标注点（文字、图片、语音），标注点显示在轨迹上。[FAQ](https://www.2bulu.com/community/gotohuatinfo.htm?id=qXWu1oO5tss%3D)
- **导入导出**：支持 GPX、KML、KMZ，其中 GPX 不带标注点。批量导出本地轨迹是 VIP 权益。[格式说明](https://www.2bulu.com/community/gotohuatinfo.htm?id=489&type=3)，[VIP 权益](https://www.2bulu.com/community/gotohuatinfo.htm?id=t1BHvWh6a9Fc+SmuJCH3zg%3D%3D&type=3)
- **离线地图**：商店描述为"5 种专业户外地图、8 种可叠加图层"。[小米商店](https://app.mi.com/details?id=com.lolaage.tbulu.tools)
- **路网**：2020 年取消路网图层，2023 年彻底移除，改用热力图；新版热力图和 3D 地图是 SVIP 权益。[博客（二手）](https://zhiqiang.org/outdoor/get-back-2bulu-roads.html)
- **队伍**：
  - 发布活动后报名的人自动组成队伍，也可以手动建队或搜索加入。
  - 能看队员位置和尾迹，聊天可以发轨迹、兴趣点和位置，有一键求助。
  - 没网时要靠"手麦"硬件对讲。
  - [队伍说明](https://www.2bulu.com/community/gotohuatinfo.htm?id=115795)
- **收费**：
  - 包季 ¥50，单月 ¥25。
  - VIP 权益：去广告、商业线路折扣、批量导出。
  - 商城、赛事等推广图官方不算广告，去不掉。
  - [App Store](https://apps.apple.com/cn/app/id646277024)
- **抱怨**：
  - 越更新越臃肿；偏离轨迹提醒不及时（来源：App Store 评论）。
  - 收费后免费功能缩水、广告变多。
  - 用户协议规定用户轨迹的知识产权归公司。
  - 以上两条出自 [8264（二手，据搜索摘要）](https://m.8264.com/thread-5608360-1.html)。

### 六只脚
- **标注、导入与离线**：
  - 官网 FAQ 写支持 KML、GPX、PLT。[FAQ](https://www.foooooot.com/faq/)
  - 可离线记录和循迹，能叠加多条参考线路、手绘规划。[App Store](https://apps.apple.com/cn/app/id543465749)，[小米商店](https://app.mi.com/details?id=com.topgether.sixfoot)
- **收费与广告**：App Store 页面没显示内购；会员权益和价格未核实。[用户协议](https://image1-oss.v.lvye.com/cert/app-sixfoot-yonghuxieyi.html)
- **抱怨**：信号好时也会突然停止记录（App Store 评论）。

### 奥维互动地图
- **定位**：偏测绘和 GIS 工具，标绘能力强（点/线/面、SHP、CAD、DXF）。SHP、CAD 要 VIP。[产品介绍](https://www.ovital.com/product/)
- **导出限制**：出于政策原因只能导出 OVKML/OVKMZ，把扩展名改掉就能在别的软件里用。[说明](https://www.ovital.com/144391/)
- **图源**：谷歌等国外图源已按《测绘法》下架；天地图、吉林一号等卫星图要会员才能下载。[下载说明](https://www.ovital.com/141574-2/)，[下架说明](https://www.ovital.com/137046-2/)
- **队伍**：用 ID 或二维码加入，也可由队长邀请。有效期 1–31 天，可延到 45 天。成员互相能看位置；聊天支持文字、语音、图片、文件。[我的队伍](https://www.ovital.com/147419/)
- **收费**：
  - 收藏上限：免费 1000 条，VIP 2 万条，SVIP 100 万条。
  - 每个账号最多绑定 5 台设备。
  - [VIP 说明](https://www.ovital.com/newvip/)
- **抱怨**：
  - 图源被下架后"越来越不好用"。[知乎（二手）](https://zhuanlan.zhihu.com/p/407710333)
  - 云端空间不足，照片被压缩（App Store 评论）。

### AllTrails
- **分档**（2025-05 推出 Peak）：
  - Base 免费，有广告。
  - Plus $35.99/年：离线地图、Live Share。
  - Peak $79.99/年：AI 智能选路（Custom Routes）、Trail Conditions 天气与路况。
  - [新闻稿](https://www.alltrails.com/press/alltrails-expands-membership-offering-with-alltrails-peak)
- **导入**：只支持 GPX，账号里至少有一条记录才会出现上传按钮。[导入说明](https://support.alltrails.com/hc/en-us/articles/37228498475028-Uploading-files-to-AllTrails)
- **Live Share**：用链接分享实时位置，面向安全联系人，不是组队功能。[Live Share 说明](https://support.alltrails.com/hc/en-us/articles/37212858771348-How-to-use-Live-Share)
- **抱怨**：
  - 离线下载要一条一条步道去下，管理笨拙。
  - 核心功能被放进付费墙。
  - 用户上传的数据过时。
  - 来源：[TechRadar（二手）](https://www.techradar.com/health-fitness/alltrails-review)

### Gaia GPS（Outside 旗下）
- **导入导出**：导入支持 GPX、KML、KMZ、GeoJSON、FIT，导出为 KML 时会带上照片。[帮助中心](https://help.gaiagps.com/hc/en-us/articles/360052763513)
- **离线地图**：只有 Premium 可以下载。[帮助中心](https://help.gaiagps.com/hc/en-us/articles/360047131513-Download-Maps-for-Offline-Use)
- **位置共享**：官方明确说不支持实时位置共享。[帮助中心](https://help.gaiagps.com/hc/en-us/articles/360048679934)
- **抱怨**：
  - 被收购后涨价，同时变卡。[官方社区](https://help.gaiagps.com/hc/en-us/community/posts/26053121241239-Exorbitant-Subscription-Increase)
  - 默认公开分享，引发隐私不满。
  - 退款难。
  - 后两条来源：[Trustpilot](https://ca.trustpilot.com/review/gaiagps.com)

### Komoot（2025-03 被 Bending Spoons 收购）
- **底图与路由**：都基于 OSM，按运动类型规划并自动吸附路网。[支持站](https://support.komoot.com/hc/en-us/articles/360022830972-Improving-the-komoot-map-using-OpenStreetMap)
- **收费**：
  - 原先的"一次性买地图区域"模式仍在卖。[product](https://www.komoot.com/product)
  - 2025-02 起，新用户同步到 Garmin 等设备必须订阅 Premium。[DC Rainmaker（二手）](https://www.dcrainmaker.com/2025/03/komoots-expanded-paywalls-trying-to-make-sense-of-it.html)
  - Premium 权益：Live Tracking、Weather on route、全球离线地图。[Premium 页](https://www.komoot.com/premium)
- **抱怨**：
  - 收购后付费墙扩大。
  - 2025 年改版后更偏重信息流。[BikeRadar（二手）](https://www.bikeradar.com/news/komoot-redesign-2025)

### CalTopo
- **定位**：专业规划工具。有 Marker、线、面对象，地形和坡度分析强，没有 UGC 步道库。[培训文档](https://training.caltopo.com/)
- **团队**：Team 账户能实时显示成员位置，还能接入 inReach 等设备。[团队追踪](https://training.caltopo.com/all_users/team-accounts/team-tracking)，[团队版](https://caltopo.com/about/teams/)
- **抱怨**：手机端按钮太小，戴手套难操作；手机上画路线远不如网页端。[官方社区](https://help.caltopo.com/hc/en-us/community/posts/28224771394459-Has-Caltopo-UI-development-stagnated)

### Outdooractive
- **分档**：
  - Basic 免费，有广告。
  - Pro €29.99/年：离线地图、官方地形图、BuddyBeacon。
  - Pro+ €59.99/年：天气、阿尔卑斯协会地图。
  - [会员页](https://www.outdooractive.com/en/membership/plans.html)
- **抱怨**：
  - 闪退、修改不保存。
  - 切到后台后记录或导航会中断。
  - 功能难找。
  - 广告多。
  - 来源：[Trustpilot](https://www.trustpilot.com/review/www.outdooractive.com)

## 3. 结论

### 商业模式
- **国内竞品都是"广告 + 会员"**，价格约 ¥70–200/年。广告是去广告付费的主要驱动。
- **海外主流是"免费档（可能有广告）+ 年订阅"**，约 $20–80/年。离线地图几乎都是第一道付费墙。
- **"无广告 + 离线地图免费"本身就是差异点。** 六只脚免费离线下载，是国内口碑的来源之一。
- **Komoot 的一次性买区域模式**对个人开发者是可参考的低打扰收费方式。
- **收购后扩大付费墙、涨价**（Komoot、Gaia）会引发大量差评，所以免费承诺一旦给出就不要收回。

### 可借鉴的交互
1. **记录中一键标注**（两步路）：不打断记录，支持照片和语音，标注点直接挂在轨迹上。
2. **临时队伍**（奥维）：用 ID 或二维码加入，自带有效期，和本项目的"临时队伍"模型一致。
3. **队伍聊天里能发位置、标注、轨迹**（两步路）。
4. **多条参考轨迹用不同颜色叠加**（六只脚），偏离路线语音提醒。
5. **规划时自动吸附路网**（Komoot、Outdooractive），导入 GPX 时可选"吸附"还是"保持原样"。
6. **离线下载支持按区域框选和沿轨迹缓冲**（两步路、奥维），并提供清晰的离线包管理。
7. **导入尽量宽容**（Gaia 支持 GPX/KML/KMZ/GeoJSON/FIT）。导出用标准 GPX/KML，并保留航点。

### 需要避免的交互
1. 越更新越臃肿，信息流和商城挤占地图（两步路、Komoot 改版）。首屏应该只有地图。
2. 离线下载只能一条一条步道下、离线包难管理（AllTrails）。
3. 切后台后记录中断，或无故停止记录（Outdooractive、六只脚）。这是可信度红线。
4. 默认公开分享，或在协议里主张轨迹归属（Gaia、两步路）。本项目轨迹默认私有，应在界面上明确告知。
5. 私有导出格式，或要求改扩展名（奥维 OVKML）。
6. 按钮太小，戴手套难操作（CalTopo）。关键按钮要大、要放在拇指够得着的区域。
7. 导出 GPX 丢失标注（两步路、Komoot）。
8. 功能要求至少有一条记录才能导入之类的隐性前置条件（AllTrails）。

### 空白与机会
- 沿途天气在国内三家都没核实到。海外的沿途天气多是付费项，而且覆盖以美国为主。国内用户的沿途天气 + 规则提醒存在空白。
- 真正的队伍（实时位置 + 聊天）只有两步路和奥维做到位；海外产品只做"分享给安全联系人"。
