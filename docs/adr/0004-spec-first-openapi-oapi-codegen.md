# 接口契约先写 OpenAPI，再用 oapi-codegen 生成服务端代码

App（Kotlin）和服务端（Go）不共享代码，`server/openapi.yaml` 是两边唯一的契约。因此改接口时先改这份 yaml，再 `go generate ./...` 用 oapi-codegen 生成 `server/api/`（模型、`net/http` 路由和 strict server 接口），由 handler 实现生成的接口。这样文档和代码对不上时会直接编译失败，CI 也会检查生成文件是否最新。这是在已定的"标准库 net/http、不用框架"之内做到不漂移。

## Considered Options

- **从代码生成文档**（huma 等）：可以不写 yaml，但 handler 要换成框架的写法，违背"不用框架"；契约的源头也会变成 Go 代码，而不是两端都读的 yaml。
- **swaggo 注释**：注解的量和 yaml 差不多，而且只支持 Swagger 2.0。
- **继续手写 yaml**：handler 和文档靠人工保持一致。#39 就漏写过一个 500 响应。

## Consequences

- **错误格式统一**：所有错误都是 `{"error": <ErrorCode>, ...该错误码约定的字段}`，错误码全集就是 yaml 里 `ErrorCode` 的枚举，生成为 Go 常量；App 按同一组错误码映射提示文案。
  - 横切错误（`rate_limited`、`client_outdated`，将来的 `unauthorized`）由 `net/http` 中间件返回，在 yaml 的 `components/responses` 里定义一次，各接口引用。
  - 业务错误由 handler 返回生成的类型化响应，写在对应接口的 `responses` 下。
  - 意外错误由 handler 直接返回 `error`，统一记日志并回 500 `internal`，细节不外泄；请求解析失败统一回 400 `invalid_request`。
  - 不用 `default` 响应兜底所有状态码，否则类型约束就没了。
- **认证在 yaml 里声明**：需要登录的接口写 `security: [{bearerAuth: []}]`，其余保持匿名（地图、离线包、天气免登录）。生成代码会在这些接口的 context 里标出 `BearerAuthScopes`，由一个中间件看到这个标记后校验 token，再把用户放进 context。代码里不维护路由白名单，新接口不会漏掉鉴权。
- **流程**：改接口顺序是 yaml → `go generate` → 实现 handler。`server/api/api.gen.go` 是生成文件，不要手改。
