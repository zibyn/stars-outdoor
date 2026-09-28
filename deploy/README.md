# 部署

内网测试服务器（ADR 0003）和日后的香港 VPS 用同一套：`compose.yaml` 跑 API（`server/`）、PostGIS 和 RustFS。

## 启动

```sh
cd deploy
cp .env.example .env         # 填 POSTGRES_PASSWORD、S3_*、第三方凭据；值里有空格就加引号（脚本会 source 它）
docker compose up -d --build # 改了 server/ 之后重跑同一条
curl localhost:8080/v1/health   # {"postgis":"3.5.x","status":"ok"}
curl localhost:8080/v1/version  # {"api":"v1","minClientVersion":1}
```

接口契约在 `server/openapi.yaml`。强制旧版客户端更新：把 `.env` 里的 `MIN_CLIENT_VERSION` 调到新的 Android versionCode，再 `docker compose up -d api`；旧版只在联网功能上看到"需要更新"，离线功能照常。

## 备份

`scripts/backup-db.sh` 每天 `pg_dump` 到 `deploy/backups/`（本机保留 14 天），再 rsync 到 `BACKUP_DEST`（服务器以外的机器，需要免密 SSH）。在服务器上加 cron：

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
