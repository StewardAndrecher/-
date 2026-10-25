# 第117课：我收到的交易评价

复用order_reviews和market_orders，不新增软件包、表、迁移、索引或权限。已有评价的可见范围仍是订单双方；本课只为本人聚合本来就可见的对方评价，不公开到卖家主页。

## 接口与范围

GET /api/orders/received-reviews?role=BUYER&rating=ALL&page=1&size=10，必须使用有效Bearer会话。role为BUYER/SELLER，rating为ALL、GOOD（4—5分）、NEUTRAL（3分）、LOW（1—2分）；默认BUYER/ALL。page默认1，上限1000000；size默认10，上限20。

只接受上述四个单值参数。未知、重复、空值、大小写变体、前导零和非规范整数400 / INVALID_RECEIVED_REVIEW_QUERY；先鉴权，匿名／已退出／到期401。userId不能由客户端传入。无匹配结果200空items，不暴露他人的评价。

仅关联本人当前角色的COMPLETED订单，reviewer必须是另一方，排除本人给出的评价、非参与者记录、其他用户订单及未完成订单。无评价不会伪造默认评分。BUYER收到卖家评价，SELLER收到买家评价。

响应为userId、role、rating、page、size、total、totalPages、summary、items、sampledAt。summary.total/good/neutral/low表示当前角色全部收到评价的评分分布，不随rating过滤；total/items为评分筛选结果。每条仅orderId、productTitleSnapshot、rating、content、completedAt、reviewedAt，无对方ID/昵称、请求键、令牌或哈希。标题使用订单快照。

## 一致性与只读边界

REPEATABLE_READ只读事务内使用同一MVCC快照读取身份、汇总和本页。按评价时间、订单ID倒序，SQL先筛选后LIMIT/OFFSET，越界页收缩到末页。只有固定枚举选择的列名和条件进入SQL，账号、条数、偏移使用绑定值。每个HTTP请求重新采样，跨页不是冻结快照，并发新增评价可能导致偏移重复或漏项。

只读取，不创建评价、通知、MQ任务或历史补写，不修改订单状态。成功响应no-store / Pragma:no-cache。数据读取故障500；页面内评价时间或文字异常503 / RECEIVED_REVIEW_UNAVAILABLE，不返回假空列表。异常保护验证当前返回页；未对全库记录做逐条内容审计。沿用现有索引，未进行生产规模压测。

## uni-app入口与生命周期

我的订单 → 我收到的交易评价，携带当前买入／卖出角色。可按评分筛选、翻页、刷新、打开原订单评价。详情仍再次鉴权。页面隐藏清空数据，返回保留角色、评分及实际页码并重新读取；换账号或令牌恢复默认筛选并丢弃旧响应。失败清空旧结果，不自动重发。文字仅按文本渲染，不使用v-html。

## 验收

隔离测试覆盖本人／对方区别、双角色、第三方隔离、六种订单状态、评分分布、筛选后分页、同秒排序、越界收缩、参数白名单、敏感字段缺失、只读、失败和并发快照。所有写入仅发生在临时测试库；真实业务库只读核对。前端覆盖协议校验、迟到请求、身份变化、跳转和实际页面返回生命周期。小程序／App资源编译不等于手机安装运行。
