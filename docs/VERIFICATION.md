# 测试与验收

[项目首页](../README.md) · [启动指南](GETTING_STARTED.md) · [技术栈](TECH_STACK.md)

## 当前记录（第82课）

| 范围 | 已通过记录 | 证据版本 |
|---|---|---|
| 完整Java回归 | 843项、59类；0失败、0错误、0跳过 | 第82课单次完整隔离运行，macOS ARM64 |
| quick Java基础测试 | 73项、8类；不启动中间件 | 第82课桌面入口及最小PATH复查 |
| Python验收工具 | 32项通过 | 第82课 |
| 工作流配置 | actionlint静态校验通过 | 云端运行状态见Actions |
| 监控规则 | 17个场景、168条告警断言、2条表达式断言 | 第76/77课 |
| 告警通知 | 35项检查，含故障、抑制、恢复与持久化 | 第77课本机回环接收器 |
| 前端行为 | 47组通过 | 本次发布副本重新执行 |
| 前端工具 | 50项通过 | 本次发布副本重新执行，包含请求编号检查 |
| 桌面规则 | 17项通过 | 本次发布副本重新执行 |
| 三端资源 | Web53、微信151、App32；App不可直接安装 | 本次发布副本重新构建 |

[公开验收摘要](verification-snapshot.json)保留各范围的版本来源。测试数量不是性能压测结果；quick覆盖范围小于完整回归。

## 后端复查

在仓库根目录运行：

```sh
# 快速模式：Python3.9+、JDK17、Maven
python3 -B scripts/test_backend.py --check --suite quick
python3 -B scripts/test_backend.py --suite quick

# 完整模式：还需已安装MySQL、Redis、RabbitMQ二进制
python3 -B scripts/test_backend.py

# 仅文档相关20项集成与基础测试
python3 -B scripts/test_backend.py --suite docs

# 工具自身检查
cd campus-market-server
python3 -B -m unittest discover -s scripts/tests -v
```

根目录入口转交后端的同一实现；不再维护另一份测试类名单。依赖缓存齐全可加--offline。工具在临时源码副本构建，不清理IDEA的target/classes。完整模式使用临时MySQL、两个Redis和独立MQ节点；不继承真实业务DB/MQ/Spring/JVM注入变量。

JUnit报告必须完整且无失败、错误、跳过；零用例或缺少报告不能通过。故意失败和SIGTERM中断的清理分支已实际验证。临时服务退出后保存私有summary、Maven日志与JUnit；启动失败时额外保留指定启动日志末尾。kill -9或断电不保证Python能够清理。

第82课首次完整尝试在临时MQ启动阶段退出；独立探测与最终完整回归成功，原启动退出原因未复现。本次公开版本的225份Java/SQL/资源/Maven构建输入与最终843项通过记录一致。原13张业务表、图片、后端进程和195个编译文件未变。

完整机制与运行限制见[后端TESTING](../campus-market-server/TESTING.md)。本机完整回归为macOS实测，Linux quick的云端结果独立记录。

## GitHub Actions

根目录[backend-quick.yml](../.github/workflows/backend-quick.yml)在push、pull_request或手动触发时运行：Ubuntu24.04、Temurin17、32项Python检查与73项quick测试。使用只读仓库权限和固定Action提交SHA，报告保留7天。它不运行完整中间件回归，不部署业务，也未自动配置分支保护。

[查看当前云端运行与日志](https://github.com/StewardAndrecher/-/actions/workflows/backend-quick.yml)。静态校验通过不代表云端首跑通过；每次运行结果以Actions页面和JUnit为准。

## 第75至80课新增证据

- Micrometer与Prometheus：MQ队列、Outbox及消费者指标使用独立采集凭证，采集失败和业务值为零分别处理。
- Grafana：15个面板；8条规则经17个场景验证。
- Alertmanager：分组、同实例下游抑制、重复间隔与恢复通知；接收器SQLite重启后保留历史。只验收本机通知，不宣称已接入外部消息渠道。
- HTTP追踪：真实500响应显示请求编号，服务端日志可按编号定位；前端请求和图片上传都保留诊断编号。
- MQ追踪：请求与事件标识贯穿Outbox投递、确认、消费和幂等结果，不改变消息架构或宣称exactly-once。
- springdoc：51个业务操作、契约与默认关闭测试通过；Swagger真实调用商品接口200；健康与运维采集接口不混入业务文档。

以下保留此前业务页面与跨端证据，版本来源分别注明。

## 前端与桌面复查

HBuilderX须安装Vue3 uni-app编译器，Node.js22.12+可用；自定义安装位置设置UNI_COMPILER_HOME为uniapp-cli-vite目录、NODE_BIN为Node可执行文件。

```sh
python3 scripts/test_frontend.py
cd campus-market-desktop
npm ci
npm test
```

前端入口预处理平台分支、重建Web/微信/App资源，执行47组检查及50项工具测试。桌面17项验证静态服务、路径、Host、资源一致性和窗口规则；打包与启动另见启动指南。

## 页面与迁移证据

- 第74课在隔离环境关闭数据库扫描后停止MQ：健康仍UP，下单HTTP201且Outbox保留；恢复MQ后第2次投递成功，超时关闭、释放占用，系统超时记录仅1条。持久测试消息在Broker重启后仍存在。
- 隔离网页真实登录并打开MQ关闭订单，显示超时关闭和系统时间线；账号退出并撤销测试会话。验收采用200毫秒TTL与测试订单创建时间回拨，真实业务仍15分钟；截图创建字段和原CREATED事件时间因此不同。
- 第74课真实库只新增空Outbox表，原12张业务表DDL/行/自增值和上传图片一致；IDEA后端UP，MQ消费者连接成功。前端源码未改，本课没有重做微信/App/Mac构建或手机验收。

以下页面与跨端证据来自第73课或更早的对应版本：

- 隔离HTTP与浏览器走完创建订单→模拟付款→卖家交付→买家收货，四条事件和操作角色正确；重复请求不改变记录，非参与者404、匿名401。
- 六种历史订单迁移前后原字段保留，每笔仅补一条HISTORY_SNAPSHOT；旧PAID订单可继续交付/收货。快照明确标注无法还原此前实际操作人。
- 订单统计在角色全量范围计算，筛选和翻页不改变全量统计，金额仅累计已完成模拟订单的整数分快照。
- 最新真实Mac包已启动、登录和检查空订单页，包内53个Web资源与实际构建匹配；完整多角色时间线在隔离Web验收。
- 当前微信完成编译及结构检查，历史第29课有开发者工具截图；没有将其描述为最新全部业务的模拟器验收。
- Android/iOS只完成App资源构建，未生成APK/IPA或进行手机验收。

当前MQ只验收单节点，不代表集群高可用；至少一次投递与幂等业务效果，不是端到端exactly-once。TTL从入队计时，数据库扫描继续补偿，parking需人工排查。

付款为模拟，无真实资金结算。事件为应用层只追加，数据库管理员能修改，删除订单会级联删除事件。暂无公网业务部署、实时推送或大规模性能压测结果。

## 发布内容

公开源码、结构SQL、测试、文档与开发截图；真实业务库、会话、上传目录、IDE配置、密码、私钥、构建目录和备份包不进入Git。示例AppID与局域网地址需替换为自己的开发环境。
