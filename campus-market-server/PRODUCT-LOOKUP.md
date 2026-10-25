# 第127课：有界的公开商品批量读取

POST /api/products/lookup 是公开只读查询，成功200，不创建资源或返回201。不依赖Bearer，不创建会话或Cookie，响应no-store。

请求仅允许 productIds：1至20个不重复JSON正整数，最大9007199254740991。字符串、浮点数、布尔、null项、未知字段、重复productIds键及非法编号均400。数量上限不是按用户传入值拼SQL；SQL占位符数由验证后集合长度决定，编号通过参数绑定。

响应为 {records:[{productId,product}]}，每个请求编号恰有一项，顺序严格等于请求顺序。单条SQL读取ON_SALE的公开ProductResponse，包括未登记卖家的历史在售商品；服务层重建顺序。不存在、下架和售出统一product:null，不返回不可用原因、隐藏标题、拥有者视图或其他私有信息，即使带商品主人的Token也不能扩展可见范围。

数据库失败返回整批500，不伪造所有商品不可用；部分编号不可用则正常200并用null标识。单条SQL读取当前状态，不调用单条详情缓存，不新增Redis键或MQ消息，不写商品、收藏、浏览历史、账号或权限。

浏览页只需要发送当前页编号，不接收viewedAt、userId、浏览时间或整份历史。本接口本身不验证登录是否有效，不能用它代替需要登录的业务接口。跨HTTP请求不是冻结快照。

本课不新增依赖、表、索引或迁移。减少HTTP请求数不代表已经测得延迟提升倍数，也不承诺生产规模数据库性能；SQL内仍有既有收藏计数子查询。

ProductLookupIntegrationTest使用私有MySQL验证顺序、混合可用性、输入边界、隐藏信息、所有者Token、旧商品、只读记录、参数化单条查询、状态更新、SQL失败和CORS；ApiContractIntegrationTest验证公开POST、200及有界请求模型。
