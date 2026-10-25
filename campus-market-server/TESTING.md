> 当前入口与统计请看仓库根目录的 [验收记录](../docs/VERIFICATION.md) 和 [运行指南](../docs/GETTING_STARTED.md)。下文保留历次教学记录，数字对应当时版本。

# 第81课：项目自带的一键隔离后端验收

## 本课解决的问题

原有完整验收入口依赖课程工作目录、早期SQL附件和手工拼接的测试类名单。本课把入口、MySQL历史结构文件、临时MQ工具和报告校验放进后端项目。拿到后端目录即可检查环境并运行测试，不再依赖Codex目录或某台电脑的用户名路径。

没有增加Maven/npm依赖，使用Python标准库与已安装的Java、Maven、MySQL、Redis、RabbitMQ。当前实际验收环境是macOS ARM64、JDK17；脚本按POSIX编写，Linux尚未做实际运行验收，Windows原生不在本课范围。

## 第82课补充：快速分层

第85课增加10项Flyway真实MySQL测试；完整all现为67类989项，quick仍为8类73项。迁移集成类只允许在入口启动的私有MySQL内创建夹具库，默认业务迁移开关保持关闭。

新增`--suite quick`：预检只需要JDK17和Maven，执行8类基础测试（当前73项），不启动中间件；完整all和docs入口保持原有覆盖范围。详见[CI.md](CI.md)。工具自身检查现为32项。

## 准备与运行

需要Python3.9+、JDK17、Maven及mysql/mysqld/mysqldump、redis-server、rabbitmq-server/rabbitmqctl。请先安装这些工具；入口不会静默安装软件，也不会修改系统服务。macOS会查找PATH和常见Homebrew安装位置。

IDEA的项目SDK与终端JAVA_HOME是两个配置。若检查提示JDK版本错误，在macOS终端选择已安装的17：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

进入后端目录：

```sh
# 只检查环境和测试类，不启动服务
python3 -B scripts/test_backend.py --check

# 完整验收；首次可能需要Maven下载项目依赖
python3 -B scripts/test_backend.py

# 本机依赖已缓存时
python3 -B scripts/test_backend.py --offline

# 只验收第80课文档功能
python3 -B scripts/test_backend.py --offline --suite docs

# 验收测试工具自身，不启动数据库/MQ
python3 -B -m unittest discover -s scripts/tests -v
```

也可以从其他目录用脚本的绝对路径调用。路径含空格时记得用引号。

若工具没有放在PATH，Redis可通过REDIS_SERVER_BIN指定，RabbitMQ可通过RABBITMQ_SBIN指定安装目录。JAVA_HOME应指向JDK17。`--offline`仅控制Maven依赖解析；测试仍会使用本机临时网络端口。

## 一次完整验收发生什么

1. 预检依赖、JDK版本和源文件。all自动发现当前67个Java测试类；docs选择3个指定文档测试类。
2. 获取本项目测试锁；在私有临时目录中复制源码，排除data、target、IDE和Git目录。
3. 临时目录沿用campus-lesson24-前缀，以满足历史上传测试的清理身份断言，不放宽原断言。启动独立MySQL数据目录与随机回环端口，重放历史结构、建立各测试库，检查历史迁移没有丢失数据。
4. 启动两个临时Redis：商品缓存与noeviction登录保护分开。
5. 启动随机节点名、随机凭证和独立vhost的RabbitMQ；不连接桌面正在运行的MQ业务节点。
6. 构造测试专用环境，不继承真实数据库/MQ密码、Spring配置注入、API文档开关和JVM注入选项。
7. 在临时源码副本运行Maven clean test。原项目target/classes不会被删除或重编译。
8. 校验JUnit报告覆盖所有被选中的测试类，并要求失败、错误、跳过均0；退出码为0才算通过。
9. 关闭本次拥有的临时进程组，删除临时运行目录，保留私有验收结果。测试失败、超时或可捕获的SIGINT/SIGTERM中断也走清理流程；无法确认清理成功则报失败并保留目录供排查。

强制kill -9、断电等情况无法保证Python执行清理；此时应检查摘要中记录的临时目录和所属进程，不能将运行状态当成通过。

Erlang的本机epmd可能由其他RabbitMQ节点共用，本工具不会停止这个共享服务。

## 如何查看结果

```text
data/test-results/
  latest.json                  最近一次正式运行的摘要
  日期时间-随机标识/
    summary.json               此次摘要与测试数量
    maven.log                  此次Maven日志
    junit/TEST-*.xml            此次JUnit原始报告
```

目录仅当前用户可访问，摘要/报告/日志不提交Git。历史运行分别保留，入口不覆盖以前的结果。`--check`没有正式运行结果，不覆盖latest.json。

