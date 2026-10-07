# 调研：公开轨迹的删除与保留（同类应用做法）

调研日期：2026-09-30。对应问题：删除轨迹（#99）、撤回公开、注销账号之后，公开轨迹还留不留在 **周边路网**；"断开作者关联、匿名化后永久保留"是不是做复杂了。相关：`docs/spec/mvp.md` §2.8、`GLOSSARY.md`、`docs/research/offline-cache-and-nearby-trails.md` §2.3。

**来源说明**
- 只用一手来源：各家的服务条款、隐私政策、官方帮助中心、官方博客，以及法律的官方文本。引文保留原文，后面附简短中文释义。
- 找不到的写 **未找到**。只来自搜索摘要、没能打开原文核对的，标 **UNVERIFIED**。
- 取文方式：
  - AllTrails 隐私中心、Gaia GPS（Outside）条款、Outdooractive 条款、Garmin、两步路、GDPR 是用 headless Chrome 渲染后读到的；AllTrails、Gaia 的帮助文章走 Zendesk JSON API。
  - Wikiloc 条款和 Outdooractive 隐私政策直连返回 403，改读 Wayback 快照，快照日期标在来源里。
  - 条款会改。下文引用的是 2026-09-30 读到的版本，生效日期标在来源里。

## 1. 摘要

- **"永久、不可撤销"的授权几乎家家都有，但只写在条款里。**
  - AllTrails、Gaia GPS、Garmin 都写"irrevocable, perpetual"（不可撤销、永久）。Outdooractive 写"temporal unlimited"（时间上不受限）。Komoot 写授权"does not expire"（删除后也不终止）。Wikiloc 写的是著作权的整个保护期。
  - 两步路的"用户材料"条款几乎是 AllTrails 那段的逐句译文 [LB1]。
  - 例外是 **Strava**：授权随删除终止，只有"public routes and segments"（公开路线和路段）等几类是永久的 [S1]。
- **帮助中心讲删除时，说的都是"删掉"。** 没有一家在删除界面提示"删了也会继续保留"。
  - AllTrails："all data, hikes, photos, and reviews will become unavailable"（所有数据、徒步记录、照片和评论都将不可用） [A4]。
  - Wikiloc："permanently delete all your information"（永久删除你的全部信息） [W2]。
  - Strava：注销后最长 45 天删完 [S2]。
  - 唯一明写"保留但断开关联"的是 **Komoot**，而且只适用于注销账号：已分享的数据"no longer associated with the profile information"（不再与个人资料关联） [K1]。
- **行业里保留的是"聚合"，不是单条线。**
  - Strava、Komoot、AllTrails 的热力图，Garmin 的热度图，Outdooractive 给 B2B 伙伴的热力图，都用"aggregated / de-identified / anonymised"（聚合、去标识、匿名化）的数据 [S2][S3][K2][A1][G2][OD2]。
  - 热力图普遍每月重建，只统计近 12 个月。设为私有、删除或退出后，数据会自然滚出去，没有哪家承诺"立即清除"。
  - Strava 还要求"multiple users"（多人经过）才显示热度 [S3]。
- **逐条的"永久保留"确认，未找到任何一家这样做。** 各家的做法是：条款里写宽授权，隐私政策里写可以使用聚合或去标识数据，界面上只做可见性、隐私区、热力图退出这些开关。
- **我们的公开轨迹层是逐条的，不是聚合。** 点地图会列出"经过这里的轨迹"，还可以保存。单条线即使去掉作者、时间和首尾 200 m，也很难算《个人信息保护法》第七十三条所说的"无法识别……且不能复原"。所以"永久保留匿名几何"在法律上站不稳，在行业里也没有对应的做法。

## 2. 各应用对照

### 2.1 授权条款与删除后的效果

