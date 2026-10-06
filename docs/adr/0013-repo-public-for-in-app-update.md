# 仓库公开，App 直接查 GitHub Release 更新

应用内更新（§2.13，#51）要匿名查询和下载 GitHub Release。私有仓库的 Release 对没登录的请求一律返回 404，所以把 `zibyn/stars-outdoor` 改成公开，推翻 #24 里「MVP 暂不开源 App 源码」那一条。App 每天查一次 `releases/latest`，SHA-256 用 GitHub 给每个资源算好的 `digest`，不用另传校验文件。

考虑过的另外两种：单独建一个只放 Release 的公开仓库，能保住不开源，但每次发版要多管一个仓库；由自己的服务端带 token 转发私有 Release，要多写服务端代码和接口契约，下载也得多经过服务器一道。

## Consequences

- 公开前要确认历史里没有密钥：签名 keystore、`keystore.properties`、`deploy/` 下的证书和 `.env` 都不进 git。
- 发版：在提交上打 `v1.2.3` tag，`scripts/build-apk.sh` 构建、推 tag 并 `gh release create`。版本号从 tag 来，versionCode = 主 × 10000 + 次 × 100 + 修订（各段 < 100）。
- 仍不上 F-Droid；开源许可证另定。
