# 第67课：公开商品详情正文缓存

本课新增 Spring Boot 4.1.1 的 `spring-boot-starter-data-redis`，由父项目管理 Spring Data Redis 和 Lettuce 版本。没有增加数据库表，也没有更换 MySQL 作为业务数据来源。

## 本机启动和查看

在后端项目根目录执行：

```sh
python3 scripts/redis-local.py start
python3 scripts/redis-local.py status
python3 scripts/redis-local.py inspect
```

`start` 只管理本项目自己的 Redis 副本，监听 `127.0.0.1:6379`，保护模式开启、最多128MB、allkeys-lru淘汰，不启用RDB/AOF持久化。原Homebrew安装不会被改动。本机原Redis二进制保留了 `@@HOMEBREW_PREFIX@@` 依赖占位符，脚本只修复 `data/redis/` 下副本的库路径，并重新进行本机签名。

其他程序占用端口时脚本会退出，不会停止那个程序。可以为脚本和IDEA后端同时设置 `REDIS_PORT=6380` 改用独立端口。

`inspect` 只扫描本项目的缓存前缀，显示键、剩余秒数和公开商品正文。它不会展示登录令牌，也不使用 FLUSHALL/FLUSHDB。打开一个在售商品详情后才能看到缓存；默认60～75秒后自动消失。

停止本项目缓存：

```sh
python3 scripts/redis-local.py stop
```

停止后商品接口仍读MySQL。不要将此本机配置直接用于公网Redis。

## 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| REDIS_HOST | 127.0.0.1 | 后端Redis地址；本机管理脚本始终只操作127.0.0.1 |
| REDIS_PORT | 6379 | 后端和本机管理脚本的端口 |
| REDIS_PASSWORD | 空 | 外部受保护实例的密码；本机脚本不设置密码 |
| PRODUCT_CACHE_ENABLED | true | false时直接使用原数据库读取路径 |
| PRODUCT_CACHE_PREFIX | campus:product-detail:v1: | 隔离环境和缓存结构版本，测试必须使用独立前缀/实例 |
| PRODUCT_CACHE_TTL_SECONDS | 60 | 基础TTL，允许1～3600秒；附加0～min(15,基础TTL/4)秒随机值 |

Redis连接超时300ms，命令超时200ms。捕获缓存操作错误后，当前后端实例5秒内跳过缓存；下一次请求再尝试连接。超时值不是整个HTTP请求耗时的严格上限。MySQL不可用仍会失败，因为它承担权限与真实业务数据校验。

Redis作为可降级缓存，不影响 `/api/health/liveness` 和 `/api/health/readiness` 的健康结果；就绪检查仍包含MySQL。Redis健康贡献项关闭，避免可选缓存故障把核心接口标记为不可用。缓存问题会打印不含凭证的警告。

## 数据与调用过程

缓存键：`campus:product-detail:v1:<商品ID>:<商品版本>`。

JSON只包含：`id,title,description,priceInCents,coverImageId,version,category`。不缓存下架/售出商品、不缓存账号或会话、不缓存昵称和收藏数。分类显示名和图片访问地址由后端构造。

1. 正常开启缓存时，先读取MySQL中的商品状态、版本、当前卖家资料和收藏数。
2. 在售时按当前版本读取Redis；命中后把缓存正文与刚读取的动态字段组合成原 `ProductResponse`。
3. 未命中时完整读取数据库，只把在售商品正文以JSON写入Redis。处在事务里时等提交后写入。
4. 商品编辑/下架/售出成功后，在最外层数据库事务提交成功时删除旧版本缓存。回滚不删，未改变内容或被拒绝的更新也不删。
5. 写事务内的详情读取直接走数据库，既能看到本事务修改，也不会把未提交内容写入Redis。
6. Redis失败、缓存JSON损坏或ID/版本不符，都回到数据库；Redis故障不应转成商品接口HTTP500。

已有接口路径、JSON字段、卖家权限、乐观锁版本规则和浏览器 `Cache-Control: no-store` 均保持。服务器Redis缓存与浏览器缓存是两个独立层次。

## 为什么命中后仍读MySQL

如果仅依靠“提交后删除缓存”，一个先开始读取的请求可能在删除之后把旧结果重新写回。旧键即使短期存在，本实现仍按数据库最新版本选键；下架/售出时也先校验当前状态，不会从旧键恢复公开访问。提交前已经读取到旧数据库快照的在途请求仍可能返回旧视图，这是本课的正常并发边界。

昵称修改和收藏操作不改变商品版本。因此它们随每次数据库状态查询实时读取，避免命中旧正文时丢失新昵称或收藏数。

**性能边界：缓存命中跳过的是完整商品正文读取，不是所有SQL。状态查询仍含卖家连接和收藏计数。当前数据量很小，不声称已经提升多少吞吐或延迟。** 第67课缓存范围不包括负缓存、热点请求合并、分布式锁、集群或跨服务会话；后续应按真实负载和业务需要增加。当前版本已在第70课实现基于MySQL条件更新与定时任务的订单超时关闭，见 [ORDER_LIFECYCLE.md](ORDER_LIFECYCLE.md)。

## 验收

新增 `ProductDetailCacheIntegrationTest`：使用独立临时MySQL和真实Redis，共24项；覆盖命中时完整正文查询次数、中文JSON、TTL、变更后失效、下架/售出权限、回滚、外层提交、旧读回填、动态昵称/收藏、损坏数据、历史无卖家商品、图片/分类、关闭缓存和Redis故障回退。

集成测试需要专用测试库及Redis，不能把 `LESSON67_DB_URL` 指向实际业务库。运行时设置 `LESSON67_DB_URL=jdbc:mysql://127.0.0.1:<临时端口>/campus_lesson67_verify?...` 和 `LESSON67_REDIS_PORT=<临时Redis端口>`；没有显式隔离环境变量时自动跳过，不会删除真实数据。

官方文档：[Spring Boot Redis](https://docs.spring.io/spring-boot/reference/data/nosql.html#data.nosql.redis)、[StringRedisTemplate与序列化](https://docs.spring.io/spring-data/redis/reference/redis/template.html)、[事务提交后回调](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)。

## 第68课新增登录保护实例

商品缓存仍默认使用6379、128MB、allkeys-lru。本课登录保护单独使用6380、32MB、noeviction，避免缓存淘汰清掉限流状态。

```sh
python3 scripts/redis-local.py start --role login
python3 scripts/redis-local.py status --role login
python3 scripts/redis-local.py inspect --role login
```

不加 `--role` 的原有命令继续管理商品缓存。登录保护运行文件位于 `data/redis/login/`。停止登录保护使用 `python3 scripts/redis-local.py stop --role login`；它会让新登录暂时返回503，已有会话继续有效。Redis不持久化，重启会重新开始限流窗口。详细规则和并发行为见 [LOGIN_PROTECTION.md](LOGIN_PROTECTION.md)。