| 应用 | 用户授予的许可 | 删除活动或注销后，条款或帮助怎么说 |
|---|---|---|
| **Strava** | "non-exclusive, transferable, sub-licensable, royalty-free, worldwide license to … create derivative works from …"（非独占、可转让、可再许可、免版税、全球范围，可制作衍生作品） [S1] | "this license ends when your Content is deleted from Strava's systems"（内容从系统中删除时授权终止）。例外："For your public routes and segments … the license will be perpetual … and we may continue to store and use such information after your account is deleted"（公开路线和路段的授权永久有效，注销后仍可继续存储和使用） [S1]。注销后"up to 45 days"（最长 45 天）删完；"We do not have control over content you have shared … publicly with others"（对已公开分享给他人的内容无法控制） [S2] |
| **AllTrails** | "an irrevocable, perpetual, non-exclusive, fully-paid, royalty free, sublicensable, worldwide license"（不可撤销、永久、非独占、免版税、可再许可的全球许可） [A2] | 帮助中心："Once you delete your account, all data, hikes, photos, and reviews will become unavailable"（注销后所有数据都将不可用） [A4]。隐私政策只写"as long as reasonably necessary"（在合理必要的期限内保留），没有提到聚合或去标识数据的保留 [A3] |
| **Komoot** | 第 13 条：授权"does not expire on the date in which the contract … is terminated or content is removed by the user"（合同终止或用户删除内容时授权不终止），条款里明确列出"GPS tracks" [K1] | 第 18 条（注销）：照片等"deleted irreversible"（不可恢复地删除），但"content shared on the platform … for both, other users and made publicly available data … are no longer associated with the profile information"（已分享给他人或已公开的数据保留，但不再关联个人资料） [K1]。隐私政策：注销时个人资料"completely and permanently deleted"（完全、永久删除），备份另留 30 天 [K2] |
| **Wikiloc** | "non-exclusive licence … with the right to sub-license … during the entire term recognised to them under the applicable regulations"（非独占、可再许可，期限为法律承认的整个保护期） [W1] | 帮助中心："if you delete your Wikiloc account, you will permanently delete all your information and personal data from our system"（注销即永久删除全部信息和个人数据） [W2]。条款里没有保留条款，**未找到** |
| **Gaia GPS**（Outside） | "irrevocable, perpetual, world-wide, non-exclusive, sub-licensable, royalty-free, and transferable right and license to … create derivative works of … User Generated Content"（不可撤销、永久……可制作衍生作品）；另外向"each user of the Services"（每位用户）授予永久、不可撤销的访问许可 [GA1] | 删除后怎么处理 **未找到**（隐私政策页 404） |
| **Outdooractive** | §7："non-exclusive, textual, spatial and temporal unlimited right to use the works (e.g. … tour geometries)"（非独占，内容、地域、时间均不受限的使用权，明确包括"tour geometries"线路几何） [OD1] | **未找到** |
| **Garmin** | garmin.com 的使用条款："royalty-free, perpetual, irrevocable, non-exclusive right and license … for the full term of any copyright"（免版税、永久、不可撤销，期限为著作权的全部保护期） [G3]。这条管的是网站上的"User Submissions"；Garmin Connect 活动专属的条款 **未找到** | 隐私政策："as long as your Garmin account is considered to be active"（在账户被视为活跃期间保留） [G1] |
| **两步路** | "一项不可撤销、永久、非独占、全额付费、免版税、可再许可的全球许可……并制作此类用户材料的衍生作品"（与 AllTrails 条款几乎逐句对应） [LB1] | "注销帐号后，我们将删除有关您的相关信息或进行匿名化处理，但法律法规另有规定的除外" [LB1][LB2] |
| **六只脚** | "在法律规定的保护期限内用户免费授予六只脚获得全球非排他的许可使用权利"，另有一句"如用户希望六只脚停止使用或停止以某种方式使用其内容，用户可以向六只脚发出书面通知" [SJ1] | "六只脚将删除其在六只脚平台上的个人信息"；对外共享去标识化信息"无需另行向您通知" [SJ1][SJ2] |

### 2.2 聚合层（热力图、热度路线）

