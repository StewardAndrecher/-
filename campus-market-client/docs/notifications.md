# 第92课：站内通知与RabbitMQ可靠投递

新增软件包0：复用已有Spring AMQP、RabbitMQ、MySQL、会话认证和uni-app。本课提供站内列表、未读数、单条已读与安全业务跳转，不涉及短信、邮件、微信订阅消息或WebSocket推送。进入页面或手动刷新后更新，MQ到达和页面显示都不承诺实时。

## 来源与收件人

只为升级后的新业务转换生成通知，不回填历史：
- 订单创建、模拟付款、交接、完成、取消、超时关闭，每次给买卖双方各一条；模拟付款文案明确教学演示、不扣款。
- 商品BLOCK/APPEAL/RELEASE给商品卖家一条；无登记卖家的历史商品不造收件人。解除限制仍保持下架，通知提醒手动确认上架。
- 举报结案给举报人一条。举报导致下架时卖家收到商品处理消息，举报人收到工单结果，卖家消息不包含举报人身份或原举报说明。

通知任务通过NotificationEvents在原业务事务写入notification_outbox；写入失败则业务回滚，事务中不请求MQ。既有业务重放在写事件之前返回，不生成重复通知。唯一(source_key,recipient_id)保护同一来源只生成一次，意外冲突拒绝而非覆盖原内容。

## 数据流与恢复

短事务SELECT FOR UPDATE SKIP LOCKED抢占任务，随机租约30秒。事务外发送持久消息，只带schema和eventId，不在Broker载荷中放个人资料、举报文字或通知正文。correlated confirm ack且mandatory未退回才记published_at。发送失败用数据库时间指数退避2～64秒；旧租约不能覆盖新持有者的发布/失败结果。

消费端校验体积、JSON字段、schema、UUID和messageId一致，从数据库source记录读取收件人及内容，不信任消息头中的目标用户。消费事务先锁Outbox，插入user_notifications并置delivered_at，事务代理返回后容器ACK；唯一event_id及源行锁使重复投递只有一条通知，也不会把已读改回未读。

如果发布确认后30秒仍未落入通知箱，Outbox允许重新投递；进程崩溃或Broker丢失已确认消息时可从数据库恢复。delivered_at才表示通知已落库，published_at仅表示该次Broker可路由确认。任务一旦delivered不再重发。

专用durable direct exchange、quorum工作队列与parking队列，队列长度上限100000、reject-publish；工作队列通过at-least-once DLX进入parking。消费初次+2次重试后拒绝重入，避免热循环。持续失败的有效任务仍保留在Outbox，之后按投递规则重新尝试；parking保留失败副本供排查，不自动清空。未匹配数据库来源的毒消息只进parking，不生成通知。

这是至少一次投递配合幂等落库，不是端到端exactly-once。数据库与Broker均为本机单节点，不声称集群高可用。Outbox和Inbox目前不自动清理；删除源Outbox会级联删通知，维护前必须制定保留策略，不能直接删除全部已发布任务。

## 配置与本机权限

market.notifications.mq.enabled来自NOTIFICATION_MQ_ENABLED，未指定时沿用ORDER_MQ_ENABLED，默认false。可以独立关闭通知MQ，未来业务仍写任务，重启开启后继续投递。前缀NOTIFICATION_MQ_PREFIX默认campus.notification.v1，与原campus.order.v1队列分离；不变更原队列TTL和拓扑。

scripts/rabbitmq-local.py configure-notifications先核验项目节点归属、本机账号campus_mq与专用/campus-market，再备份并将既有订单命名空间权限扩展为^campus[.](order|notification)[.].*。遇到未知自定义权限拒绝覆盖。不会修改连接密码、IDEA配置、监听地址或其他账号/vhost。新建节点与隔离测试节点默认仅允许这两个项目命名空间。

## API与页面

- GET /api/notifications?page=1&size=10&status=ALL：当前用户通知，ALL/UNREAD，最多20件，按通知箱ID倒序。
- GET /api/notifications/unread-count：当前用户未读数。
- POST /api/notifications/{id}/read：当前用户单条已读，重复保留首次read_at；他人通知404，管理员也不覆盖收件人检查。

新增成功响应no-store，未登录401。没有客户端指定收件人接口，没有批量全已读或删除接口。列表和未读数是独立请求，不能视为跨请求原子快照。通知正文保留事件时内容，进入业务详情仍再次执行原业务鉴权。

首页新增站内通知入口和可刷新未读数，通知中心新增1路由，共24路由。点击“查看详情（标记已读）”先等待服务器已读确认，再按ORDER/REPORT/MODERATION三种白名单映射本地路由，不接受服务器任意URL。隐藏、卸载、账号/Token变化立即清数据，旧回调不能跳转到另一账号的业务。已读结果未知先清旧列表并要求刷新；重复标记天然幂等，不自动重新发业务操作。

## 验收与边界

新增35项通知业务/数据库集成测试、10项真实MQ集成测试和1项API契约测试，以及20项前端协议/竞争测试。通知、订单、举报与审核写验收使用私有临时账号、库与队列。真实库仅新增两个空表，不补发历史通知、不制造测试订单/举报。V92两条CREATE TABLE不保证整份DDL事务回滚；若失败必须核对现场，不能自动repair/clean。原29份历史迁移不改，新库30份迁移、21张业务表；真实原库仅在74 BASELINE+87+89+90+91之后追加92，历史6条。

## 只读排查

SELECT COUNT(*) AS pending, MIN(created_at) AS oldest FROM notification_outbox WHERE delivered_at IS NULL;
SELECT published_at,delivered_at,attempts,next_attempt_at,last_error FROM notification_outbox WHERE delivered_at IS NULL ORDER BY created_at LIMIT 20;

本课未新增通知专用Prometheus指标或告警面板；原订单MQ监控保持。通知运维可先看SQL积压与parking队列，后续再补专用告警。App仅资源、小程序仅编译，不能代替手机运行验收。
