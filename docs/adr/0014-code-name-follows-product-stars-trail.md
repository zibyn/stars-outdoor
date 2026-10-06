# 仓库、包名和代码都改叫 stars-trail，取代 ADR 0008 的「Outdoor 作代号」

ADR 0008 让仓库名、包名继续叫 outdoor，理由是包名上架后不能改，产品名却可能变。仓库以 GPL-3.0 公开以后（ADR 0013），仓库名就是外人认识这个项目的第一个名字，和产品名「星径 / Stars Trail」对不上反而让人困惑。首发版本 v0.1.0 发出后还没人安装，这时改包名不会让任何人没法覆盖升级，所以现在一起改：

- GitHub 仓库 `zibyn/stars-outdoor` → `zibyn/stars-trail`（旧地址由 GitHub 跳转，以后不要再建叫 `stars-outdoor` 的仓库）。
- 包名和 `namespace` `com.starsdom.outdoor` → `com.starsdom.trail`，Kotlin 包一起改。
- Gradle `rootProject.name`、Go module 名、本地目录都叫 `stars-trail`。

接受的风险：以后产品再改名，包名不会跟着改。

## Consequences

- 不改的：服务端域名 `outdoor.starsdom.com`、S3 bucket、镜像名 `stars-outdoor-server`。它们用户看不到，改了要迁移数据和部署。
- 用 0.1.0 之前的 debug 包测试过的手机，要先卸载旧包（包名不同，会装成两个 App）。