摘要示例（具体数字以当前运行结果为准）：

```json
{
  "suite": "all",
  "status": "passed",
  "expectedTestClasses": 67,
  "buildUsesTemporarySourceCopy": true,
  "junit": {
    "tests": 989,
    "failures": 0,
    "errors": 0,
    "skipped": 0,
    "suites": 67
  },
  "temporaryRuntimeRemoved": true
}
```

源码SHA-256来自编译前的临时副本，可以区分不同代码版本。它不是Git提交号，也不是制品签名。

## 为什么不能只看“构建成功”

Java集成测试使用EnabledIfEnvironmentVariable限制可写的测试数据库。直接运行Maven而没有测试环境时，部分测试可能被跳过。入口会核对报告中的测试类集合、用例数量和skipped：少报告、空报告、失败、错误或跳过都不能当成验收通过。

新测试类以Test.java或Tests.java结尾，并放在com.campus.market包下；自动发现后无需手改长名单。当前没有JUnit嵌套类报告；未来增加嵌套测试或特别的报告结构时，需同步调整覆盖校验，不能绕过它。

## 结构文件的范围

补回sql/lesson15-users.sql和sql/lesson19-login-sessions.sql，内容来自原有项目建表脚本，仅用于建立临时库；这是恢复测试输入，不是对当前业务库执行新迁移。其他历史迁移仍按原顺序使用。

## 文件职责

- scripts/test_backend.py：命令行、预检、运行锁、结果和退出码。
- scripts/backend_test_runtime.py：临时源码、数据库迁移与服务编排。
- scripts/backend_test_support.py：环境隔离、工具发现、报告覆盖、进程组清理。
- scripts/test_mq_runtime.py：独立测试MQ节点。
- scripts/tests/test_backend_tools.py：32项测试基础设施检查。

完整命令仍需已安装的本机中间件，不是Docker环境。第81课先完成可移植的验收入口；第82课已加入quick分层和GitHub Actions工作流模板，详见[CI.md](CI.md)。工作流尚未推送或执行云端首跑，本机通过不代表远程CI已通过。


第86课：已有数据库显式接管见[LEGACY-BASELINE.md](LEGACY-BASELINE.md)。先check核对14张表与25份冻结SQL，再显式baseline到74；默认应用启动不接管旧库。新增26项测试，完整67类989项，quick仍8类73项。


第87课：最新列表先分页再关联，新增首页及分类复合索引V87；见[PERFORMANCE.md](PERFORMANCE.md)。新库26份迁移，V74基线库只补1份；完整67类989项，quick仍73项。


第88课：新增公开连续浏览API `/api/products/feed` 与uni-app连续浏览页，使用 `(created_at,id)` 双键游标和多取1条判尾，无总数和深OFFSET。复用V87索引，无新迁移或依赖。完整67类989项，专项25项；前端新增12项并发、重试与游标协议检查。见 [CURSOR-PAGINATION.md](CURSOR-PAGINATION.md)。


第89课：加入ADMIN实时授权、管理路由统一拦截、本机授权/撤销审计及只读管理中心。见 [ADMIN-ACCESS.md](ADMIN-ACCESS.md)。无新依赖；27份迁移、新增2表。完整67类989项，发布quick仍73项。


第90课：新增发布后商品巡查、平台下架限制、卖家复核、解除限制和商品证据审计。见 [PRODUCT-MODERATION.md](PRODUCT-MODERATION.md)。28份迁移、18张业务表；完整67类989项，专项74项，quick仍73项。

第91课新增ReportIntegrationTest的32项举报/处理/隐私/并发与回滚测试，以及1项API契约测试。

第92课新增NotificationIntegrationTest 35项、NotificationMqIntegrationTest 10项和1项API契约测试。旧业务隔离库补充通知双表，迁移用例继续独立建库。

第94课：新增通知指标与采样器两个无需中间件的单元测试类，已加入quick明确名单。当前quick为10类89项，完整all为74类1106项。新增监控集成测试仅使用隔离MySQL/RabbitMQ；详见[NOTIFICATION-MONITORING.md](NOTIFICATION-MONITORING.md)。

第95课：新增16项批量已读数据库测试和1项API契约测试，完整all现为75类1123项；quick仍10类89项。新增18项前端测试，见[NOTIFICATION-BATCH-READ.md](NOTIFICATION-BATCH-READ.md)。


第96课新增AdminOrderOverviewIntegrationTest的20项私有MySQL测试，以及1项API契约测试。覆盖聚合、金额精度、到期边界、权限和只读；临时样本不写真实业务库。


第97课新增AdminOrderQueryIntegrationTest的25项隔离查询测试和1项文档契约测试。原业务库不写样本，覆盖筛选、分页、快照、权限和只读。


