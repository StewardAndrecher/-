# Getting Started

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.1.1/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.1.1/maven-plugin/build-image.html)
* [Spring Web](https://docs.spring.io/spring-boot/4.1.1/reference/web/servlet.html)

### Guides
The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.


## 第80课：自动接口文档

新增springdoc-openapi 3.1.1；访问与契约验收见 [接口文档指南](docs/API_DOCUMENTATION.md)。开发环境设置API_DOCS_ENABLED=true后重启，通过http://localhost:8080/docs.html访问。默认关闭，仅允许本机。

## 第81课：一键隔离回归验收

在后端目录执行 `python3 -B scripts/test_backend.py --check` 检查环境，执行 `python3 -B scripts/test_backend.py --offline` 运行完整隔离测试。入口、结果和排查说明见 [TESTING.md](TESTING.md)。

## 第82课：快速验收与CI配置

无需启动MySQL/Redis/RabbitMQ的快速检查：`python3 -B scripts/test_backend.py --suite quick`。分层范围与工作流放置位置见 [CI.md](CI.md)。

## 第84课：独立后端发布包

执行 `python3 -B scripts/build_release.py --offline`，通过73项快速测试后打包可执行JAR、空库SQL、外置配置示例和校验清单。使用说明见 [RELEASE.md](RELEASE.md)。

## 第85课：Flyway版本迁移

新库可通过DB_MIGRATION_ENABLED=true开启26个版本迁移；默认关闭，不自动接管当前业务库。流程、历史校验与旧库边界见[MIGRATIONS.md](MIGRATIONS.md)。


第86课：已有数据库显式接管见[LEGACY-BASELINE.md](LEGACY-BASELINE.md)。先check核对14张表与25份冻结SQL，再显式baseline到74；默认应用启动不接管旧库。新增26项测试，完整67类989项，quick仍8类73项。


第87课：最新列表先分页再关联，新增首页及分类复合索引V87；见[PERFORMANCE.md](PERFORMANCE.md)。新库26份迁移，V74基线库只补1份；完整67类989项，quick仍73项。


第88课：新增公开连续浏览API `/api/products/feed` 与uni-app连续浏览页，使用 `(created_at,id)` 双键游标和多取1条判尾，无总数和深OFFSET。复用V87索引，无新迁移或依赖。完整67类989项，专项25项；前端新增12项并发、重试与游标协议检查。见 [CURSOR-PAGINATION.md](CURSOR-PAGINATION.md)。


第89课：加入ADMIN实时授权、管理路由统一拦截、本机授权/撤销审计及只读管理中心。见 [ADMIN-ACCESS.md](ADMIN-ACCESS.md)。无新依赖；27份迁移、新增2表。完整67类989项，发布quick仍73项。


第90课：新增发布后商品巡查、平台下架限制、卖家复核、解除限制和商品证据审计。见 [PRODUCT-MODERATION.md](PRODUCT-MODERATION.md)。28份迁移、18张业务表；完整67类989项，专项74项，quick仍73项。

第91课举报与处理工单见[PRODUCT-REPORTS.md](PRODUCT-REPORTS.md)。

第92课站内通知与MQ可靠投递见[NOTIFICATIONS.md](NOTIFICATIONS.md)。

第93课管理员通知投递概览见[NOTIFICATION-OPERATIONS.md](NOTIFICATION-OPERATIONS.md)。无新依赖或迁移，新增只读任务聚合接口与管理页面。

第94课通知自动监控与告警见[NOTIFICATION-MONITORING.md](NOTIFICATION-MONITORING.md)。新增7条通知规则、10个Grafana面板；quick现为89项/10类，无新依赖或迁移。

第95课本页通知批量已读见[NOTIFICATION-BATCH-READ.md](NOTIFICATION-BATCH-READ.md)。明确编号、最多20条、整批鉴权与回滚；无新依赖或迁移。


第96课交易运营概览见[ORDER-OPERATIONS.md](ORDER-OPERATIONS.md)。全平台六状态、已完成模拟金额及到期待付款子集；实时管理员权限，无新依赖或迁移。


第97课管理员订单检索见[ADMIN-ORDER-QUERY.md](ADMIN-ORDER-QUERY.md)。状态/到期待付款、精确编号、分页与事务快照，无新依赖或迁移。


第98课管理员订单详情和进度时间线见[ADMIN-ORDER-DETAIL.md](ADMIN-ORDER-DETAIL.md)。角色化最小响应、历史快照说明、同一事务快照与异常记录拒绝；无新依赖或迁移。


第99课登录会话管理见[SESSION-MANAGEMENT.md](SESSION-MANAGEMENT.md)。当前会话保留、账号锁与签发串行、未知结果先刷新；无新依赖或迁移。


第100课修改密码与全会话退出见[PASSWORD-CHANGE.md](PASSWORD-CHANGE.md)。验证原密码，事务更新哈希/撤销会话，登录校验与签发共享账号锁，无新依赖或迁移。


第101课完成交易后的双方评价见[ORDER-REVIEWS.md](ORDER-REVIEWS.md)。仅参与者、每方一次、载荷幂等与订单行锁；新增V101评价表，无新依赖。


## 第102课更新

首次评价现已接入可靠站内通知，重放不重复提醒，历史评价不补发。完整事务、隐私和验收说明见 REVIEW-NOTIFICATIONS.md。


## 第103课：评价清单

我的订单新增待评价与已评价清单，按当前角色筛选分页。是否已评价只看本人记录。完整说明见 ORDER-REVIEW-TASKS.md。


## 第104课：交易问题反馈

新增用户侧反馈提交及本人记录，尚未接管理员处理或退款。新增V104空表迁移，新库32份迁移；旧31份迁移不变。完整说明见 ORDER-ISSUES.md。


## 第105课：反馈处理与结果通知

管理员可逐条答复或不予支持；本人记录显示处理结果，可靠站内通知仅提醒该提交人。不改变交易或执行退款。新增V105空结果表，旧32份迁移不变，新库33份迁移。完整说明见 ORDER-ISSUE-RESOLUTIONS.md。


## 第106课：我的交易反馈清单

我的订单增加本人反馈清单，按买入/卖出、待处理/已处理筛选分页，汇总和记录共享只读事务。无新软件包、表或迁移；见 ORDER-ISSUE-LIST.md。


## 第107课：反馈处理时效

新增只读反馈处理概览，统计待处理满24/72小时子集，并可按最早提交优先筛出工单。无新依赖、表或迁移，详见ISSUE-OPERATIONS.md。


## 第108课：反馈工单精确检索

管理员可按订单号查找双方反馈，和状态/时长取交集；详情返回保留条件及实际页码，重新读取最新结果。无新依赖或迁移，见ISSUE-SEARCH.md。


第109课：我的交易待办中心见 TRADE-TASKS.md。当前会话本人双角色采样，状态入口和独立待评价，无新依赖或迁移。


第110课业务通知精准跳转见NOTIFICATION-TARGETS.md。评价及本人反馈结果直接进入相应页面；无依赖或迁移。


第111课个人订单精确检索见OWN-ORDER-SEARCH.md。编号与本人角色/状态取交集，详情返回保留条件；无新依赖或迁移。


第112课个人订单导出见ORDER-EXPORT.md。本人条件快照、200笔上限、预览及CSV，无新依赖或迁移。


第113课注册防刷见REGISTRATION-PROTECTION.md。复用独立登录Redis的原子来源窗口，429/503和前端等待提示，无新依赖或迁移。

第114课注册保护指标、面板和告警：[REGISTRATION-MONITORING.md](REGISTRATION-MONITORING.md)。

第116课新增有效登录分页明细：[SESSION-LIST.md](SESSION-LIST.md)。


第117课收到的交易评价清单见 [RECEIVED-REVIEWS.md](RECEIVED-REVIEWS.md)。本人双角色、对方评分筛选、只读分页，无新依赖或迁移。


第118课收藏批量管理见 [FAVORITE-BATCH.md](FAVORITE-BATCH.md)。明确编号、本人范围、单条SQL事务和前端确认，无新依赖或迁移。


第119课站内通知组合筛选见 [NOTIFICATION-FILTERS.md](NOTIFICATION-FILTERS.md)。分类与已读状态取交集，保持原响应、目标跳转及本页已读边界，无新依赖或迁移。


第122课收藏可用性筛选见 [FAVORITE-FILTERS.md](FAVORITE-FILTERS.md)。数据库过滤后分页、本人范围、失效商品详情不外泄，无新依赖或迁移。
