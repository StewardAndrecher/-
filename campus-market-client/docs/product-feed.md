# 第88课：商品游标分页

新增公开 GET /api/products/feed，首页保留原页码分页、总数和所有排序；点击“连续浏览最新商品”进入独立uni-app页面。没有新增依赖、服务或SQL迁移，复用V87两个复合索引。

## 请求与响应

首批：GET /api/products/feed?size=10&keyword=&category=&sort=NEWEST
下一批：同一筛选条件，增加 cursor=上一批nextCursor（交给URL参数编码）。size允许1～20，可在续页时改变；关键词strip后最长50，分类沿用原枚举。仅NEWEST，其他排序或无效参数400。

响应：records、hasMore、nextCursor、size。没有total或totalPages。末页hasMore=false且nextCursor=null；空结果同样如此。响应Cache-Control:no-store，无需登录，不接入旧的页码缓存。

## 数据库如何找到下一批

```sql
WHERE p.status = 'ON_SALE' AND p.title LIKE ? ESCAPE '!'
  AND (p.created_at < FROM_UNIXTIME(?)
       OR (p.created_at = FROM_UNIXTIME(?) AND p.id < ?))
ORDER BY p.created_at DESC, p.id DESC
LIMIT ?
```

分类非空才加category_code条件。游标比较两列，时间相同靠id打破并列；比较条件应用于原始索引列，时间转换放在参数一侧。使用数据库UNIX_TIMESTAMP读取秒数，避免JVM/连接时区把位置移错；目前created_at为TIMESTAMP(0)，以后若提高时间精度必须升级游标协议。

取size+1条判断还有没有下一批，只把前size条返回。**nextCursor必须使用最后一条可见记录**，如果使用额外那条探测记录，就会漏掉一件商品。保留先分页、后关联卖家与收藏数量；只对最多21件商品补信息。这里无商品总数COUNT、无OFFSET；每件商品的收藏数COUNT仍存在。

## 游标协议与边界

版本1载荷为 1|epochSecond|id|filterSHA256，使用无填充的Base64URL编码。筛选摘要包含NEWEST、规范化关键词（包含长度）和分类。严格校验规范编码、最长256、版本、时间范围、前端安全整数ID及筛选一致性；无效返回业务400，不泄漏500。

Base64和SHA256并非签名或加密。此游标只是公开数据的浏览位置，不能用于鉴权；调用方可以自行构造位置。公开接口仍必须限制页大小，并遵守全部可见性条件。

这不是跨请求数据库快照：新发布且位于当前位置之前的商品，需要刷新才看到；删除锚点不影响续页；下架、修改和搜索命中变化仍会影响后续结果。游标无需锚点仍存在，不查锚点行。关键词包含搜索可能扫描更多候选行，不能承诺任何筛选都只读21行。深页实测为12045件私有商品、跳过10000位置；单次EXPLAIN ANALYZE仅说明此夹具查询计划，不是P95/SLA。

## 前端状态机

独立.mjs控制器把列表、筛选、游标与loading组合管理。一次只加载一批；成功校验响应后才追加并推进游标；失败保留列表和游标供重试。刷新递增generation，迟到的旧请求不能覆盖新筛选；页面卸载后忽略返回。检查页内/跨页重复ID、原地游标、非在售数据及末页协议。独立页只展示公开卡片，不维护收藏交互状态。

网页、小程序和App使用同一页；本课构建证明包含路由和资源，不等同真机APK/IPA验收。桌面端需同步新的H5产物后重新运行或打包。

## 验证

后端新增10项游标编码测试、15项私有MySQL/真实SQL接口测试、1项OpenAPI契约测试；完整65类915项。前端新增12项状态测试，覆盖翻页、错误重试、并发、刷新竞争、卸载、重复数据和错误协议。数据库测试严格限制在临时MySQL目录，不向业务库插入夹具。

实际运行记录见本课验收报告。完整入口仍为 python3 -B scripts/test_backend.py --offline；前端为 python3 -B tools/test_quick.py --suite all 与 sh tools/build.sh all。
