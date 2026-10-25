# 第87课：用执行计划优化最新商品列表

## 范围与证据

本课优化首页及分类下的NEWEST排序，不新增Maven/npm依赖。新增V87迁移和两条复合索引，改写ProductRepository的最新列表查询。接口、返回字段、分类、关键词转义及排序规则保持一致；价格、收藏排序沿用原查询。

在私有MySQL9.6.0中构造12000件商品：9600件在售、BOOK分类1600件在售，发布时间存在大量并列。先比较原查询、仅改写查询、改写加索引三种方案，返回商品逐行一致。EXPLAIN ANALYZE实际执行SELECT；只对临时夹具运行大批量测量，不向业务库写压测数据。

本机一次观测，单位毫秒：

| 方案 | 首页最新20件 | BOOK最新20件 |
|---|---:|---:|
| 原查询、原索引 | 13.6 | 2.42 |
| 先分页再关联、原索引 | 5.66 | 1.04 |
| 先分页再关联、新索引 | 0.098 | 0.096 |

这些是指定数据分布和空关键词、offset=0条件下的单次观测，不是P95、压测吞吐或生产SLA。验收断言依据执行计划、结果一致和索引定义，不以固定耗时阈值判断通过。

## 为什么只加索引不够

原查询先LEFT JOIN用户并计算收藏数，再排序分页。本机优化器选择hash join，仍扫描、关联并排序9600或1600个候选商品，没有选中新索引。不能只看到CREATE INDEX成功就宣布查询变快。

新查询在带LIMIT的派生表中先选本页商品，再关联用户和读取收藏数。LIMIT阻止该派生表被合并，最终每页只补充20件商品的信息。外层显式ORDER BY保证最终顺序，不能依赖派生表的自然输出顺序。

```sql
SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
       p.cover_image_id, p.version, p.category_code,
       u.id AS seller_id, u.nickname AS seller_nickname,
       (SELECT COUNT(*) FROM product_favorites pf
        WHERE pf.product_id = p.id) AS favorite_count
FROM (
    SELECT p.id, p.title, p.description, p.price_in_cents, p.status,
           p.cover_image_id, p.version, p.category_code, p.seller_id, p.created_at
    FROM products p
    WHERE p.status = 'ON_SALE' AND p.title LIKE '%%' ESCAPE '!'
    ORDER BY p.created_at DESC, p.id DESC
    LIMIT 20 OFFSET 0
) p
LEFT JOIN users u ON u.id = p.seller_id
ORDER BY p.created_at DESC, p.id DESC;
```

分类查询在内层WHERE追加AND p.category_code = ?。真实代码仍使用绑定参数，查询改写是同一条SQL，不拆成多次应用层请求。

## 索引与最左前缀

```sql
ALTER TABLE products
    ADD INDEX idx_products_status_created_id
        (status, created_at DESC, id DESC),
    ADD INDEX idx_products_category_status_created_id
        (category_code, status, created_at DESC, id DESC),
    ALGORITHM=INPLACE,
    LOCK=NONE;
```

首页按status等值过滤后，读取created_at、id的倒序；分类页在category_code、status等值过滤后读取同样顺序。created_at相同仍按id倒序，分页顺序稳定。两条索引保留不同前缀，用于不同入口；不在没有使用证据时删除旧索引。

新增索引增加磁盘占用和写入维护成本。LOCK=NONE要求支持并发DML的在线方式，遇到不支持的条件直接失败，不静默退化为更强锁；开始或结束仍可能等待元数据锁，不等于完全不阻塞。

## 怎样读执行计划

给上面的完整查询加EXPLAIN FORMAT=TREE可只查看计划，加EXPLAIN ANALYZE会实际执行查询并给出实际行数与时间。

本课测试直接记录ProductRepository交给JdbcTemplate的SQL和绑定参数，再EXPLAIN，不复制一条更容易命中索引的简化查询，也不使用FORCE INDEX。

优化后首页内层命中idx_products_status_created_id，分类内层命中idx_products_category_status_created_id。Materialize只输出20行；内层不再对全部候选商品排序。外层仍可能对这20行排序，这是为保证返回顺序保留的工作，不能描述成“所有filesort都消失”。

普通B树不能为LIKE '%关键词%'直接定位任意包含匹配；非空关键词可能扫描更多索引项。深OFFSET也要跳过前面的记录。本课不宣称优化任意关键词、深分页、价格或收藏排序，后续可分别设计游标分页或搜索方案。

## 迁移与旧库接管兼容

25份已应用SQL和legacy-v74.properties保持冻结；新增V87，不编辑旧文件。

- 新空库执行26份SQL，历史26条，当前版本87。
- 第86课已经建立V74基线的库，正常启动只执行V87，历史2条：V74 BASELINE和V87 SQL。
- 未受管、结构仍是V74的旧库，接管工具仍固定baseline/target=74，核对25份历史SQL与14张表，允许更高版本保持待执行；建立基线后再由正常migrate执行V87，不能把baseline提高到87来跳过索引。
- 手动初始化交付同步整合第26份sql/lesson87-product-newest-indexes.sql；仍只用于全新库，不能在已有库重复初始化。

应用迁移开关默认false；受管启动需要显式DB_MIGRATION_ENABLED=true。本课本机迁移使用已验收JAR、随机回环端口和关闭业务扫描的临时运行配置，完成后停止该验收进程，原8080与IDEA配置不替换。桌面代码已具备新查询，原8080继续使用其启动时的代码，之后在IDEA重新构建运行才会加载新查询。

## 验收入口

```sh
python3 -B scripts/test_backend.py --offline
python3 -B scripts/build_release.py --offline
python3 -B -m unittest discover -s scripts/tests
```

完整63类889项；新增10项真实MySQL测试覆盖两条索引列序/方向、全部商品行和自增保留、重复迁移、两类真实查询计划、分页并列顺序、关键词字面转义、所有公开排序结果、空卖家LEFT JOIN、约束保留。原迁移和基线测试同步覆盖26份迁移与V74接管后升级V87。quick仍8类73项，不能替代完整回归。

参考：[MySQL复合索引](https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html)、[ORDER BY优化](https://dev.mysql.com/doc/refman/8.4/en/order-by-optimization.html)。实际验收环境为macOS ARM64、MySQL9.6.0、JDK17，不声明其他环境均已实测。
