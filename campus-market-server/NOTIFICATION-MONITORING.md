# 第94课：通知自动采样、Grafana与Prometheus告警

新增Maven/npm/pip依赖0，无数据库迁移。复用Micrometer、独立凭证指标端点、现有Prometheus/Grafana/Alertmanager和本机技术告警收件箱。第93课管理页面保持按需读取，本课新增后台采样与独立运维面板。

## 采样与指标

MONITORING_ENABLED=true时注册通知指标。两个定时任务共用专用的2线程调度器，每15秒分别采样数据库和通知队列，首次延迟1秒；不占用通知发送、订单扫描或原订单监控线程。market.monitoring.poll-enabled=false可以停用自动采样。通知MQ关闭时仍可采样数据库，Broker值保持未知且不发起队列查询。

数据库复用NotificationOverviewService的一条只读聚合SQL和5秒查询超时。积压、重试、租约等口径与第93课一致。默认每15秒仍是全表聚合，当前教学规模适用；大表需后台汇总与保留策略，不能声称单个索引解决所有计数成本。查询超时不包括连接获取等全部HTTP/任务耗时。

Broker只通过AmqpAdmin.getQueueInfo被动读取本项目work与parking队列，不声明、删除、清空、发布、消费或重投消息。ready是可读取消息数，不包含已交给消费者但尚未ACK的消息；不能把ready为0等同端到端投递完成。消费者数只按work判断，parking通常没有消费者。

| 指标前缀 campus_notification_ | 含义 |
|---|---|
| delivery_enabled | 通知MQ配置开关，1/0 |
| outbox_poll_up | 最近数据库采样是否成功 |
| outbox_sample_age_seconds | 最近成功数据库采样年龄，单调时钟 |
| outbox_pending / unconfirmed / awaiting_delivery | 待完成、未确认发布、已确认待入库任务 |
| outbox_retrying / leased | 待完成中的多次领取、有效租约子集，可能重叠 |
| outbox_delivered | Outbox已完成标记累计数，不是用户已读数 |
| outbox_oldest_seconds / max_attempts | 最老待完成时长和最多领取次数 |
| mq_broker_up / mq_sample_age_seconds | 最近完整队列采样结果与最近成功采样年龄 |
| mq_queue_ready{queue="work|parking"} | 两个队列的可读取消息数 |
| mq_queue_consumers{queue="work|parking"} | 两个队列的消费者数 |

首次采样前数量和年龄为NaN；失败清除旧数量、状态置0，保留上次成功时刻供新鲜度判断。通知MQ关闭时mq_broker_up为NaN，不伪装为队列故障。指标抓取只读取内存快照，不在抓取请求内访问数据库或Broker。标签只使用固定work/parking，个人资料、通知正文、收件人ID、事件ID、Token均不进入指标。

快照对象原子替换，但一次Prometheus抓取各Gauge的读取仍可能跨越两次采样；不要把多条时序值当作数据库事务快照。Broker调用仍受底层连接/RPC超时影响；若某任务阻塞，另一线程可继续采样，45秒新鲜度规则能发现过期。

## 7条通知规则

| 规则 | 条件与持续时间 |
|---|---|
| CampusNotificationMetricsMissing | 应用可采集，但通知开关指标缺失/无效，1分钟 |
| CampusNotificationSamplingUnavailable | 数据库采样失败/缺失/成功快照年龄达到45秒，30秒 |
| CampusNotificationBacklog | 开启投递，存在等待至少60秒任务且数据新鲜，持续1分钟 |
| CampusNotificationDeliveryDisabled | 投递关闭且有待处理任务、数据新鲜，持续1分钟 |
| CampusNotificationBrokerUnavailable | MQ开启但完整队列采样失败/缺失/年龄达到45秒，30秒 |
| CampusNotificationParkingBacklog | parking可读取消息大于0且队列数据新鲜，30秒 |
| CampusNotificationNoConsumer | work消费者为0且队列数据新鲜，30秒 |

规则按job+instance匹配，不用一个健康实例掩盖另一个故障实例。应用采集失败时通知派生规则由up门控；Alertmanager的应用故障抑制范围新增notification组件。数据库故障时不继续使用旧积压发业务告警；队列失败或过期时不继续使用旧parking/消费者数字。

新增规则独立放在monitoring/notification-alerts.yml，原8条订单规则保持原文。monitoring-local.py启动时同时加载两份规则。verify-monitoring.py同时校验原规则、通知规则及两个Grafana面板。通知规则测试覆盖19种场景、133条告警断言与Pending/Firing表达式断言。

## Grafana与技术告警收件箱

新增10面板的仪表盘UID campus-notification-overview，地址 http://127.0.0.1:3000/d/campus-notification-overview 。原15面板订单仪表盘保留。仪表盘没有把未知数据填成0；开关状态与Broker采样分开展示，刷新15秒。

Alertmanager按job+instance+component分组，通知告警使用固定component=notification，与订单告警分开分组。接收器只为技术组件白名单增加notification，不放宽认证、Host/Origin、载荷大小、字段过滤或SQLite提交要求。首次Firing后还有既有10秒group_wait，恢复也按既有group_interval发送，不是规则一触发就立即到达。

本机更新需按归属核验后正常重启后端、Prometheus、Grafana和告警服务，保留原凭证、时序数据、Grafana数据库和SQLite告警历史。原运行配置先备份。全部仍只监听本机，未新增外部收件人、短信、手机推送或公网访问。

## 验收

新增8项指标快照单元测试、8项采样器单元测试、6项私有真实MySQL/RabbitMQ集成测试。新增单元测试加入quick明确名单，quick从73项/8类变为89项/10类。完整回归从1084项/71类变为1106项/74类。

故障演练仅在私有数据库、队列和独立端口进行；不停止真实MQ、不向真实业务库注入积压。以真实Prometheus规则和Alertmanager验证通知无消费者告警Firing，恢复监听器后收到Resolved；所有测试通知只进入临时技术收件箱。原业务数据、图片、管理员授权、迁移历史和凭证在更新后复核。

这仍是本机单节点监控，告警是技术运维通知，不是用户站内通知。尚无高可用或完整值班系统承诺。本课不改uni-app业务页面和Mac应用资源。
