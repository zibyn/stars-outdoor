# 仓库以 GPL-3.0 公开，CI 发版，App 直接查 GitHub Release 更新

应用内更新（§2.13，#51）要匿名查询和下载 GitHub Release。私有仓库的 Release 对没登录的请求一律返回 404，所以把 `zibyn/stars-outdoor` 以 GPL-3.0 公开，推翻 #24 里「MVP 暂不开源 App 源码」那一条。选 GPL 是为了不让别人拿去做闭源的换皮 App。App 每天查一次 `releases/latest`，SHA-256 用 GitHub 给每个资源算好的 `digest`，不用另传校验文件。

考虑过的另外两种：单独建一个只放 Release 的公开仓库，能保住不开源，但每次发版要多管一个仓库；由自己的服务端带 token 转发私有 Release，要多写服务端代码和接口契约，下载也得多经过服务器一道。

## Consequences

- 公开前要确认历史里没有密钥：签名 keystore、`keystore.properties`、`deploy/` 下的证书和 `.env` 都不进 git。
- 发版改由 CI 来做，推翻 #50「签名不进 CI」那一条：推 `v1.2.3` tag 后由 `release.yml` 构建签名 APK，再 `gh release create --generate-notes`。版本号从 tag 来，versionCode = 主 × 10000 + 次 × 100 + 修订（各段 < 100）。`scripts/build-apk.sh` 只用来本地装机测试。
- 签名 secrets 只放在 environment `release`，只有 `v*` tag 触发的 job 能用；tag 规则集限定只有维护者能建 `v*` tag。fork 来的 PR 拿不到 secrets，合进 `main` 的代码也碰不到签名密钥。
- 测试版打 `v1.3.0-beta.1` 发成 prerelease；`releases/latest` 不包含它，所以普通用户不会被推送。
- 仍不上 F-Droid。
