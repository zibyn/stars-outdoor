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

接口契约在 `server/openapi.yaml`。强制旧版客户端更新：把 `.env` 里的 `MIN_CLIENT_VERSION` 调到新的 Android versionCode，再 `docker compose up -d api`；旧版只在联网功能上看到"需要更新"，离线功能照常。

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
- API 前面加 HTTPS 反向代理（如 Caddy）之前，先让 `server/main.go` 从可信代理读 `X-Forwarded-For`：限流按来源 IP + `X-Device-Id`，否则所有客户端共用代理的一个 IP。
