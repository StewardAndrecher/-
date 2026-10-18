# 第71课：卖家交付与买家收货

本项目仍为教学模拟交易，没有真实扣款、资金托管、物流或资金结算。本课只记录双方明确确认的交易进度，不证明线下商品实际交接，也不做自动收货。

## 状态机

PENDING_PAYMENT → PAID → DELIVERED → COMPLETED。

PAID显示已模拟付款、待交付；卖家POST /api/orders/{id}/deliver确认已把商品交给买家，进入DELIVERED。买家核对商品后POST /api/orders/{id}/complete确认收货，进入COMPLETED。买家不能代卖家交付，卖家不能代买家收货；未付款、已取消、超时订单不能推进；PAID不能跳过交付直接完成。

CANCELLED、CLOSED_TIMEOUT和COMPLETED为终态。已模拟付款、已交付、已完成订单均不能使用未付款取消接口；本课没有退款、争议、退货、自动完成或自动释放成交商品。

## 数据库与迁移

执行sql/lesson71-order-fulfillment.sql，单条ALTER新增delivered_at、completed_at两个DATETIME(6)字段，扩展状态/生命周期CHECK和有效商品生成列。历史四种状态的原字段和自增值保持不变，新时间字段NULL。已完成仍保留active_product_id，避免同一商品再次成交；商品保持SOLD。

CHECK约束确保：PAID没有交付/完成时间；DELIVERED有不早于付款时间的交付时间且没有完成时间；COMPLETED同时有按序的付款、交付、完成时间，付款类型仍必须SIMULATED。未付款/取消/超时不得带交付或完成时间。比较前明确非NULL，避免SQL UNKNOWN绕过CHECK。迁移先于新后端启动，已经执行后不要重复执行ALTER。

## 事务、权限与重试

deliver/complete为public事务入口，经Spring代理调用，再进入共享private progress方法。保持商品→订单的加锁顺序；会话确认角色，锁定后重新检查角色与状态；SQL WHERE再次限定order_id、seller_id/buyer_id和原状态。不接收前端身份、状态、时间或价格。越权返回404；未登录401；不允许的状态409；响应no-store。

同一次阶段确认没有新的业务载荷，通过订单ID和已达成的状态实现幂等。重复交付返回原DELIVERED；订单已完成后再交付返回当前COMPLETED；重复收货返回原完成时间。首次replayed=false，重放true，不重新写时间。

原创建requestId和模拟付款的迟到重试同样返回当前DELIVERED/COMPLETED。模拟付款开关关闭后不能调用模拟付款，但已经付款的订单仍可以正常交付、收货。

交付/完成只修改私有订单数据，商品SOLD状态、version=付款后的版本和快照都不变，因此不需要递增商品版本或删除商品正文缓存。订单GET/list保持只读且no-store，超时扫描仍只处理PENDING_PAYMENT。提交失败回滚对应阶段和时间。

## 前端

详情增加卖家确认交付时间、买家确认收货时间。只有PAID订单的卖家显示“确认已交付”；只有DELIVERED订单的买家显示“确认收货”。二次确认展示商品快照标题和模拟交易提示；收货确认明确要求已收到并核对商品。

交付、收货、付款、取消、刷新共用busy状态。账号变化、隐藏/卸载、令牌变化后旧回调不更新页面；响应还核对商品ID、双方ID、标题和金额快照。结果未知时清掉旧详情，提示重新加载，绝不自动重下单或自动确认收货。API允许迟到交付重放返回COMPLETED。

六状态DTO严格校验时间和SIMULATED类型。DELIVERED/COMPLETED标签明确说明模拟交易；收货可发生在原15分钟付款期限之后，该期限只约束付款。

## 验收

写入验收只使用明确命名的隔离数据库，真实库只迁移空订单表。专项测试覆盖角色、跳步、时间、约束、重复、并发、回滚、旧提交重放和已完成占用。Web点击验收使用隔离买卖账号；实际Mac做资源打包与入口检查。微信本课为编译，App仅资源构建，未生成APK/IPA或进行手机验收。
