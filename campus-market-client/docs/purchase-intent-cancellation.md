# 第54课：买家取消购买意向

新增依赖：无。数据库迁移 sql/lesson54-intent-cancellation.sql。

首页→我的购买意向→我发出的→取消意向→确认取消。只有当前会话买家可取消自己的PENDING意向，状态变为CANCELLED并记录cancelledAt。买家和卖家列表都保留原快照、创建时间及取消时间，条数不减少。

POST /api/purchase-intents/{intentId}/cancel：首次取消200/replayed=false，再次取消200/replayed=true，取消时间不变。未登录401，非法编号400，不存在或不属于当前买家均404。服务器只根据登录会话判断身份，不接受前端买家ID。使用事务锁定本人意向行，再执行带buyer_id和PENDING条件的UPDATE，不修改商品。

取消是终态。本课不支持恢复或重新发起；原创建接口仍按买家＋商品唯一键返回已取消的原意向，不会重新打开它。详情页正确显示已取消。

前端只在明确确认后发送取消请求，不自动重试。弹窗期间或响应返回时校验账号、Token、页面可见状态及请求序号。超时提示先刷新；刷新后仍待沟通才可再次取消，后端幂等。列表查询、翻页与取消互斥。换账号清空旧列表，离开首页后的旧确认和响应失效。

迁移保留原行，新增可空cancelled_at并扩展状态约束。PENDING必须没有取消时间，CANCELLED必须有取消时间。创建的READ_COMMITTED和列表的REPEATABLE_READ规则沿用上课。
