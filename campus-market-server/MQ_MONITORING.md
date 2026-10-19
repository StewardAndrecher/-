# 第75课：MQ监控指标与Prometheus采集

本课在第74课消息投递、消费和Outbox机制上增加可观测性。新增Maven依赖`io.micrometer:micrometer-registry-prometheus`，版本由Spring Boot管理；本机采集使用Prometheus3.15.0。没有新增npm包、数据库迁移或前端页面变更。

## 三类指标

- Counter累计次数：发送确认/失败；消费关闭/无须变更/拒绝/失败。消费计数是每次调用尝试，不是去重后的订单数量；重试和重投都会再次计数。
- Gauge当前快照：Outbox待发、已尝试、有效租约、最老待发时长；三个队列的ready消息数和消费者数；采样状态与快照年龄。
- Timer处理耗时：发送等待确认和消费事务处理的次数、总时长及窗口最大值。未开启直方图，不提供P99。

指标标签只使用固定result和逻辑queue名称，不使用订单ID、eventId、用户名、Token、异常文本或消息正文。Counter保存在当前应用进程内，重启后归零；应使用rate/increase观察变化，不能直接把它当数据库审计账本。

## 后台采样与未知值

独立mqMonitorTaskScheduler每5秒执行一次：一条只读聚合SQL统计未确认Outbox，然后分别对三个RabbitMQ队列做被动查询。这里的ready不包括已投递但尚未ACK的消息，不将它宣传为全部在途消息数。

数据库与Broker分别记录成功状态；一方失败不阻断另一方采样。失败时相关数值设置NaN，保留上次成功时间，让快照年龄继续增长。NaN表示未知，不能当作0。采样线程卡住时年龄也会增长，因此告警应同时检查poll/broker状态和样本年龄。

HTTP采集只读取内存中的指标，不在请求中查询MySQL或RabbitMQ。监控、投递、原数据库扫描使用独立调度线程，采集故障不改变原业务事务与readiness定义。

## 受保护采集接口

```http
GET /api/ops/metrics
Authorization: Bearer <独立MONITORING_TOKEN>
```

默认MONITORING_ENABLED=false；启用时必须配置随机64位十六进制Token，否则启动失败。这里使用独立运维凭证，普通用户会话不能采集。匿名、错误、重复Authorization返回401；有效凭证下携带Origin返回403，不支持把凭证放查询参数；响应no-store。GET/HEAD只读，不提供修改日志级别、环境变量、关机或重投接口。

该接口直接使用PrometheusMeterRegistry导出文本，既有Actuator仍只开放health，原/api/health/liveness和/api/health/readiness保持。没有把通用Actuator metrics/env/prometheus端点额外公开。

## 本机运行

安装Prometheus（macOS可用官方Homebrew）。在后端项目目录执行：

```sh
python3 scripts/monitoring-local.py init
python3 scripts/monitoring-local.py configure-idea
# 在IDEA启动/重启后端，使运行配置中的独立凭证生效。
python3 scripts/monitoring-local.py start
python3 scripts/monitoring-local.py status
python3 scripts/monitoring-local.py query
```

默认界面http://127.0.0.1:9090，只监听本机；每5秒采集127.0.0.1:8080/api/ops/metrics。随机Token通过credentials_file提供给采集器，不放URL或公开配置。Token、IDEA旧配置备份、日志和时序数据在被Git忽略的data/monitoring私有目录。

configure-idea要求已有CampusMarketServerApplication Spring Boot运行配置。脚本仅管理核对归属的项目Prometheus进程，拒绝停止其他占用端口的程序；不会启用brew services或开机自启。其他安装位置可设PROMETHEUS_BIN；隔离验收可设MONITORING_DATA_DIR及--port/--api-port，不修改真实业务目录。

时序块保留目标为7天/256MB，WAL等开销不受这一块大小目标严格限制，不是进程内存或整个目录的硬上限。开发监控界面只供本机；远程部署需要另行配置网络隔离、TLS和运维访问管理。

停止采集：`python3 scripts/monitoring-local.py stop`；停止采集不影响业务和RabbitMQ，磁盘时序数据保留。

## 常用PromQL

见[scripts/monitoring-queries.promql](scripts/monitoring-queries.promql)。

| 表达式 | 含义 |
| --- | --- |
| `up{job="campus-market"}` | Prometheus是否成功抓取应用；不等于MQ业务正常 |
| `campus_order_mq_broker_up` | 最近一次三个队列查询是否全部成功；不是集群所有节点健康证明 |
| `campus_order_outbox_pending` | 尚未获得确认并标记成功的任务数 |
| `campus_order_outbox_oldest_seconds` | 最老待发任务已等待多久 |
| `campus_order_mq_queue_ready{queue="parking"}` | 消费失败保留队列中可读取的消息数 |
| `campus_order_mq_queue_consumers{queue="timeout"}` | 超时队列消费者数量 |

正常15分钟延迟期间delay队列存在消息是预期行为，不能直接判故障。排查可先看采集up，再看Broker/数据库采样up及年龄，然后看Outbox等待时长、parking积压、timeout消费者与失败速率。当前提供查询和人工诊断，没有接入Alertmanager、通知、Grafana或自动死信重放。

## 验收

新增26项单元测试及8项真实MySQL/RabbitMQ集成测试；另做隔离HTTP＋真实Prometheus故障验收。关闭隔离数据库扫描后停止MQ，验证HTTP201下单、Outbox pending1、发布失败次数增长、Broker0和队列NaN；此时应用readiness和采集up仍1。恢复MQ后验证pending0、确认次数增长、closed1及仅一次系统超时记录。

隔离验收使用200毫秒TTL并仅调整测试订单created_at；真实规则仍15分钟。当前RabbitMQ是本机单节点，没有集群高可用、吞吐提升或精确一次投递声明。

## 官方资料

- [Spring Boot指标与注册器](https://docs.spring.io/spring-boot/reference/actuator/metrics.html)
- [Micrometer Prometheus导出](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)
- [Prometheus采集与authorization配置](https://prometheus.io/docs/prometheus/latest/configuration/configuration/)

第76课继续加入Grafana仪表盘和规则，见[仪表盘与告警](MQ_DASHBOARD.md)。