| 应用 | 用什么数据 | 删除、私有、退出之后 |
|---|---|---|
| **Strava** 全球热力图 / Metro | "aggregated, de-identified activity data"（聚合、去标识的活动数据）；"We do not show 'heat' in an area until multiple users have uploaded activities traversing that area"（多人经过才显示热度）；排除非"Everyone"的活动、被隐藏的起终点、已退出的用户 [S3] | "updated monthly"（每月更新），只统计"the last year"（近一年） [S3]。隐私政策："We may also share aggregated or deidentified information"（可能共享聚合或去标识信息），Metro 也用它 [S2]。删除后已进聚合的部分怎么处理 **未找到** |
| **Komoot** 全球热力图 | "aggregated, anonymized visualizations … not linked to individual user accounts"（聚合、匿名化的可视化，不关联个人账户）；法律依据是 GDPR 6(1)(f) 正当利益 [K2] | 可以在设置里退出；"Only you"（仅自己可见）的活动永远不进入。个人热力图写了"If you delete an activity … it will also be removed"（删除活动会从中移除）；全球热力图没有这样写 [K2] |
| **AllTrails** 社区热力图 | 只用公开记录，近 12 个月，每月刷新 [A1] | "If you wish to remove your recordings from the heatmap, you can change the activity privacy setting to 'private'"（改为私有即可移出），"changes may not be immediate"（不会立即生效） [A1] |
| **Garmin** 热度图 | "generated in major cities based on aggregated user data"（基于聚合的用户数据，在大城市生成） [G2]；隐私政策说聚合后可以"publishing statistics and trends"（发布统计和趋势），依据是正当利益 [G1] | **未找到** |
| **Outdooractive** | 给 B2B 伙伴的区域热力图"completely anonymised"（完全匿名化） [OD2] | **未找到** |
| **Wikiloc / Gaia / 两步路 / 六只脚** | 公开的聚合热力图 **未找到** | — |

### 2.3 隐私工具与默认可见性

| 应用 | 默认可见性 | 起终点保护 | 退出聚合 |
|---|---|---|---|
| **Strava** | 用户自设默认值 [S4] | 上传第一条活动后，"the first and last 200 meters of your future activity maps will be hidden by default"（以后的活动默认隐藏首尾 200 米）；也可以按地址隐藏（最大 1 英里）。原文提醒："does not mean it would be impossible for someone to deduce a hidden location"（并不意味着别人无法推断出被隐藏的位置） [S5] | "Product Improvements"（产品改进）开关 [S6] |
| **AllTrails** | 注册后内容"Public by default"（默认公开） [A3] | **未找到** | 改为私有即可移出 [A1] |
| **Komoot** | — | 隐私区是随机多边形（**UNVERIFIED**：帮助页 403，只看到搜索摘要 [K3]）；热力图砍掉首尾数百米（见前一篇调研 [K4]） | 设置里取消勾选 [K2] |
| **Gaia GPS** | "any new tracks will default to public"（新轨迹默认公开） [GA2] | **未找到** | **未找到** |
| **Garmin** | — | 隐私区内起止的公开活动，对他人显示为从隐私区边缘开始 [G4] | 产品改进需同意（EEA） [G1]；热度图能否单独退出 **未找到** |
| **我们** | 默认私有，逐条公开 | 公开时隐藏首尾各 200 m（与 Strava 默认值相同） | 不适用：没有聚合层 |

## 3. 法律框架

- **GDPR 鉴于条款第 26 条**：原文"The principles of data protection should therefore not apply to anonymous information … This Regulation does not therefore concern the processing of such anonymous information, including for statistical or research purposes"（数据保护原则不适用于匿名信息，本条例不涉及对这类信息的处理，包括统计或研究用途）。同一条也说，经过假名化、借助额外信息仍能关联到个人的数据仍属个人数据；判断能否识别时，要考虑"singling out"（单独挑出某人）等"all the means reasonably likely to be used"（一切合理可能使用的手段） [L1]。
- **《个人信息保护法》**（2021 年 8 月 20 日通过） [L2]：
  - 第四条：个人信息"不包括匿名化处理后的信息"。
  - 第二十八条：敏感个人信息包括"行踪轨迹"；处理敏感个人信息需要"特定的目的和充分的必要性"。第二十九条要求取得"单独同意"。
  - 第四十七条：以下情形应当主动删除，包括"个人撤回同意"和"处理目的已实现……或者为实现处理目的不再必要"。
  - 第七十三条：去标识化是"在不借助额外信息的情况下无法识别特定自然人"；匿名化是"无法识别特定自然人 **且不能复原**"。
