# 第68课：Redis登录限流

本课没有新增Maven依赖或数据库表，沿用第67课的Spring Data Redis4.1.1和Lettuce7.5.2.RELEASE。

## 默认规则

- 同一真实来源IP、同一规范化用户名，15分钟窗口内最多保留5个“失败或在途”登录校验记录。顺序输入5次错误密码分别返回401，第6次尝试返回429。限制生效时正确密码也不能绕过。
- 来源IP在60秒窗口内最多进入30次密码校验，跨用户名共享，成功登录也不清除此计数。被账号窗口或来源窗口挡住的请求不再进行密码校验。
- 窗口从首次占位开始，到期后自动释放；拒绝请求不延长窗口。成功登录清理它开始之前已经存在的失败记录，不删除并发期间新完成的失败或其他在途请求。
- 不存在的用户名使用同样规则，并保留原有一次虚拟BCrypt校验，返回统一“用户名或密码错误”，不暴露账号是否存在。
- 输入格式不合法仍返回400，不进入昂贵的密码校验，也不占Redis预算。数据库/密码组件内部故障释放本次账号占位，但保留来源尝试数。
- 默认阈值是本项目开发演示配置；校园NAT等共享出口场景需结合真实访问量调整来源阈值。

## 为什么先占位，再核验密码

只在密码错误后INCR，会让同时到来的大量请求一起通过检查。本课通过Redis Lua脚本将“检查两层额度、来源计数、账号占位、设置TTL”作为一个原子操作执行。账号窗口用Hash保存服务器随机票据，值为pending或failed，因此已经进入校验的请求也会占用额度。

`login-reserve.lua`：检查账号/来源预算，创建随机占位，返回此时已存在的失败票据。

`login-finish.lua`：失败时把本次pending改成failed；成功时只删除自己的占位及开始前已存在的失败；内部故障时删除本次占位。旧窗口中的票据在到期后不能修改新窗口。重复完成操作也不会增加记录。

窗口内其他并发成功请求可能释放新的额度，因此“15分钟内总请求绝不超过5次”不是账号窗口的语义；账号窗口限制的是尚未被成功清理的失败和正在进行的校验。来源窗口始终计数全部已准入尝试。

Lua脚本原子执行不意味着发生脚本运行错误时自动回滚先前写入；发生错误时新登录返回503，来源计数可能已经占用，但不会放行未受保护的密码核验或签发会话。

## 独立Redis与故障策略

商品缓存：127.0.0.1:6379、128MB、allkeys-lru。故障时商品读库。

登录保护：127.0.0.1:6380、32MB、noeviction。限流数据不能与可淘汰的商品缓存共享同一个Redis实例；仅换Redis逻辑database不能隔离实例内存淘汰策略。

```sh
python3 scripts/redis-local.py start
python3 scripts/redis-local.py start --role login
python3 scripts/redis-local.py status --role login
python3 scripts/redis-local.py inspect --role login
```

本机脚本仅监听127.0.0.1、保护模式开启；本机示例无密码、不持久化。实际公网部署需要受控网络与独立凭证，不能直接公开此开发实例。此未持久化Redis实例重启或数据丢失会重新开始窗口；仅重启后端不会清空Redis中仍存在的记录。不应把这套短期限流写成永久封禁或持久风控。

登录保护连接/命令超时分别300/200ms。Redis连接失败、脚本错误或内存不足导致操作失败时，返回HTTP503、LOGIN_PROTECTION_UNAVAILABLE和Retry-After:5；本后端实例暂停访问保护Redis5秒后再尝试。已有MySQL会话、商品接口和核心readiness不受登录保护实例停止影响。readiness仍不代表所有可选子功能健康，应单独观察登录503日志与比例。

成功密码校验后的Redis完成操作也必须成功，才允许控制器签发会话。无法更新保护状态时不允许跳过限流继续登录。限流器使用自有Lettuce连接工厂，不替换Spring Boot自动配置的商品缓存连接。

## 来源与隐私

只使用 `HttpServletRequest.getRemoteAddr()`，并固定 `server.forward-headers-strategy=none`。不读取客户端传来的X-Forwarded-For、X-Real-IP、Forwarded。IPv6等价写法和IPv4映射地址进行规范化。

目前适用于直连本机后端。将来部署Nginx时，需要单独配置并测试可信代理边界；不能直接改成无条件相信转发头。

Redis键包含SHA-256摘要及随机票据，不包含原始用户名、IP、密码或登录令牌。摘要用于避免明文标识，不能视为加密或匿名化保证。`inspect --role login`只显示摘要键、剩余秒数与计数，不显示原始账号凭证。

## 配置

| 环境变量 | 默认值 |
| --- | --- |
| LOGIN_PROTECTION_ENABLED | true |
| LOGIN_REDIS_HOST | 127.0.0.1 |
| LOGIN_REDIS_PORT | 6380 |
| LOGIN_REDIS_PASSWORD | 空 |
| LOGIN_PROTECTION_PREFIX | campus:login:v1: |
| LOGIN_ACCOUNT_LIMIT | 5 |
| LOGIN_ACCOUNT_WINDOW_SECONDS | 900 |
| LOGIN_IP_LIMIT | 30 |
| LOGIN_IP_WINDOW_SECONDS | 60 |

显式关闭LOGIN_PROTECTION_ENABLED只用于隔离旧回归测试或明确的开发场景。生产环境应保持开启并监控保护服务。

429与503响应均为no-store，带Retry-After秒数；跨域响应暴露该头。现有uni-app登录页已经展示后端message，因此无需为本课改变前端接口格式。

## 验收

新增LoginRateLimitIntegrationTest共32项，使用专用临时MySQL和独立登录Redis，覆盖HTTP401/429/503、成功清理、未知账号、规范化、代理头伪造、并发准入、来源共享预算、TTL、不延长窗口、旧票据、重复完成、故障时禁止发会话和已登录会话兼容。

测试环境设置LESSON68_DB_URL与LESSON68_REDIS_PORT。测试库名必须为campus_lesson68_verify，不得指向真实campus_market。未明确提供隔离环境变量时测试自动跳过。此前回归测试显式关闭登录保护，新专项测试明确开启；这避免旧测试的批量登录撞上新限额，开启状态的业务行为由本课专项与实际接口验收覆盖。

官方参考：[Spring Data Redis脚本执行](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)、[Redis脚本原子执行](https://redis.io/docs/latest/develop/programmability/eval-intro/)。