第98课新增AdminOrderDetailIntegrationTest的26项隔离MySQL测试和1项API契约测试。事件缺失、断档、身份/时间错误应503且不补写，详情与事件共享只读快照。


第99课新增 SessionManagementIntegrationTest 的28项隔离数据库测试、1项API契约测试及24项前端测试；覆盖账号隔离、撤销/签发并发、等待锁时失效、事务回滚和页面异步状态。


第100课新增32项PasswordChangeIntegrationTest、4项Redis保护集成测试、1项API契约和26项前端测试；改密仅作用于隔离样本账号。


第101课新增OrderReviewIntegrationTest的32项权限、幂等、并发与回滚测试，新增1项92升级测试和1项API契约；新增26项前端测试。旧迁移仍冻结，发布初始化补入评价表。


## 第102课更新

首次评价现已接入可靠站内通知，重放不重复提醒，历史评价不补发。完整事务、隐私和验收说明见 REVIEW-NOTIFICATIONS.md。


## 第103课：评价清单

我的订单新增待评价与已评价清单，按当前角色筛选分页。是否已评价只看本人记录。完整说明见 ORDER-REVIEW-TASKS.md。


## 第104课：交易问题反馈

新增用户侧反馈提交及本人记录，尚未接管理员处理或退款。新增V104空表迁移，新库32份迁移；旧31份迁移不变。完整说明见 ORDER-ISSUES.md。


## 第105课：反馈处理与结果通知

管理员可逐条答复或不予支持；本人记录显示处理结果，可靠站内通知仅提醒该提交人。不改变交易或执行退款。新增V105空结果表，旧32份迁移不变，新库33份迁移。完整说明见 ORDER-ISSUE-RESOLUTIONS.md。


## 第106课验收

新增OrderIssueListIntegrationTest及本人清单API文档契约。重点验证另一方处理不影响本人、SQL先筛选后分页、只读事务快照、列表无反馈/处理正文、会话撤销和异常数据拒绝。运行入口与隔离中间件策略不变；无新迁移或依赖。详见ORDER-ISSUE-LIST.md。


## 第107课验收

新增IssueOverviewIntegrationTest与概览/时长筛选API契约。覆盖权限、空表、两种处理结果、边界、时钟、子集、SQL分页、只读和异常记录。原AdminOrderIssueIntegrationTest适配列表新增age及sampledAt字段。详见ISSUE-OPERATIONS.md。


## 第108课验收

新增AdminIssueSearchIntegrationTest与精确检索API契约；原管理员列表回归适配orderId回显。重点验证规范编号、权限、交集、SQL先筛选后分页、处理后页码收缩及只读快照，见ISSUE-SEARCH.md。


第109课：OrderTaskIntegrationTest验证本人双角色待办、到期边界、独立评价、会话与只读，API契约验证最小响应。


第110课：NotificationTargetIntegrationTest验证来源推导、身份边界、单条/批量已读回执及Outbox锁不阻塞Inbox已读。


第111课OwnOrderSearchIntegrationTest验证严格编号、本人隔离、全量汇总保持、过滤后分页及并发快照。


第112课：OrderExportIntegrationTest（24项），复用OwnOrderSearchIntegrationTest（26项）；CSV及页面隔离见前端tools/order-export.test.mjs（38项）。使用私有临时库，不向真实库造数。


第113课RegistrationRateLimitIntegrationTest新增25项，和LoginRateLimitIntegrationTest、ApiContractIntegrationTest一同专项验收；使用私有Redis和MySQL，不向真实库造账号。

第114课注册保护指标、面板和告警：[REGISTRATION-MONITORING.md](REGISTRATION-MONITORING.md)。

第116课新增有效登录分页明细：[SESSION-LIST.md](SESSION-LIST.md)。


第117课新增ReceivedReviewIntegrationTest及收到评价API文档契约检查。隔离库验证对方记录归属、评分分布与分页、只读快照、错误与数据异常；前端实际页面生命周期及API封装测试见tools/received-reviews.test.mjs。


第118课新增FavoriteBatchIntegrationTest和API文档契约检查：私有库覆盖整批边界、幂等与并发、事务回滚及旧收藏兼容；前端新增favorite-batch.test.mjs。


第119课新增NotificationFilterIntegrationTest与分类筛选文档契约；私有库覆盖身份范围、分类／已读交集、SQL分页、快照和原已读操作。前端新增notification-filters.test.mjs并保留原通知系列测试。


第122课新增FavoriteAvailabilityIntegrationTest及API文档契约；验证可用性分区、SQL分页、只读快照和旧接口兼容，前端新增favorite-availability.test.mjs。
