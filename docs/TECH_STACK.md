# 技术栈与架构

[返回项目首页](../README.md) · [启动指南](GETTING_STARTED.md) · [验证方式](VERIFICATION.md)

## 技术栈一览

| 范围 | 技术 | 落地方式 |
|---|---|---|
| 共享客户端 | Vue 3（当前依赖3.4.21）、uni-app、JavaScript | Composition API、组件复用、生命周期与条件编译 |
| 请求与状态 | uni.request、Vue ref/computed/watch | 统一错误处理、内存会话、账号/页面/请求序号校验 |
| HTTP服务 | Java 17、Spring Boot4.1.1、Spring MVC | REST接口、分层服务、统一异常、参数及权限验证 |
| 数据访问 | JdbcTemplate、MySQL InnoDB（当前本机9.6.0） | 参数绑定、行锁、事务、版本条件更新、唯一键 |
| 认证 | Spring Security Crypto的BCrypt、随机Bearer会话 | BCrypt强度12；会话摘要持久化、有效期与退出撤销 |
| 统计与分页 | SQL EXISTS、LEFT JOIN、GROUP BY、MAX/COUNT/SUM | 过滤后排序分页；当前页批量元数据与全局独立统计 |
| 文件处理 | Spring Multipart、图片格式/尺寸校验、本机文件存储 | 封面最大5MB，限制图片宽高；数据库保存文件元数据 |
| 桌面 | Electron44.7.0、@electron/packager20.3.0 | Mac ARM64、本机静态服务、隔离的渲染进程 |
| 验证 | JUnit Jupiter、MockMvc、Mockito、Node Test Runner | 真实临时MySQL集成、前端模拟状态、构建产物和桌面测试 |
| 工具 | Maven、HBuilderX、Node.js22.12+、Python3、Git | 依赖管理、跨端编译、隔离验证与源码/产物清单 |

认证使用服务端随机会话；上述Spring Security Crypto用于密码哈希，未接入完整Spring Security过滤器链。前端API和Controller是接口契约的源码依据。

## 架构

```mermaid
flowchart LR
    Web[Web浏览器] --> Shared[uni-app / Vue3共享业务代码]
    WX[微信小程序] --> Shared
    App[Android / iOS App资源] --> Shared
    Mac[Electron Mac客户端] --> H5[本机H5静态资源]
    H5 --> Shared
    Shared --> Request[统一HTTP请求 / Bearer会话]
    Request --> MVC[Spring MVC Controller]
    MVC --> Service[Service / 权限 / 事务 / 业务规则]
    Service --> JDBC[Repository / JdbcTemplate]
    JDBC --> DB[(MySQL / InnoDB)]
    Service --> Files[本机图片目录]
    Shared --> Local[按账号的草稿与浏览记录]
```

App节点表示可编译资源，完整安装包与手机验收仍待完成。Electron加载H5构建，Java/MySQL仍是独立服务，不会随桌面应用自动安装。

## 数据与一致性

- 用户与会话分表，原始Bearer Token保留于客户端内存；服务端保存摘要并检查有效期。刷新页面后重新登录。
- 商品使用整数分保存金额，支持卖家、封面、分类、状态、版本号。旧版本修改不能覆盖新状态。
- 收藏、公开留言、发布记录、草稿备份各自保留账号/商品归属。
- 购买意向保存商品标题、价格、版本快照，保留买卖双方与状态历史。卖家接受意向不自动标记商品已售。
- 意向内留言保存提交标识与撤回状态；个人阅读游标单独保存并只前进，查询不自动修改游标。
- 最近接收时间以当前账号收到、未撤回留言的MAX(created_at)计算，已读留言也参与。排序SQL与显示批次采用同一定义。

分页和本页时间在同一只读事务里取得。其他HTTP请求各有查询快照；跨请求同时发生新消息或撤回时，需要刷新更新结果，尚未实现游标分页或实时推送。

<a id="source-map"></a>

## 实现索引

| 设计点 | 可以直接阅读的源码 |
|---|---|
| 商品版本冲突与权限 | [ProductService](../campus-market-server/src/main/java/com/campus/market/product/ProductService.java)、[ProductRepository](../campus-market-server/src/main/java/com/campus/market/product/ProductRepository.java) |
| 发布幂等 | [PublicationService](../campus-market-server/src/main/java/com/campus/market/publication/PublicationService.java)、[表结构](../campus-market-server/sql/lesson38-product-publications.sql) |
| 购买意向状态与行锁 | [PurchaseIntentService](../campus-market-server/src/main/java/com/campus/market/intent/PurchaseIntentService.java)、[Repository](../campus-market-server/src/main/java/com/campus/market/intent/PurchaseIntentRepository.java) |
| 留言幂等与撤回 | [PurchaseIntentMessageService](../campus-market-server/src/main/java/com/campus/market/intent/PurchaseIntentMessageService.java)、[Repository](../campus-market-server/src/main/java/com/campus/market/intent/PurchaseIntentMessageRepository.java) |
| 未读聚合 | [PurchaseIntentUnreadRepository](../campus-market-server/src/main/java/com/campus/market/intent/PurchaseIntentUnreadRepository.java) |
| 最近收到排序与批量时间 | [排序枚举](../campus-market-server/src/main/java/com/campus/market/intent/PurchaseIntentSort.java)、[Repository](../campus-market-server/src/main/java/com/campus/market/intent/PurchaseIntentRepository.java) |
| 会话及密码哈希 | [SessionService](../campus-market-server/src/main/java/com/campus/market/auth/SessionService.java)、[PasswordConfig](../campus-market-server/src/main/java/com/campus/market/config/PasswordConfig.java) |
| 前端异步失效与结果提交 | [PurchaseIntentList.vue](../campus-market-client/components/PurchaseIntentList.vue)、[request.js](../campus-market-client/api/request.js) |
| 跨端编译与哈希校验 | [build.mjs](../campus-market-client/tools/build.mjs)、[build-check.mjs](../campus-market-client/tools/build-check.mjs) |
| 桌面服务、窗口及权限 | [local-server.mjs](../campus-market-desktop/src/local-server.mjs)、[window-policy.mjs](../campus-market-desktop/src/window-policy.mjs) |

## 当前边界

此版本聚焦校园商品管理、购买意向与双方沟通。支付、正式订单、库存锁定、WebSocket推送、容器化部署、线上性能压测及Android/iOS安装包属于后续工作；未将它们列为已完成技术能力。