- **推论**（我们的判断，不是法条原文）：
  - 删掉作者和时间戳、首尾各裁 200 m，更接近"去标识化"：线的形状本身就是"额外信息"的钥匙。它曾以作者名义公开过，别人可能保存过带作者的副本；一条独特的线，也可能被本人的熟人认出来。
  - 去标识化的信息仍然是个人信息，撤回同意（撤回公开、删除、注销）就触发第四十七条。
  - 聚合、多人叠加、到阈值才显示的热力图，才比较站得住"匿名化"。Strava 的"multiple users"门槛就是为此设的 [S3]。
- **Strava 2018 年的教训**：聚合数据也会泄露。首席执行官在公开信里承认，军人等用户"shared their location in areas without other activity density and, in doing so, inadvertently increased awareness of sensitive locations"（在没有其他活动密度的地区分享了位置，无意中暴露了敏感地点） [S7]。人少的山区与此同理：一条线就是整个区域唯一的"热度"。

## 4. 对 stars-outdoor 的结论

**行业常态**
1. 删除之后还保留的，是 **聚合或去标识的统计层**（热力图、Metro、热度路线），而且只在隐私政策里笼统写一句。聚合层按月重建、只看近 12 个月，删除和私有的数据自然滚出去。
2. **没有一家做逐条的"永久保留"确认。** 永久授权只写在条款里；删除界面上说的都是"删掉"。
3. 保留 **单条** 用户轨迹的只有两种情况，都很窄：Strava 的"公开路线、路段"（授权条款里写明例外） [S1]，以及 Komoot 注销后"已分享给他人的内容"与资料断开关联 [K1]。二者都是条款层面的保留，而且不是国内语境下的敏感个人信息场景。
4. 所有人都不追回别人已经保存的副本 [S2]。

**我们的方案"断开关联、永久保留匿名几何"的问题**
- 比行业多做了一件事：多一套无主数据，多一个确认弹窗，删除路径也要分叉。
- 法律上反而更弱：单条逐条可点、可保存的线，难算第七十三条的"匿名化"。这样处理行踪轨迹（第二十八条）并在撤回后继续保留，与第四十七条冲突。
- 产品价值也有限：周边路网已经有 OSM 路径和徒步线路兜底。少一条已删除的公开轨迹，影响的是"经过这里的轨迹"列表里的一项，不是路网本身。

**建议：最简单、也站得住的设计**
1. **删除轨迹、撤回公开、注销账号，都把这条线从周边路网（PostGIS 公开轨迹）一起删掉。** 不做无主副本，也不加逐条的"永久保留"确认。
2. **滞后照旧**：离线快照和地图缓存里的线，保留到联网刷新或清除为止。这已经写在 `mvp.md` §2.8，与行业"按周期重建"是同一口径。
3. **他人已保存到"我的轨迹"的副本不追**，与各家一致。**公开时** 提示一句即可，例如"公开后别人可以保存副本；撤回或删除后，已保存的副本无法收回"。这对应 Strava 隐私政策里"We do not have control over content you have shared … publicly"的说法 [S2]。
4. **用户协议写两句**：一是公开轨迹的使用许可在删除或撤回时终止（与 Strava 相同，比 AllTrails 或两步路式的"永久不可撤销"更干净）；二是已被他人保存的副本除外。不要抄"不可撤销、永久"的写法。
5. **以后真想要一张删不掉的路网**，就按热力图的做法做 **聚合层**：多人叠加达到阈值才显示，按月重建，只统计近 12 个月，可以退出。这时它才站得住"匿名化"，也符合行业常态。现在没有用户量，先不做。

## 5. 来源

