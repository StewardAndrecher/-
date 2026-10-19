# 第74课：RabbitMQ待付款订单延迟关闭

新增Maven依赖spring-boot-starter-amqp，由Spring Boot4.1.1管理Spring AMQP4.1.1、RabbitMQ Java Client版本。运行环境使用RabbitMQ4.3.6与Erlang28；没有新增npm依赖，uni-app共享现有订单页面与接口。

## 数据流

下单事务：market_orders + order_events + order_timeout_outbox一起写入。事务回滚时三者一起回滚；事务中不连接MQ。

后台投递：短事务SELECT FOR UPDATE SKIP LOCKED抢占一条Outbox，使用30秒随机租约；离开事务后发送持久消息，等待correlated publisher confirm，并检查mandatory return。ack且未退回才标记published_at。失败保留任务，指数退避2～64秒重试；进程中断后可恢复到期租约。旧持有者不能覆盖新租约的结果。

RabbitMQ：durable direct exchange → quorum delay.queue（TTL900000毫秒）→ dead-letter exchange → quorum timeout.queue →消费。两个源队列均启用at-least-once dead lettering与reject-publish；持久parking.queue保留消费失败消息。当前是本机单节点，不代表已验收集群高可用。

消费：核验schema/eventId/orderId、messageId与数据库Outbox对应关系，再调用原OrderService.closeExpired。数据库事务返回后容器AUTO ACK；崩溃或重投时，订单状态条件与商品→订单锁顺序保证不会再次释放或重复记事件。暂时业务失败最多初次+2次重试，之后拒绝重入并转parking队列；不会无限热循环。

数据库始终检查PENDING_PAYMENT和数据库付款截止时间。已付款、已取消、已完成及重复/提前消息不会错误关闭。关闭后仍记录SYSTEM/TIMEOUT_CLOSED，现有详情/列表/时间线无需更改。MQ投递使用独立调度器，避免阻塞数据库扫描补偿；原30秒扫描继续启用。

## 时间与可靠性边界

延迟队列TTL从入队计时，默认15分钟；下单到实际入队的延迟、Broker故障或队列处理积压可能导致MQ晚到。数据库截止时间仍从下单创建时间起算，扫描补偿负责已到期订单，不承诺精确毫秒或固定15分钟准时关闭。测试使用200毫秒TTL加数据库回拨订单创建时间，不改变真实15分钟规则。

这是至少一次投递加幂等业务效果，不是端到端exactly-once。确认后进程崩溃、数据库未标记成功时可能再次投递；Quorum DLX也可能重投。单节点磁盘丢失不能保证Broker消息仍在；数据库订单扫描仍是补偿依据。Outbox表需保留并监控积压，parking队列需人工排查，当前没有自动死信重放接口。

历史待付款订单仍由扫描任务处理，不伪造旧消息。删除订单会级联删除其Outbox。当前没有跨服务分布式事务、真实支付或吞吐提升倍数证明。

## 本机运行

先安装官方RabbitMQ/Erlang，macOS可用Homebrew。脚本不会启用开机服务，项目节点默认campus_market@localhost，只管理核对归属的节点。

```sh
python3 scripts/rabbitmq-local.py start
python3 scripts/rabbitmq-local.py status
python3 scripts/rabbitmq-local.py configure-idea
```

AMQP127.0.0.1:5672，管理页http://127.0.0.1:15672，Erlang分布端口25672和epmd仅本机。专用vhost /campus-market；随机凭证、Erlang cookie和消息数据保存在data/rabbitmq并加入gitignore。configure-idea备份并更新本机IDEA运行配置，不输出密码；其他IDE/终端请使用自己的连接环境变量，不能将该目录上传。

停止MQ：`python3 scripts/rabbitmq-local.py stop`。Outbox保留待投递任务，数据库超时扫描继续工作；重启相同节点保留磁盘消息。脚本操作前校验pid和节点归属，拒绝停止未知实例。

ORDER_MQ_ENABLED默认false；配置连接后启用true。环境变量RABBITMQ_HOST、RABBITMQ_PORT、RABBITMQ_USERNAME、RABBITMQ_PASSWORD、RABBITMQ_VHOST控制连接。ORDER_MQ_PREFIX默认campus.order.v1，队列参数不可原地随意更改，变更TTL/拓扑应使用新版本前缀并规划旧消息处理。该开发节点配置不能直接用于公网部署。

## 迁移

停旧后端，备份原库后执行一次sql/lesson74-order-timeout-outbox.sql，再启用新后端。脚本仅新增Outbox表，不改写历史订单；原扫描任务须保留。接口响应和原15分钟规则保持。

## 排查

```sql
SELECT COUNT(*) AS pending, MIN(created_at) AS oldest_pending
FROM order_timeout_outbox WHERE published_at IS NULL;
SELECT last_error,COUNT(*) FROM order_timeout_outbox
WHERE published_at IS NULL GROUP BY last_error;
```

管理页查看delay/timeout/parking队列、ready与unacked，以及连接/消费者状态。不能仅根据published_at判定订单已消费或完成；它只表示Broker确认并可路由。

## 官方参考

- [Spring Boot AMQP](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)
- [Spring AMQP Confirm与Return](https://docs.spring.io/spring-amqp/reference/amqp/template.html)
- [RabbitMQ Quorum与至少一次死信](https://www.rabbitmq.com/docs/quorum-queues)
- [RabbitMQ TTL](https://www.rabbitmq.com/docs/ttl)

第75课继续增加指标与Prometheus采集，见[MQ监控](MQ_MONITORING.md)。

第79课继续使用已有事件编号关联异步阶段，见[MQ关联日志](MQ_LOGGING.md)。
