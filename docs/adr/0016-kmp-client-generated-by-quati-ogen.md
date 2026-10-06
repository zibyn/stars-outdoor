# App 的客户端用 Quati OpenGen 从 openapi.yaml 生成

ADR 0004 让 `server/openapi.yaml` 成为唯一的契约，但只有服务端是生成的；App 的客户端是手写的，契约漂移编译时发现不了。新的 KMP 模块 `:shared` 里的客户端改为构建时生成（不提交），网络库换成 Ktor。我们用完整规格试了两个生成器（#218），选 [Quati OpenGen](https://github.com/goquati/ogen)（Gradle 插件 `de.quati.ogen`，`clientKtor`），因为它是唯一能直接用的：

- **3.1 和规格的全部写法都能处理**：`ErrorCode` 生成枚举，`Error` 是一个类；自由对象（`FeatureCollection.features`、`Package.outline`、空的 `TeamRequest`）生成 `JsonElement`。
- **二进制正确**：JPEG 上传按 `image/jpeg` 发原始字节；下载用生成的 `prepare…()` 拿到 `HttpStatement`，再 `execute { it.bodyAsBytes() }`。
- **`HttpClient` 由我们传进去**：通用职责都写成我们自己装的 Ktor 插件，生成代码不碰：设备 ID 和客户端版本两个头；426 弹升级提示（后台请求不弹）；非 POST 遇可重试网络错误重试一次；错误统一成 `OfflineError(错误码)`，并区分超时与离线。生成的调用遇到非 2xx 不抛异常（426 的错误体实测能取到），所以由错误插件统一抛出。运行库只有约 200 行（`core`、`client-ktor`），依赖 Ktor、kotlinx.serialization 和 `de.quati:kotlin-util`；iOS 目标都有发布。
- 是 Gradle 插件，`ogenGenerate` 挂在编译前，正合「构建时生成、不提交」。

## Considered Options

- **openapi-generator `kotlin` / `library=multiplatform`（7.25.0）**：生成一个完整的 Gradle 工程，3.1 仍是 beta。实测问题：
  - JPEG 上传把字节转成十六进制字符串，作为 `application/json` 发出（`"ffd80102"`）；下载抛 `NoTransformationFoundException`。我们的头像、照片、队伍图片都走这里。
  - 自由对象默认生成 `String`，反序列化就失败；要配 `typeMappings`/`importMappings` 改成 `JsonObject`，配了之后空对象请求体 `postTeam` 又编译不过。
  - `ApiClient` 自己建 `HttpClient`，并硬装 `ContentNegotiation`，DELETE 也设 JSON 请求体。

## Consequences

- **Gradle 守护进程要 JDK 21**（OpenGen 的要求）：CI（`android.yml`、`release.yml`）的 `setup-java` 从 17 改为 21，本机也一样；AGP 9 最低要求 JDK 17，用 21 没问题。
- OpenGen 还是 0.x，只有一位维护者，README 落后于代码（实际是 `clientKtor {}`，`model {}` 必填）。`de.quati:kotlin-util` 要自己声明依赖（JVM 变体只放进了 runtime）。生成代码是普通 Kotlin，运行库很小；它停更的话，我们锁版本或者把运行库抄进来就行。
- 可选字段生成 `Option<T>`（`Undefined`/`Some`），不是 `T?`；转换成领域类型时在传输层拆开。
- 两个生成器遇到不认识的 `ErrorCode` 都会反序列化失败；服务端先加了错误码、App 还是旧版时会遇到。错误插件不用生成的枚举，按 JSON 取 `error` 的字符串原样交给界面；界面本来就把不认识的码当服务器出错。
- 返回二进制的接口，`body()` 没有类型，一律用 `prepare…()`。
- 规格目前只用到 3.0 也有的写法（没有 `type: [x, "null"]`、`const` 之类）。以后哪个生成器不支持 3.1，可以把版本头降到 3.0，代价很小；服务端的 oapi-codegen 本来就以 3.0 为主。
