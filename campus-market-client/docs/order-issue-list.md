# 第106课：我的交易反馈清单

我的订单增加“我的交易反馈”入口，带入当前BUYER或SELLER身份。清单只包含本人已提交的反馈，支持ALL/OPEN/RESOLVED、分页、本人提交总数及待处理/已处理汇总。点击进入原本人反馈详情，服务端再次按会话鉴权。标题使用下单快照，列表不显示反馈正文、处理说明、管理员或对方身份。

GET /api/orders/issues?role=BUYER&status=ALL&page=1&size=10

默认BUYER、ALL、1、10；role精确BUYER/SELLER，status精确ALL/OPEN/RESOLVED；page为1至1000000，size为1至20。无效参数400，匿名/无效/撤销会话401，异常记录503，数据库不可用500。响应no-store，客户端不得指定查询人。

响应为{userId,role,status,page,size,total,totalPages,summary:{total,open,resolved},items:[{orderId,productTitleSnapshot,reason,createdAt,resolutionDecision,resolvedAt}]}。处理决定/时间均为空表示待处理；两者非空表示已处理，决定为ANSWERED/DISMISSED。列表不含description、explanation、applicantId、resolverId或requestId。

JOIN条件必须同时匹配order_id和applicant_id，WHERE中的申请人来自当前会话，并核对订单中当前BUYER/SELLER列。另一方即使已处理，也不会改变本人的待处理状态；没有本人反馈的订单不进入清单。汇总为当前角色下所有本人反馈，不随状态筛选或分页改变；total为本次筛选后的数量。

汇总与分页放在同一只读REPEATABLE_READ事务，SQL先筛选后LIMIT/OFFSET，按提交时间和订单编号倒序。越界页收缩到最后一页，空列表为第1/1页。跨HTTP请求不是冻结快照；管理员处理或有新反馈后，手动刷新重新读取。沿用已有外键和索引，不增加表、索引、迁移或软件包；本课未进行大规模压测，不宣称无限数据量性能。

前端校验最小精确字段、当前账号/筛选回显、计数安全范围与分区、分页长度、状态/时间配对和排序。ALL页各状态行数不能超过对应汇总。发生读取失败清除旧私人快照，不伪造空数据；没有自动重试。身份、令牌、筛选切换或隐藏卸载后旧响应失效；只允许打开当前页面中的有效订单，防止重复跳转。

入口：pages/order/list.vue → issueListPath(role) → pages/order/issues.vue → GET /api/orders/issues → OrderIssueListService.page → 原本人反馈详情。返回我的订单使用明确的uni.redirectTo路由，避免直接打开清单时退回浏览器历史。

只读清单不创建或修改反馈、处理结果、订单、商品、评价、Outbox或收件记录，不执行退款。异常表或时间返回错误，不将错误当成“暂无反馈”。未来可单独增加创建范围索引和游标，需先测量查询计划与数据规模。

测试入口：python3 -B scripts/test_backend.py --offline；前端python3 -B tools/test_quick.py。新增真实隔离MySQL测试覆盖本人/对方、角色、筛选分页、标题快照、只读、不一致数据、会话撤销和并发处理期间的事务快照；前端覆盖协议、账号/令牌/筛选切换与过期导航。
