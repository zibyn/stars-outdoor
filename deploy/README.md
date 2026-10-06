# 部署

内网测试服务器（ADR 0003）和日后的香港 VPS 用同一套：`compose.yaml` 跑 API（`server/`）、PostGIS 和 RustFS。

## 启动

```sh
cd deploy
cp .env.example .env         # 填 POSTGRES_PASSWORD、S3_*、第三方凭据；值里有空格就加引号（脚本会 source 它）
mkdir -p images && sudo chown 65532 images  # 队伍对话的图片；API 以 nonroot（65532）运行
docker compose up -d --build # 改了 server/ 之后重跑同一条
curl localhost:8080/v1/health   # {"postgis":"3.5.x","status":"ok"}
curl localhost:8080/v1/version  # {"api":"v1","minClientVersion":1}
```

## 发布到内网服务器（192.168.50.51）

```sh
scripts/deploy.sh [版本号]   # 不填用 git 短哈希
```

构建镜像并推送到 `registry.starsdom.com:9443/zibyn/stars-outdoor-server`，打上版本号和 `latest` 两个标签。然后 SSH 到 `root@192.168.50.51`，在 `/opt/outdoor` 拉取该版本，重启 `db` 和 `api`，并检查 `/v1/health`。服务器用外部对象存储（`S3_ENDPOINT`），不跑 RustFS。

首次运行时，脚本会逐步引导你：登录镜像仓库、配置免密 SSH、复制 `.env` 和 `certs/`（服务器上是一个空数据库）。之后这些步骤会自动跳过。当前版本记在服务器 `.env` 的 `API_TAG`：回滚时把它改回旧版本号，再执行 `docker compose up -d api`。

接口契约在 `server/openapi.yaml`。强制旧版客户端更新：把 `.env` 里的 `MIN_CLIENT_VERSION` 调到新的 Android versionCode（从发版 tag 算：v1.2.3 → 10203，ADR 0013），再 `docker compose up -d api`；旧版只在联网功能上看到"需要更新"，离线功能照常。

### 反向代理

`outdoor.starsdom.com:9443` 是网关上的 nginx，转发到 `192.168.50.51:8080`。队伍的实时更新走 WebSocket（`/v1/teams/{id}/live`），代理必须转发 Upgrade，否则 App 只在重开时拉一次（#136）：

```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }   # http 块

proxy_http_version 1.1;                                               # API 的 location
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $connection_upgrade;
proxy_read_timeout 3600s;  # 服务端每分钟 ping 一次，nginx 默认 60 s 会断开
```

测试号：`.env` 的 `TEST_LOGINS`（只在内网服务器配），配合 `scripts/fake-teammate.py` 在模拟器上测队伍（#135）。

## 离线包

`scripts/upload-data.sh` 把季度数据传到 bucket 根目录；API 按请求范围裁出小包，缓存在 `packages/<数据版本>/` 下，数据版本随源文件 ETag 变化，所以重新上传后旧包自然失效，客户端显示"可更新"。旧版本的包不会自动删除：给 bucket 加一条生命周期规则，`packages/` 前缀 90 天过期。

```sh
curl -XPOST localhost:8080/v1/offline/packages -d '{"bbox":[107.7,33.9,107.85,34.0]}'  # 返回各文件的签名下载地址
```

## 备份

`scripts/backup-db.sh` 每天 `pg_dump` 到 `deploy/backups/`（本机保留 14 天），再把它和 `deploy/images/`（队伍对话的图片）rsync 到 `BACKUP_DEST`（服务器以外的机器，需要免密 SSH）。结束行程 180 天后 API 删除原图、只留缩略图；异地副本不跟着删。在服务器上加 cron：

```cron
30 3 * * * /path/to/repo/scripts/backup-db.sh >> /var/log/stars-backup.log 2>&1
```

恢复：

```sh
docker compose exec -T db pg_restore -U stars -d stars --clean --if-exists < backups/stars-YYYY-MM-DD.dump
```

## 上公网 VPS 时

- `compose.yaml` 把数据库、RustFS 控制台开在所有网卡上，只适合内网：改成只绑 `127.0.0.1` 或删掉 `ports`。
- 加上全局限流（ADR 0003 上线清单）：按来源 IP + `X-Device-Id` 限每分钟请求数，瓦片另算一份更宽的。现在只有登录限流和天气的每日格数限额，搜索代理和天地图瓦片代理的第三方 Key 日配额没有任何保护。
- API 前面加 HTTPS 反向代理（如 Caddy）之前，先让 `server/main.go` 从可信代理读 `X-Forwarded-For`：登录限流、天气限额和以后的全局限流都按来源 IP 算，否则所有客户端共用代理的一个 IP。