**Strava**
- S1：服务条款 §6 License：<https://www.strava.com/legal/terms>
- S2：隐私政策（Share Insights、Retention of Information、Restrict or Delete 各节）：<https://www.strava.com/legal/privacy>
- S3：<https://support.strava.com/hc/en-us/articles/216918877-Strava-Heatmaps>（跳转到"What Are the Global Heatmap and Strava Metro?"）
- S4：<https://support.strava.com/en-us/articles/15401987-activity-privacy-controls>
- S5：<https://support.strava.com/en-us/articles/15402012-edit-map-visibility>
- S6：<https://support.strava.com/hc/en-us/articles/360015677851>（Product Improvements）
- S7：James Quarles，"A Letter to the Strava Community"，2018-01-29：<https://web.archive.org/web/2018/https://blog.strava.com/press/a-letter-to-the-strava-community/>（原链接已失效，读的是 Wayback 快照）

**AllTrails**
- A1：<https://support.alltrails.com/hc/en-us/articles/36898308536852-Community-Heatmaps>（更新于 2026-08-11）
- A2：服务条款 §6 User Material，生效日期 2025-12-17：<https://www.alltrails.com/terms>
- A3：隐私政策，生效日期 2026-09-24（Privacy Settings Default、Data Retention 两节）：<https://privacy.alltrails.com/policies/en-US>
- A4：<https://support.alltrails.com/hc/en-us/articles/37215997478292-How-to-delete-your-AllTrails-account>

**Komoot**
- K1：服务条款第 13、16、18 条：<https://www.komoot.com/terms-of-service>
- K2：隐私政策（Generation of heatmaps、Deletion of your data 两节）：<https://www.komoot.com/privacy>
- K3：**UNVERIFIED**（403）：<https://support.komoot.com/hc/en-us/articles/360046595312-Privacy-Zones>
- K4：见 `docs/research/offline-cache-and-nearby-trails.md` 的 [K4]

**Wikiloc**
- W1：使用条款（"Intellectual property"一节）。直连 403，读的是 Wayback 2026 年快照：<https://www.wikiloc.com/wikiloc/terms_en.html>
- W2：<https://help.wikiloc.com/article/270-delete-my-user-account-wikiloc-cancel>

**Gaia GPS**
- GA1：Outside Inc. 使用条款（Gaia GPS 的条款指向它）：<https://www.gaiagps.com/company/terms_of_use/>；指向关系见 <https://help.gaiagps.com/hc/en-us/articles/360004146253>
- GA2：<https://help.gaiagps.com/hc/en-us/articles/4403979404311-Privacy-and-Data-controls>（更新于 2025-02-10）

**Outdooractive**
- OD1：一般条款 §7：<https://www.outdooractive.com/en/terms-and-conditions.html>
- OD2：隐私政策。直连 403，读的是 Wayback 2026 年快照：<https://www.outdooractive.com/en/privacy.html>

**Garmin**
- G1：Garmin Connect 隐私政策，最后更新 2026-01-09：<https://www.garmin.com/en-US/privacy/connect/policy/>
- G2：<https://support.garmin.com/en-US/?faq=n2UzfNkYOt3iAbXqgl03W7>（Popularity Heatmap）
- G3：garmin.com 使用条款（User Submissions）：<https://www.garmin.com/en-US/legal/terms-of-use/>
- G4：<https://support.garmin.com/en-US/?faq=B9dlXYxQIr97DQwho5TBR7>（Privacy Zones）

**两步路**
- LB1：用户注册协议，2025-07-03 生效（第四章"账号注销"、第八章"知识产权"第 6 条）：<https://www.2bulu.com/about/terms_use.htm>
- LB2：隐私权政策（"账号注销"一节）：<https://www.2bulu.com/about/privacy.htm>

**六只脚**
- SJ1：用户协议（第四章"知识产权"、第五章"用户授权及隐私保护"）：<https://image1-oss.v.lvye.com/cert/app-sixfoot-yonghuxieyi.html>
- SJ2：隐私政策，2019-08-12 生效：<https://image1-oss.v.lvye.com/cert/app-sixfoot-yinsizhengce.html>（与 SJ1 同一托管路径，按命名规律找到）

**法律**
- L1：GDPR（EU 2016/679）鉴于条款第 26 条：<https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679>
- L2：《中华人民共和国个人信息保护法》，中国人大网：<http://www.npc.gov.cn/npc/c2/c30834/202108/t20210820_313088.html>
