# 第37课：公开卖家主页

GET /api/sellers/{id}/products?page=1&size=5&sort=NEWEST 返回{seller:{id,nickname},products:{records,total,page,size}}，无须登录，不发送Token，只公开这位卖家的在售商品。没有商品时200空列表，不存在的卖家404。支持已有四种排序；卖家编号/分页/排序由后端校验，SQL参数绑定并始终限制seller_id和ON_SALE。卖家概要只SELECT id,nickname，不序列化完整账号对象。

Service在只读REPEATABLE_READ事务中读取卖家、数量和列表，保持同次响应一致。公开查询和我的发布查询分开，后者继续允许本人管理下架商品。没有数据库迁移和新增依赖。

商品卡片通过open-seller事件传卖家ID，@click.stop阻止冒泡打开商品详情；详情直接使用sellerStorePath。卖家页复用ProductCard但隐藏收藏按钮和重复卖家入口，可点击商品进入详情。历史未登记卖家的商品不显示主页按钮。

路由编号严格验证，H5监听hashchange，修改为无效编号立即清空旧内容；操作序号和active/closed保护页面隐藏、卸载、切换卖家后的迟到响应。排序只在请求成功后提交；分页数据缩小后重新读取最后一页。该页不持久化公开商品快照，进入/返回时重新查询。
