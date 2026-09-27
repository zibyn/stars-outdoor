# 调研：大陆可达、免备案的托管后端

> 对应 issue #7。调研日期 2026-09-27。结论基于各服务商官方文档 / 价格页 / 公告，每条结论后附来源编号。标注「未核实」的是推断，或没能直接读到一手来源的内容。**大陆网络可达性没有任何服务商给出官方承诺**，所以下文的「可达」都要靠大陆实测确认（见最后的「下一步」）。

## 结论（TL;DR）

- **手机号验证码：用阿里云「号码认证服务 · 短信认证」**。个人实名账号就能用，签名和模板都由阿里云系统提供，不需要企业资质、ICP 或上架。¥0.06/条起，只支持 +86 号码 [A1][A2]。自建签名这条路已经走不通：阿里云、腾讯云都不再受理个人自用短信资质（腾讯自 2025-09-18 起）[A3][T1]，阿里云也不再接受「已上线 APP」作签名来源 [A4]。Twilio 发中国号码是 best-effort，不保证送达 [W1]。
- **后端主体：推荐在中国香港地域放一台轻量服务器（阿里云 / 腾讯云，约 ¥24–30/月，未核实），运行一个小服务**（PocketBase 或自写 Go/Kotlin 服务 + WebSocket）。香港地域明确不需要 ICP 备案 [T2]，回大陆走精品线路 [A5]（营销页，未核实）。代价是自己要维护一台机器。
- **全托管的备选：Supabase（新加坡 / 东京）**。能力覆盖最全（Postgres、Auth、Realtime、Storage），手机 OTP 可以用 Send SMS Hook 转到阿里云短信认证 [S1]。但它没有香港区 [S3]，大陆能否直连要实测；免费项目闲置 1 周会被暂停 [S2]，所以实际要付 Pro $25/月。
- **推送（大陆 Android 没有 FCM）：没有上架国内商店的 App，拿不到稳定的离线厂商推送。** 小米要求上架（2025-06-09 起未上架的应用会被关停推送）[X1]，vivo 未上架时只能发测试推送 [V1]，OPPO 未上架时只有测试额度（二手来源，未核实）[J1]。**对策**：队伍活跃期间，App 本来就要用前台服务上报实时位置，顺便保持 WebSocket 长连接，在本地弹通知；有 GMS 的设备另外接 FCM。App 被杀掉之后收不到推送，这一点接受。
- **图片存储**：放香港 VPS 本地磁盘或阿里云 OSS 香港，都不需要备案。Cloudflare R2 有 10 GB 免费、出流量免费 [C3]，但大陆可达性未知。
- **不推荐**：Tencent IM / 融云 / 环信这类 IM PaaS。以腾讯 IM 为例，免费版最多 100 个用户，专业版 ¥1499/月起 [T3]，而且离线推送一样依赖厂商通道，绕不开上架问题。Cloudflare Workers / Durable Objects 价格很好（免费档每天 10 万次请求 [C2]），但官方的大陆加速（China Network）必须有 ICP [C1]，不走它的话大陆访问会被路由到境外节点 [C1]，可达性和延迟都没有保证。Firebase 在大陆不可用，不考虑。

## 对比表

| 方案 | 大陆可达 | 免备案 | 手机 OTP | 实时（位置 / 对话） | 图片 | 推送 | 固定成本 | 锁定风险 |
|---|---|---|---|---|---|---|---|---|
| 香港轻量服务器 + PocketBase / 自写服务 | 较好（精品线路，营销页说法 [A5]，未核实） | 是 [T2] | 自己接阿里云短信认证 API [A1] | WebSocket / SSE，自己实现 | 本地盘或 OSS 香港 | 前台服务长连接 + FCM | ~¥24–30/月（未核实） | 低（SQLite / 开源，可以迁走） |
| Supabase 新加坡 / 东京 | 未实测 | 是（境外） | Send SMS Hook → 阿里云 [S1] | Realtime，免费档 200 并发 / 200 万条每月 [S2] | Storage，免费 1 GB [S2] | 同左，需要自己接 | 免费档闲置 1 周暂停；Pro $25/月 [S2] | 中（Postgres 可导出，Auth / Realtime 需要重写） |
| Cloudflare Workers + DO + R2 | 未实测；没有 ICP 就不走境内节点 [C1] | 是 | 自己在 Worker 里调阿里云 | DO + WebSocket Hibernation [C2] | R2 免费 10 GB、零出流量费 [C3] | 同左 | 免费档可起步 | 中高（DO 编程模型专有） |
| PocketBase 自托管（不管放哪里） | 看宿主 | 看宿主 | 只内置邮件 OTP，手机号需要自己用 hook 实现 [P2] | realtime subscriptions [P1] | 内置文件 [P1] | 无 | 看宿主 | 低；但 v0.x，官方说明**尚不建议用于生产关键场景** [P1] |
| 腾讯云 IM（Chat） | 是 | 是（SaaS） | 不含 | IM 本身 | 含 | 厂商通道插件，受上架限制 | 免费版 ≤100 用户；专业版 ¥1499/月起 [T3] | 高 |
| Appwrite Cloud | 未实测 | 是 | 内置 SMS provider 以海外为主（未核实） | Realtime | Storage | Messaging | — | 中 |

