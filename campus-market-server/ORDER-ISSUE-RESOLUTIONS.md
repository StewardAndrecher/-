# 第105课：管理员处理交易反馈与可靠结果通知

管理中心新增交易问题工单列表和详情。OPEN/RESOLVED/ALL在SQL中筛选分页，每页最多20条，越界收缩到末页；按提交时间、订单编号、提交人编号倒序。详情只显示选中的一条反馈及订单标题/金额快照，不合并双方私密说明。

GET /api/admin/order-issues?status=OPEN&page=1&size=10
GET /api/admin/order-issues/{orderId}/{applicantId}
POST /api/admin/order-issues/{orderId}/{applicantId}/resolve

处理请求decision=ANSWERED或DISMISSED，explanation规范化后10至1000个Unicode字符，requestId为规范UUID。所有接口由现有admin拦截器和服务实时校验权限；普通用户403，匿名401。写入锁反馈行后重新校验会话与管理员授权，禁止处理自己作为买家或卖家参与的订单。

新增order_issue_resolutions，只追加一条不可覆盖的结果，主键(order_id,applicant_id)，外键引用原反馈；保存resolver_id、request_id、结果、说明及首次时间。原order_issues内容和所有旧表不改。V105仅新增空表，旧32份迁移冻结不变，新库33份迁移、25张表（含历史）。

同一管理员、同一UUID、相同规范化内容返回200/replayed=true；其他处理、不同内容或跨工单复用同一管理员请求键409。双方反馈是两条独立工单，可分别处理。并发处理同一反馈由反馈行锁与数据库主键串行化，最多一条结果。

处理记录与通知Outbox在同一MySQL事务中提交，任一写入失败一起回滚。复用既有通知队列与ORDER分类，无新依赖、MQ资源或权限。只提醒该反馈提交人；通知正文不含原反馈、处理说明、处理决定或管理员身份，资源指向订单详情。源键ORDER_ISSUE:订单:提交人:RESOLVED。MQ载荷仍只有schema/eventId。至少一次投递依靠现有消费者事务及唯一event_id实现收件幂等，不宣称网络传输恰好一次。

原本人记录接口扩展issue.resolution=null或{decision,explanation,createdAt}。用户只读到自己的处理结果，不返回管理员编号、处理请求键或另一方说明。处理不改变订单、商品、评价或付款，不执行退款。管理页成功提示已保存且已安排提醒，不声称已送达。

前端严格检查工单复合编号、最小字段、状态、日期、原文不变以及回执匹配。处理时订单允许正常从PAID推进到DELIVERED/COMPLETED。保存前确认，双击保护；账号/令牌/目标切换或隐藏后旧结果失效，超时或409/403后清掉旧表单资格，先重新读取，不自动重试。

新增专项覆盖实时权限、禁止自处理、重复请求、不同管理员并发、等待锁期间撤权/退出、原文不可覆盖、结果及通知的事务回滚；真实私有RabbitMQ验证持久化、最小消息、重复消费、不可路由恢复、失败停放和重放不重复通知。真实业务库仅新增空表及V105历史，写入验收只在临时隔离环境。


第107课：新增反馈处理概览与age=ALL/OVER_24H/OVER_72H待处理筛选。列表回显age及sampledAt；时长筛选最早优先，其余保持最新优先。原处理写入/通知契约不变；详见ISSUE-OPERATIONS.md。
