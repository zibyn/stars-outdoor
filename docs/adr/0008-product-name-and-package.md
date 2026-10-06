# 产品名叫星径 / Stars Trail，Outdoor 只作项目代号，包名用 com.starsdom.outdoor

> 包名和仓库名的部分已被 [ADR 0014](0014-code-name-follows-product-stars-trail.md) 取代：现在都叫 stars-trail / `com.starsdom.trail`。

Stars 是作者所有产品共用的品牌，取「繁星」之意，每个产品是其中一颗星。Outdoor 是这个项目的场景代号，用来提示在解决什么场景下的问题，不作产品名。产品名按「品牌 + 产品词」起：中文叫 **星径**，用「星」字把产品串成一个系列，「径」对应轨迹和路网；英文叫 **Stars Trail**。桌面名称按系统语言切换，中文系统显示「星径」，其他语言显示「Stars Trail」。App 内的中文文案写「星径」，GPX、API 这类机器读的地方写英文名。

包名定为 `com.starsdom.outdoor`，也就是反写品牌域名 `starsdom.com`，末段放代号。末段不放产品名，是因为包名上架后就不能改，产品名以后却可能变。`namespace` 和 Kotlin 包一起改，趁还没上线（ADR 0003）改最省事。

考虑过的其他候选名（据 2026-09 网络搜索，#80）：「星野」已被国内的露营 App 和星野集团使用，「星途」是奇瑞的汽车品牌，「星迹」已有同类的卫星地图 App 在用。

## Consequences

- 代码、仓库名（`stars-outdoor`）、服务端域名（`outdoor.starsdom.com`）继续叫 outdoor；用户看到的都叫星径 / Stars Trail。
- 正式上架前要在中国商标局查第 9 类和第 42 类的商标。
- 包名迁移见 #120，启动图标见 #80。