## 按需求逐项分析

### 1. 手机号验证码（+86）

- **监管背景**：国内短信的签名必须在运营商那里做实名报备，报备主体必须是企事业单位。腾讯云从 2025-09-18 起不再受理新的「个人认证自用资质」，已有的个人资质也不能再关联新签名 [T1]。阿里云也一样：个人认证账号不能再新申请自用资质，要么改成企业认证，要么申请「他用资质」（也需要企业授权）[A3]。签名来源为「已上线 APP」的方式，运营商已经不再支持 [A4]。所以个人开发者**没办法拿到带自己 App 名的签名**。
- **可行方案：阿里云号码认证服务（PNVS）· 短信认证** [A1]
  - 个人实名账号即可开通，使用「系统赠送签名 + 标准验证码模板」，不能自定义签名和模板。
  - 只支持中国大陆号码（86）。
  - API：`SendSmsVerifyCode` / `CheckSmsVerifyCode`；也可以自带验证码，由自己的服务端校验 [A1]。这意味着它能接在 Supabase Send SMS Hook 后面：Supabase 生成验证码，Hook 调阿里云下发。
  - 价格：按量 ¥0.06/次起，阶梯降到 ¥0.04；套餐 1000 次 ¥54，12 个月有效，发送失败不计费 [A2][A1]。
- **Twilio**：2021-06-20 之后发往中国的短信只做 "limited support"，发件号会被改写，**不保证送达**，也不再受理模板报备 [W1]。不作为主通道。
- 海外号码暂不支持。MVP 用户以国内为主，需要时再加 Twilio 等作为海外通道。

### 2. 可达性与备案

- 域名 / App 指向**境外（含中国香港）服务器，不需要 ICP 备案**；指向境内服务器才需要 [T2]。
- Cloudflare China Network 必须有 ICP [C1]。没有它的话，大陆访客会被路由到境外最近的数据中心 [C1]。官方没有给出性能承诺。
- Supabase 没有香港区域，亚太离大陆最近的是新加坡、东京、首尔 [S3]。
- 阿里云香港地域宣传「BGP 多线精品、CN2、直连中国内地」[A5]，但这是营销页，未核实。
- **以上都不是「保证能访问」**。GFW 封锁不会有官方文档，**必须在电信 / 联通 / 移动三网的 4G/5G 下实测**。

### 3. 队伍实时位置 + 队伍对话

- 规模很小：一支临时队伍通常 2–20 人，位置 5–30 秒上报一次。WebSocket 广播对任何方案都算轻负载。
- Supabase Realtime 免费档 200 并发、200 万条每月；Pro 500 并发、500 万条每月 [S2]。按 10 人队伍、15 秒一次估算，每人每小时 240 条，扇出后约 2.4 万条 / 队 / 小时。免费档的消息额度大约够几十个队伍小时（估算）。
- 自建：一个进程加内存里的 room map 就够用（单机单进程即可，将来多实例再换 Redis pub/sub）。

### 4. 推送（大陆 Android 无 FCM）

- 厂商通道是大陆 Android 唯一可靠的离线推送。极光、个推、腾讯 IM 等第三方推送走的也是厂商通道，所以都受同样的上架约束。
  - **小米**：技术服务协议要求应用通过小米应用商店审核并上架；未上架的应用需在 2025-06-09 前完成上架，否则会被调整或关闭推送权限。唯一的例外是「企业内部分发」下的非公开上架，需要企业资质 [X1]。
  - **vivo**：未上架时推送权限为「受限」，只能发测试推送；上架后才自动转为正式推送 [V1]。vivo 商店不接受个人开发者 [V2]。
  - **OPPO**：未上架时可以申请测试权限，每天约 1000 条（来自极光文档搜索摘要，一手页面访问失败，未核实）[J1]。
  - **华为 / 荣耀**：需要在 AppGallery Connect 开通推送，是否要求上架没有查到明确的一手说法（未核实）[H1]。
  - 极光免费版能否使用厂商通道：搜索摘要称只对 VIP 开放，但 FAQ 原文无法访问，未核实 [J2]。
- **结论**：在「不上架国内商店」这个约束下，离线推送基本拿不到。建议的产品取舍：
  1. 队伍活跃期间（加入队伍、开启位置共享）本来就需要前台服务和常驻通知来持续定位。同一个服务里维持 WebSocket，收到对话消息就弹本地通知，效果等同于推送。
  2. 队伍是临时的。离队或者 App 被系统杀掉之后，不再推送；重新打开 App 时拉取未读消息。
  3. 有 GMS 的设备（海外用户、部分国内机型）可以额外接 FCM，成本很低。

