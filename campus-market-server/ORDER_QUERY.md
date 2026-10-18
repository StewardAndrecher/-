# 第72课：订单状态筛选与交易统计

没有新增依赖、数据库表或迁移。沿用已有买家/卖家索引，新增的是只读查询能力，不改变订单状态机。

## 接口与范围

GET /api/orders?role=BUYER&status=COMPLETED&page=1&size=10。

role仅BUYER/SELLER；status默认ALL，允许ALL、PENDING_PAYMENT、PAID、DELIVERED、COMPLETED、CANCELLED、CLOSED_TIMEOUT。原有分页限制不变，每页最多20条，超出最后页返回当前最后页，空集合仍第1/1页。排序仍created_at DESC,id DESC。条件在SQL中绑定，不能先查询一页再在前端筛选。

Page增加status和summary。外层total是当前状态筛选的总数；summary是该角色全部订单，不受筛选/分页影响，包含total、pendingPayment、paid、delivered、completed、cancelled、closedTimeout、completedAmountInCents。六状态数量之和等于summary.total。

身份只取登录会话：BUYER按buyer_id、SELLER按seller_id，列名来自后端布尔分支白名单，status使用绑定参数；不接受用户ID参数。匿名/无效会话401，参数不合法400，所有响应no-store。统计不写入Redis，不与其他用户共享缓存。

## 金额

completedAmountInCents仅累加COMPLETED订单的price_in_cents_snapshot。取消、超时、待付款、待交付、待收货均不计入。使用整数分，MySQL SUM返回DECIMAL后精确转long并限制可展示范围；不先把金额转换成浮点数。

页面明确显示“已完成模拟交易金额”，不是余额、收入或真实资金结算。原模拟付款、交付与收货规则不变；晚到的重复确认不会增加订单或重复统计。

## 一致性

Service.page是readOnly、REPEATABLE_READ事务。先按当前用户/角色一次聚合全部订单，以相应状态计数作为筛选total，再查询同一条件下的分页行。汇总、total、总页数与列表读取共享一个数据库快照；并发完成的订单在下次刷新可见，不会本次统计已完成而列表仍显示旧阶段。GET不触发超时关闭或任何业务写入。

## 前端

角色切换保留状态条件并回到第一页；状态切换回到第一页。只有同一个响应全部校验成功后，角色、筛选、分页、列表与汇总一起提交。失败保留上次成功快照，标注仍显示旧结果；首次失败不展示虚假的零统计。

严格校验角色/状态回显、六状态和/金额、筛选total、每页条数、页码收缩、重复ID和每行状态，页面进一步核对行的参与者。金额按整数商余格式化，避免大整数分除以100再toFixed的精度问题。

账号切换清空数据、重置BUYER/ALL/第1页；隐藏/卸载使在途请求失效并清掉私有快照，返回前台按原角色/状态/页码重新读取。忙碌时禁用筛选/切换/翻页/打开详情，保留既有单次请求保护。

## 验收边界

写入造数仅使用明确命名的隔离库。实际业务库无DDL与业务写入，前后核对11张业务表（含market_orders）和图片。微信本课为编译验收，App仅资源构建，不是APK/IPA，也未做手机验收。
