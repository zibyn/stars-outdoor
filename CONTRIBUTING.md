# 参与开发

欢迎提 issue 和 PR。改动较大时，先开 issue 说清楚要解决什么问题，免得白做。

## 构建与测试

见 [docs/development.md](docs/development.md)。提 PR 前至少跑一遍：

```sh
scripts/fetch-glyphs.sh                           # 只需一次
./gradlew :shared:testAndroidHostTest :app:testDebugUnitTest :app:assembleDebug
(cd server && go generate ./... && go test ./...)  # 改了服务端时
```

CI（`.github/workflows/`）会在 PR 上跑同样的检查，不需要任何密钥。debug 构建默认连正式服务端，连自己的服务端用 `-PstarsApiUrl=http://10.0.2.2:8080`。

## 约定

- **词汇**：领域用语以 [GLOSSARY.md](GLOSSARY.md) 为准；做过的决定在 [docs/adr/](docs/adr/)，推翻时新写一条 ADR。
- **接口**：先改 `server/openapi.yaml`，`server/api/` 由它生成（ADR 0004）。
- **文案**：App 里的文字遵守 [docs/spec/ux-v3-copy.md](docs/spec/ux-v3-copy.md) 的规则（字数上限、出错句式等）。
- **PR 标题**：写给用户看。发版说明由合并的 PR 标题自动生成。

## 发版（维护者）

在 `main` 上打 tag 并推送，`release.yml` 会构建签名 APK 并发布 GitHub Release。版本号从 tag 来（ADR 0013）：

```sh
git tag v1.2.3 && git push origin v1.2.3       # 正式版，App 一天内提示更新
git tag v1.3.0-beta.1 && git push origin v1.3.0-beta.1   # 测试版，发成 prerelease，不提示
```

## 许可

本项目以 [GPL-3.0](LICENSE) 发布，你提交的代码也按 GPL-3.0 授权。地图数据另有各自的许可，见 [README](README.md#数据来源)。