### 5. 图片存储 & 轨迹 / 标注同步

- 图片：香港 VPS 本地磁盘最简单；量大后可迁到阿里云 OSS 香港（境外地域，不需要备案 [T2]）。R2 免费 10 GB、出流量免费 [C3]，但可达性未测。
- 轨迹 / 标注同步：普通的 CRUD 加时间戳。Supabase（Postgres）和 PocketBase（SQLite）都能直接满足；多设备冲突策略属于 #1 里「离线记录与同步」待定项，不在本 ticket 范围内。

## 推荐组合

1. **首选（最可控）**：阿里云或腾讯云香港轻量服务器，跑单体服务（PocketBase 用 Go 扩展手机 OTP，或者自写一个小服务），外加阿里云短信认证、本地盘存图片、前台服务 WebSocket + FCM。固定成本约 ¥30/月 + 短信 ¥0.06/条。
2. **想要全托管**：Supabase Pro（新加坡），配 Send SMS Hook → 阿里云短信认证，约 $25/月。**前提是大陆三网实测可达**。
3. 两个方案里，短信和推送的做法完全一样。区别只在「自己维护一台机器」和「多付约 ¥150/月、承担可达性风险」之间怎么选。

## 下一步（建议）

- 在大陆三网的手机网络下实测以下端点的连通性和延迟：`*.supabase.co`（新加坡 / 东京）、`*.workers.dev` 和自定义域名的 Worker、`r2.dev`、一台香港轻量服务器。结果决定选方案 1 还是方案 2。
- 注册阿里云个人实名账号，开通短信认证，给自己手机发一条，确认系统签名的实际显示效果。

## 来源

- [A1] 阿里云 · 短信认证服务接入指南（个人开发者免资质）https://help.aliyun.com/zh/pnvs/use-cases/sms-verify-for-individual-developers
- [A2] 阿里云 · 号码认证服务定价 https://help.aliyun.com/zh/pnvs/product-overview/product-pricing
- [A3] 阿里云 · 关于短信服务不支持申请个人自用资质的公告 https://help.aliyun.com/zh/sms/product-overview/announcement-on-sms-not-supporting-application-for-personal-use-qualification
- [A4] 阿里云 · 申请短信签名（「已上线APP」来源不再支持）https://help.aliyun.com/zh/sms/user-guide/create-signatures
- [A5] 阿里云 · 香港服务器营销页（CN2 / 精品线路说法）https://www.aliyun.com/sswb/935800.html
- [T1] 腾讯云 · 关于调整国内短信服务个人认证资质申请规则的公告 https://cloud.tencent.com/announce/detail/2127
- [T2] 腾讯云 · ICP 备案「是否需要备案」https://cloud.tencent.com/document/product/243/19630
- [T3] 腾讯云 · 即时通信 IM 基础服务计费说明 https://cloud.tencent.com/document/product/269/81908
- [W1] Twilio · China SMS Guidelines https://www.twilio.com/en-us/guidelines/cn/sms ；China SMS Template Pre-approval Requests https://support.twilio.com/hc/en-us/articles/360016612253
- [S1] Supabase · Send SMS Hook https://supabase.com/docs/guides/auth/auth-hooks/send-sms-hook
- [S2] Supabase · Pricing https://supabase.com/pricing
- [S3] Supabase · Regions https://supabase.com/docs/guides/platform/regions
- [C1] Cloudflare China Network · FAQ / ICP https://developers.cloudflare.com/china-network/faq/ ，https://developers.cloudflare.com/china-network/concepts/icp/
- [C2] Cloudflare · Durable Objects Pricing https://developers.cloudflare.com/durable-objects/platform/pricing/
- [C3] Cloudflare · R2 Pricing https://developers.cloudflare.com/r2/pricing/
- [P1] PocketBase · Introduction https://pocketbase.io/docs/
- [P2] PocketBase · Authentication https://pocketbase.io/docs/authentication/
- [X1] 小米澎湃OS开发者平台 · 关于未上架应用推送权限关停的通知 https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2057
- [V1] vivo 开放平台 · 推送使用指南 https://dev.vivo.com.cn/documentCenter/doc/541 （据搜索摘要）
- [V2] vivo 开放平台 · 应用审核规范 https://dev.vivo.com.cn/documentCenter/doc/12 （据搜索摘要）
- [H1] 华为 Push Kit https://developer.huawei.com/consumer/cn/hms/huawei-pushkit/ （未找到「是否需上架」的明确表述）
- [J1] 极光 · 厂商通道参数申请指南 https://docs.jiguang.cn/jpush/client/Android/android_3rd_param （直连失败，据搜索摘要，未核实）
- [J2] 极光 · 产品 FAQ https://docs.jiguang.cn/jpush/faq/prod_faq （直连失败，据搜索摘要，未核实）
