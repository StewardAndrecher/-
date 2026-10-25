# 第126课：公开卖家主页分类筛选

既有 GET /api/sellers/{id}/products 增加可选 category；默认空文本表示全部分类，UNCLASSIFIED表示特定的“未分类”，二者不同。非空只接受当前六种 ProductCategory 枚举代码，不做大小写或空白自动转换，重复参数/未知分类400。

路径卖家ID、ON_SALE与分类始终取交集，在数据库过滤后统计并分页。COUNT和记录共享分类条件和只读REPEATABLE_READ快照；排序仍使用ProductSort四种白名单，参数值绑定，不将输入当SQL片段。

公开接口无需登录，也不根据Bearer切换卖家。仅返回seller的id/nickname及其当前在售商品；不公开用户名、密码、权限、下架、售出、其他卖家或未登记卖家商品。没有匹配分类或既有空卖家200空列表；不存在卖家404；绑定/校验错误400；数据库错误500，禁止伪造成功空列表。响应no-store且不创建会话Cookie。

响应格式仍为seller/products及products.records/total/page/size。越界保留请求页码和当前分类total，records为空。Service/Repository旧重载保留全部分类行为，旧客户端不传新参数可继续使用。

本课不增加依赖、表、索引或迁移，不写商品、收藏、评价、通知、MQ或权限。不同HTTP请求之间不是冻结快照；分类、状态变化后重新查询。没有新增性能保证，应根据实际规模分析查询计划。

测试由SellerCategoryIntegrationTest在私有MySQL中验证分类、四种排序、分页、可见性、只读记录、参数与快照；旧SellerStoreIntegrationTest及API契约同步回归。
