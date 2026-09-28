# 上线前用内网服务器和 RustFS，对象存储只走 S3 API

上线前没有真实用户，提前买香港 VPS、域名和 OSS 只是在空转花钱。因此开发和测试阶段，由内网服务器用 `deploy/compose.yaml` 跑 PostGIS 和自托管的 RustFS，分别替代 VPS 上的数据库和阿里云 OSS 香港（ADR 0001）。实际上线前再购置香港 VPS 并迁移到 OSS。

为了让迁移只是改配置，后端访问对象存储**只用 S3 API**（OSS 兼容 S3），不引入阿里云 OSS SDK；端点、bucket 和密钥都来自环境变量（`deploy/.env.example`）。

## Consequences

- 迁移到 OSS 时只换 `S3_ENDPOINT`、`S3_REGION` 和密钥，并改用 virtual-hosted 寻址（OSS 不接受 path-style，RustFS 默认用 path-style）。
- 内网没有公网域名和 HTTPS，手机只能在同一局域网内连测试服务器；域名、费用预警随上线一起做。
- `deploy/compose.yaml` 把数据库和 RustFS 控制台开放在所有网卡上，只适合内网；上了公网 VPS 要重新收口。
- 和风天气、天地图 Key 现在就申请，放进服务器的 `deploy/.env`，不进仓库。
