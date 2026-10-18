# 第70课：模拟付款与超时关闭

仅用于教学演示：没有调用微信/支付宝，不创建扣款、余额或退款记录。默认开发配置启用模拟付款，正式部署必须设置ORDER_SIMULATED_PAYMENT_ENABLED=false。本课新增Spring内置定时任务，无新Maven/npm依赖。

## 状态机

PENDING_PAYMENT → PAID（已模拟付款，待交付）/ CANCELLED（买家取消）/ CLOSED_TIMEOUT（超时关闭）。第71课已将PAID扩展为待交付阶段，随后卖家交付DELIVERED、买家收货COMPLETED；CANCELLED/CLOSED_TIMEOUT/COMPLETED为终态。PAID在付款事务将商品设为SOLD，之后交付/收货保持已售出。完整规则见[ORDER_FULFILLMENT.md](ORDER_FULFILLMENT.md)；尚无退款。

订单创建后15分钟到期。payment_due_at为created_at + INTERVAL 15 MINUTE的数据库生成列，历史订单迁移后也按原创建时间计算，迁移不重置时间。时间按现有数据库本地DATETIME语义处理；前端不把设备时钟当作付款资格依据。

## 数据库迁移

先执行sql/lesson70-order-lifecycle.sql，再启动本课后端。单条ALTER新增生成截止时间、模拟付款时间、超时关闭时间、付款类型与扫描索引；扩展CHECK约束与有效订单生成列。保留原订单ID、快照、创建/取消时间、请求标识和自增值。该脚本不是重复运行的脚本；已执行后不要重跑。MySQL原子DDL是单条DDL的提交保证，不代表整个应用部署自动回滚。

CHECK约束要求已付款必须有paid_at、payment_kind=SIMULATED且paid_at < payment_due_at；超时关闭必须closed_at >= payment_due_at，不能带付款或取消时间。显式检查非NULL，避免SQL UNKNOWN使CHECK放行。

## 事务、并发与时钟

所有写操作仍按商品→订单顺序加锁。拿到锁后，用新SQL语句的CURRENT_TIMESTAMP(6)判断资格；付款UPDATE条件为截止时间 > 数据库当前时间，超时UPDATE条件为截止时间 <= 数据库当前时间，相互排斥。到期瞬间也不能付款。支付只是一个受保护的模拟状态转换。

POST /api/orders/{id}/simulate-payment仅买家可调用。已PAID时返回200/replayed=true，不改变原时间、不重复更新商品版本。已取消409；卖家/第三方404；未登录401；开关关闭503。到期请求先提交CLOSED_TIMEOUT事务，再在Controller返回409；不能在事务内抛异常导致刚做的关闭被回滚。

买家在到期后请求取消，返回200，权威状态为CLOSED_TIMEOUT；不会伪装成手动取消。PAID订单不可取消。原创建requestId无论订单进入哪个终态都恢复原结果，不创建新订单。

超时关闭释放有效订单唯一索引、商品version+1并在提交后清除旧版本Redis缓存。模拟付款同样只在提交后清缓存，且商品SOLD/version+1与订单PAID同一事务提交。Redis并不承担订单定时或唯一性；关闭缓存不会改变状态机正确性。

## 定时补偿

OrderTimeoutScheduler每批完成后等待30秒（ORDER_TIMEOUT_SWEEP_DELAY_MS），调用OrderTimeoutSweep。每批按截止时间/ID最多读取100个到期候选，用idx_order_timeout扫描。Sweep逐笔经OrderService事务代理调用closeExpired；一笔失败回滚并记录日志，继续其他候选，失败项下批重试。支持重复执行和多个应用实例竞争，数据库状态与行锁确保只释放一次。

ORDER_TIMEOUT_SWEEP_ENABLED=false关闭扫描。服务停机期间不会运行，重启后继续扫描数据库中的到期订单。关闭不是精确到秒的承诺；会受扫描间隔、批次积压与数据库可用性影响。但付款接口始终独立核对数据库截止时间，扫描延迟不能让到期订单继续付款。

没有公开手动批量关闭接口，没有Redis过期事件依赖。订单GET为只读，不偷偷触发写入。

## 前端

订单详情显示截止时间、模拟付款/关闭时间和清晰的“不扣款”文案；只有买家待付款订单有模拟按钮。二次确认展示订单快照金额，不提交价格。模拟付款、取消和刷新共用busy状态；隐藏/卸载/切换账号后忽略旧回调。结果未知或超时错误先清掉旧数据，要求重新加载，绝不自动重新下单。

前端严格校验四种状态及其时间/付款类型关系，状态为PAID也必须明确SIMULATED。截止时间由服务器显示，刷新读取权威状态；本课不做基于设备时间的倒计时。

## 验收边界

写入测试仅使用明确命名的隔离数据库。真实库只迁移订单表，不对真实商品模拟付款或制造超时。实际微信为编译验收，App仅资源构建，未生成APK/IPA、未做手机验收。
