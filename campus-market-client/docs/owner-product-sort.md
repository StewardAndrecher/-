# 我的发布：三种服务端排序

GET /api/products/mine?page=1&size=5&status=ON_SALE&keyword=教材&sort=PRICE_ASC

- 支持NEWEST、PRICE_ASC、PRICE_DESC；默认NEWEST，省略sort兼容原调用。大小写或空白错误、MOST_FAVORITED及任意SQL片段均拒绝。
- NEWEST按created_at DESC、id DESC；价格分别ASC/DESC，同价按id DESC，避免同价跨页顺序不确定。规则复用ProductSort的固定SQL片段。
- 卖家身份、状态和标题条件先过滤，再整体ORDER BY、LIMIT/OFFSET；绝不只对当前五条做前端排序。
- 切换排序回第一页，保留已生效关键词和状态；加载成功后才更新排序选择。失败时保留旧列表/旧排序；输入框草稿不自动参与查询。
- 搜索、清空搜索、状态筛选、翻页、刷新、状态变更后补齐均保留已生效排序。切换账号重置NEWEST；旧响应受会话及页面卸载检查保护。
- 我的发布不开放收藏最多；公共首页仍沿用完整ProductSort目录及排序能力。
- 本课没有新增依赖、数据库迁移或索引，不宣称并发修改数据期间OFFSET分页不会漂移。
