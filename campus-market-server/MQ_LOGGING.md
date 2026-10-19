# 第79课：从HTTP请求追踪到MQ异步处理

第78课为同步HTTP请求增加请求编号。本课利用已有Outbox事件编号，将下单、事务完成、消息发布、Outbox更新和消费结果关联起来，不增加消息字段、业务表或软件包。

## 三种编号必须分清

|编号|格式与来源|用途|
|---|---|---|
|HTTP请求编号|服务端生成32位小写十六进制，响应头X-Request-Id|一次HTTP调用的日志关联，重试时通常不同|
|下单请求体requestId|客户端提供36位UUID|下单幂等键，重试同一操作时复用|
|Outbox事件eventId|数据库任务中的36位UUID|同一超时任务的消息身份，投递重试和重复消费时保持不变|

`originRequestId`从HTTP的MDC读取，绝不是下单JSON中的幂等requestId。本课不将原HTTP编号复制到MQ消息头，也不在消费者线程伪造HTTP上下文。

## 关联方法

先从HTTP响应获得编号，再在后端目录搜索：

```sh
rg -F '替换为HTTP响应中的32位请求编号' data/logs/campus-market.log
```

新订单事务提交后，会出现同一HTTP编号的日志：

```text
[requestId:HTTP编号] [eventId:事件UUID] ... order_timeout stage=outbox_committed originRequestId=HTTP编号 orderId=订单编号
```

取出eventId后继续搜索：

```sh
rg -F '替换为事件UUID' data/logs/campus-market.log
```

异步线程的HTTP requestId通常为none，eventId用于跨阶段关联。幂等重放的HTTP请求不会新增Outbox，因此不会再次产生创建日志；可根据返回的orderId查原任务：

```sql
SELECT event_id,order_id,attempts,published_at,last_error
FROM order_timeout_outbox
WHERE order_id = 这里替换为实际订单编号;
```

## 各阶段是什么意思

|日志阶段/结果|能说明什么|不能说明什么|
|---|---|---|
|outbox_committed|数据库订单事务已提交，包含Outbox任务|不能说明已向MQ发送|
|publish confirmed|Broker已确认且未mandatory return|不能说明数据库published_at已更新，也不能说明订单已关闭|
|publish failed|本次发布没有得到可路由确认|不能断言Broker绝对未收到，仍可能发生重复投递|
|outbox_update published|当前租约对应的数据库任务成功标记已发布|不能说明消费者已完成处理|
|outbox_update lease_lost|发送虽确认，但本次条件更新未生效|不能冒充数据库标记成功或推断唯一失败原因|
|outbox_update retry_scheduled|当前租约成功安排数据库退避重试|不是立即执行下一次发送|
|outbox_update retry_lease_lost|安排重试的条件更新未生效|不能声称本次已安排重试或推断唯一失败原因|
|outbox_update state_update_failed|任务状态写入发生异常|仍需查看数据库、租约到期及补偿情况|
|consume closed|数据库超时关闭事务已返回成功|不能单凭此日志证明Broker已经收到ACK|
|consume noop|本次未改变订单，例如重复/提前/已付款消息|不是另一次关单|
|consume rejected|格式或来源验证失败，抛出拒绝异常|不直接宣称消息已进入parking；要查队列或指标|
|consume failed|本次来源查询或业务处理抛出异常|仍可能被容器重试或最终转入parking|

`lease_lost`是条件更新未生效的结果标签；可能是租约失效、任务已发布或记录已移除，并非额外查询后确定了唯一原因。

发布、消费和数据库更新可能在不同线程交错；不要把日志行顺序当成全局严格时序。确认、状态更新和消费三个结果需要分别查看。消费日志按每次执行记录，重试会多次出现，不能当成唯一订单数量。

## 关键实现

### 数据库事务完成后才记录提交成功

OrderRepository生成原有eventId并写入Outbox后，注册事务同步回调。回调只在`afterCompletion(COMMITTED)`时输出日志，回滚不输出`outbox_committed`。

选择完成回调还有一个原因：Spring对afterCompletion回调异常会记录而不传播给事务调用方，避免单纯日志故障把已提交的操作变成失败响应。回调仅记录日志，不发MQ、不写数据库。未处于受管理事务时不冒充“事务已提交”。

### eventId有明确作用域

OrderMqLog.EventScope只临时设置eventId，结束时恢复先前值；支持嵌套，重复close不重复恢复。发送、投递器和消费的finally路径均覆盖，防止线程复用串号。

消费端先清空当前作用域eventId，再校验schema、消息UUID、messageId和数据库Outbox对应关系。只有来源验证通过后才绑定可信eventId。非法消息只记录固定原因`malformed`或`source_mismatch`，数据库来源查询失败为`source_check_failed`，不把攻击者提供的消息头当可信日志身份。

### 不改变原有可靠性机制

消息仍为schema=1，仅schema/eventId/orderId三个字段；不新增请求编号、令牌或业务正文。确认、mandatory return、30秒租约、数据库退避、消费重试、幂等关闭与扫描补偿保持原机制。

日志模式增加：

```properties
logging.pattern.level=%5p [requestId:%X{requestId:-none}] [eventId:%X{eventId:-none}]
```

eventId只进入日志，不添加到Prometheus指标标签，避免高基数。新增阶段日志不记录消息正文、认证头、租约Token或异常原文；原有框架/第三方异常日志未整体重写，全部日志仍属于私有运行数据。

## 新增或修改文件

- 新增OrderMqLog.java：事务完成日志、eventId作用域和有限阶段输出。
- 修改OrderRepository.java：保留原插入逻辑，注册事务完成日志。
- 修改OrderTimeoutSender/Dispatcher/Consumer：记录发送、数据库租约更新与消费结果。
- 修改application.properties：输出eventId。
- 新增OrderMqLogTest.java的18项检查；OrderMqIntegrationTest新增2项真实事务提交/回滚检查。
- 本文及MQ.md、REQUEST_LOGGING.md增加本课入口。

没有新增软件、Maven/npm/pip依赖、SQL迁移或前端/桌面端源码改动。

## 验收与边界

新增20项检查，连同第78课803项回归完整运行，目标823项。隔离MySQL、MQ和后端真实演练：

1. 停止测试MQ后通过HTTP下单201；响应编号可定位到已提交Outbox事件。
2. 同一事件记录发布失败和安排重试。
3. 恢复测试MQ，自动重试得到确认、更新Outbox、由真正监听器关闭到期订单。
4. 重投同一消息，消费结果NOOP，订单仍只新增一次超时关闭事件。
5. 投递格式错误及来源不匹配的测试消息，拒绝日志不采用其eventId，最终两个消息进入测试parking队列。
6. 注销测试会话，停止隔离进程并清理临时数据；实际业务库保持不变。

测试TTL可加速为200ms，并只回溯测试订单时间；实际TTL仍900000ms。两种错误消息可能各执行多次后进入parking，符合原容器有限重试机制。

这是一种日志关联方案，不是OpenTelemetry跨服务分布式追踪、审计账本或消息重放功能。HTTP来源编号仅保存在日志中；日志缺失、轮转或过期后，不能承诺永久找回原HTTP编号。数据库Outbox及订单状态仍是业务判断依据；实际Broker ACK还需结合容器与队列状态判断。

官方资料：[Spring事务完成回调](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)、[Logback MDC与线程](https://logback.qos.ch/manual/mdc.html)。
