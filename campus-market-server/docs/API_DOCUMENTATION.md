# 第80课：自动接口文档与接口契约验收

后端从Controller和Java请求/响应类型生成OpenAPI；Swagger UI负责可视化浏览和发送测试请求。接口契约测试检查路由覆盖、认证、重要状态码和模型。事务、权限、MQ重试等继续由原有业务集成测试验证。本课补充核心契约，尚未逐字段标注全部长度、枚举和业务限制。

## 新依赖与入口

新增`org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1`，Spring Boot 4系列组件；项目仍使用Java 17、Spring Boot 4.1.1和Jackson 3。Swagger UI资源包含在后端依赖中，无需新增前端软件包。

默认关闭。在IDEA后端运行配置的环境变量中增加`API_DOCS_ENABLED=true`，保留已有数据库、Redis、RabbitMQ和监控配置，重新加载Maven并重启后端。

- 页面：http://localhost:8080/docs.html
- OpenAPI JSON：http://localhost:8080/api-docs
- 原商品接口：http://localhost:8080/api/products

文档仅允许本机TCP来源、本机Host和同源Origin，远程返回403。关闭开关时，JSON、页面、静态资源均返回404。过滤器不信任客户端代理头；部署时不要通过代理转发文档。

Swagger UI使用打包资源，无外部在线校验器，不持久化授权。它可以执行真实接口：写操作会改变当前数据库。练习先用公开GET，写操作使用隔离测试环境。

也可在后端目录执行`python3 scripts/api-docs-local.py enable-idea`，配置会先备份到不提交Git的data/api-docs；关闭使用disable-idea，均需重启后端。导出使用`python3 scripts/api-docs-local.py export --output /tmp/campus-openapi.json`，为防覆盖要求目标文件不存在。

## 登录、状态码、日志编号

- 注册、登录、商品列表、排序目录、分类、图片、卖家主页允许匿名。
- 商品详情、商品留言列表、商品下单状态允许匿名，登录后返回当前用户有关信息。
- 其余业务接口使用`Authorization: Bearer <accessToken>`。
- 点击Authorize只填accessToken，不加Bearer前缀。这是数据库会话令牌，不是JWT。
- HTTP响应的`X-Request-Id`用来查日志；下单请求体里的`requestId`是业务幂等键。同一业务操作重试复用幂等键，每次HTTP请求产生新的日志编号。

| 接口 | 行为 |
| --- | --- |
| POST /api/users/register | 成功201 |
| POST /api/users/login | 成功200；凭据错误401；保护429/503带Retry-After |
| POST /api/products | 成功201；金额priceInCents为整数分 |
| POST /api/products/{id}/orders | 首次201、幂等重放200、冲突409 |
| POST /api/users/logout | 204，没有响应体 |
| POST /api/orders/{id}/simulate-payment | 教学模拟付款，不接真实支付渠道 |

错误体仍是`{"code":"...","message":"..."}`，不额外添加日志字段。文档不包含健康探针、运维采集和内部存储记录。

## 同名分页类型

订单、草稿、购买意向、意向消息都有名为Page的内部Java类型。`springdoc.use-fqn=true`用完整类型名区分它们，避免模型被覆盖。

## 文件职责与测试

- `src/main/java/com/campus/market/docs/BusinessApiDocumentation.java`：业务分组、摘要、Bearer授权、状态码、关键输入约束。
- `src/main/java/com/campus/market/docs/ApiDocsAccessFilter.java`：默认关闭、本机限制、禁止缓存。
- `src/main/resources/application.properties`：扫描范围、路径、UI选项。
- `ApiDocsAccessFilterTest`：开关、IPv4/IPv6、来源、Host、Origin、方法、上下文路径、伪造代理头。
- `ApiContractIntegrationTest`：实际路由与文档集合一致、认证分类、201/200/204、登录保护、模型、真实错误响应、UI资源。

单独运行无数据库依赖的过滤器测试：

```sh
./mvnw -Dtest=ApiDocsAccessFilterTest test
```

契约集成测试要求LESSON74_DB_URL指向临时campus_lesson74_verify库；使用课程的隔离MySQL/Redis/RabbitMQ验收流程运行，不对真实campus_market库写入。

导出的JSON仅是当时的验收附件，运行中的`/api-docs`代表当前说明。修改Controller、DTO、鉴权规则或状态码后，同时更新文档配置并运行契约回归。

## 面试表述

“为Spring Boot多端共用后端接入OpenAPI/Swagger UI，自动生成接口文档，区分匿名、可选登录和Bearer会话鉴权；针对幂等下单201/200、204空响应和同名分页模型建立契约回归测试。”
