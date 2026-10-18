# 第69课：正式订单创建、占用与取消

新增一张market_orders表；不改原有十张业务表结构、商品状态枚举或购买意向历史。没有新增Maven依赖。

商品仍可被公开浏览，商品状态ON_SALE与订单占用分别管理。待付款订单占用商品，其他买家不能重复下单，卖家不能修改内容/切换状态，也不接受新的购买意向或接受旧待沟通意向。已有意向记录和沟通继续保留。买家取消订单释放占用；尚未接入付款、自动超时和收货流程。

## 数据与约束

- 标题、分价、商品版本、双方昵称在下单时保存快照；价格、卖家和买家均由后端确定。
- active_product_id由订单状态生成：非CANCELLED时等于product_id，取消后为NULL。唯一索引限制每件商品最多一张有效订单；多个取消记录可以保留。
- (buyer_id,request_id)唯一，防止同一买家的提交标识重复使用。相同商品、相同版本、相同标识重试返回原订单，即使原订单已经取消；不同商品或版本复用标识返回409。
- 状态PENDING_PAYMENT/CANCELLED，取消时间与状态受数据库CHECK约束，买家与卖家必须不同。

## 事务与并发

创建采用READ_COMMITTED事务：校验会话/请求→按主键锁商品FOR UPDATE→恢复幂等结果→检查状态/版本/占用→插入订单→商品version+1→事务提交后失效旧缓存。

取消始终按商品→订单顺序加锁；仅买家可取消，重复取消返回原结果，不重复递增商品版本。取消状态变更、释放唯一占用和版本更新在同一个事务提交，任何一步失败整体回滚。

卖家编辑/改状态也先锁商品，再用SELECT ... FOR UPDATE检查占用，使用当前读，避免默认REPEATABLE_READ事务中较早的会话查询建立了旧快照而漏看新订单。购买意向创建/接受沿用相同商品锁顺序。

商品版本在占用与释放时递增，Redis旧版本键只在事务提交后删除。即使旧读回填也会被当前版本选择隔离。创建时为取消预留一次版本递增空间，防止版本到顶后产生无法取消的订单。

订单读取仅允许买卖双方；列表在SQL中按当前用户和BUYER/SELLER角色过滤，分页最多20条；其他用户读取/取消返回404。公开order-state只透露可下单与否，不透露第三方订单ID。所有订单响应no-store。

## 接口

- POST /api/products/{id}/orders：productVersion、requestId；首次201，重放200。
- GET /api/products/{id}/order-state：available、myOrderId、message；订单ID仅参与者可见。
- GET /api/orders/{id}：参与者详情。
- GET /api/orders?role=BUYER&page=1&size=10：当前用户分页。
- POST /api/orders/{id}/cancel：买家取消，幂等。

## 迁移与验收

只执行sql/lesson69-market-orders.sql新增表。开发验收使用专用临时库，订单专项39项，覆盖快照、并发、幂等、取消、权限、SQL唯一约束、回滚、RR旧快照保护、意向与缓存一致性。

实际业务库只创建空订单表，不在原有商品上做写入验收；原十张业务表和图片前后核对。没有付款动作、没有自动取消到期任务；后续课程继续扩展订单生命周期。登录保护与商品缓存继续各自使用独立Redis实例。

## 第70课扩展

正式订单现已增加模拟付款PAID与超时关闭CLOSED_TIMEOUT；原文的“尚未付款/超时”描述是第69课边界。最新规则、迁移及开关以[ORDER_LIFECYCLE.md](ORDER_LIFECYCLE.md)为准。有效占用为PENDING_PAYMENT/PAID，取消或超时后释放；付款后商品同事务标记SOLD。

## 第71课扩展

已加入卖家交付DELIVERED和买家收货COMPLETED。最新完整状态机及迁移见[ORDER_FULFILLMENT.md](ORDER_FULFILLMENT.md)。已完成仍保留成交商品唯一约束，商品保持SOLD。

## 第72课扩展

订单列表现支持按状态筛选并返回当前角色全量统计，详见[ORDER_QUERY.md](ORDER_QUERY.md)。无新表、无迁移，金额仅统计已完成模拟交易快照。


## 第73课：订单进度时间线

新增参与者私有的 `GET /api/orders/{id}/timeline`。状态变化与操作记录同事务保存，重复操作不重复记；历史订单仅补状态快照。详见 ORDER_EVENTS.md。


## 第74课：MQ超时提醒

新增RabbitMQ、事务Outbox及TTL/DLX延迟关闭。订单规则与接口不变，原扫描补偿保留。详见 [MQ.md](MQ.md)。
