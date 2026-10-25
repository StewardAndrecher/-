# 第113课：Redis注册防刷与等待提示

不新增软件包、数据库表、迁移或MQ资源；复用现有独立登录保护Redis连接，新增专用注册Lua脚本和计数键。默认启用状态跟随登录保护；未改变真实凭据、Redis淘汰策略、监听地址或用户权限。

## 请求顺序与策略

POST /api/users/register完成JSON绑定后，先调用RegistrationRateLimiter，再执行原用户名/昵称/密码校验、密码哈希和数据库唯一约束插入。默认同一连接来源60秒最多60次，REGISTRATION_LIMIT（1..10000）和REGISTRATION_WINDOW_SECONDS（1..86400）可调整。每个固定窗口从首个放行请求开始，不是滑动窗口或验证码。已放行但校验失败/重名也计入，不回退；JSON绑定失败不进入Controller，不计入本策略。

REGISTRATION_PROTECTION_ENABLED默认跟随market.login-protection.enabled。显式开启注册保护但关闭共享登录Redis连接时，配置启动失败，避免误以为受保护。可以明确关闭本特性用于隔离测试，不在故障时自动关闭。该能力只保护HTTP注册入口，UserService内部方法本身没有来源限流。

## Redis原子行为

复用LoginRateLimiter既有Lettuce连接和5秒故障退避，保留原构造签名及登录算法。注册键为原prefix+{规范化IP的SHA256}:registration，与:ip及:account:登录键隔离；不保存明文IP、用户名、密码或请求正文。哈希只是去除明文，不代表不可关联或匿名化。

Lua在同一执行中读取计数/TTL、判断上限、递增并仅在首次设置过期。到达上限后返回剩余TTL，被拒请求不递增也不延长窗口。不存在键时允许建立新窗口；已有键若格式、类型或TTL异常则失败，不修复或删除数据，也不发放配额。Redis连接、脚本、OOM或异常结果均拒绝注册。固定窗口边界附近可能出现相邻窗口突发，不声称滑动窗口限速或完整反滥用能力。

429返回REGISTRATION_RATE_LIMITED、Retry-After整数秒数、no-store和Pragma:no-cache；503返回REGISTRATION_PROTECTION_UNAVAILABLE与Retry-After:5，且本次在业务服务前拒绝，没有创建账号。注册成功也no-store。Controller跨域暴露Retry-After，原X-Request-Id仍保留。

只读取servlet的getRemoteAddr，不直接信任客户端Forwarded/X-Forwarded-For。本机未启用转发头改写；公网反向代理部署须另外建立可信来源策略。校园NAT共享出口会共用配额，部署前需按实际流量调参。本课没有启用公网代理、验证码、实名、持久化审计、分布式压测或新的告警面板。

## 前端

公共请求层把429/503的规范Retry-After解析为ApiError.retryAfterSeconds，接受大小写不同但仅一个同名头，范围1..86400；其他状态或非法值为0。注册页只有明确的两个注册错误码才启动等待：缺失/非法值分别回退30秒或5秒，不把普通500/503伪装为确定未创建账号。

保留当前活动表单，显示等待秒数，禁用按钮并在提交函数中再次检查，防止回车绕过。到期不自动提交，需用户主动点击。基于截止时间重算，隐藏停止计时，恢复重新计算；隐藏/卸载清除密码，用户名和昵称保留。迟到错误不会在后台重启计时器，卸载后忽略回执。已提交的服务器操作不能由离开页面撤销。

倒计时仅为使用提示，客户端时钟或重载不能绕过后端Redis判定。本课未改变既有用户名唯一约束、密码哈希或登录会话语义，不自动登录。

## 测试

RegistrationRateLimitIntegrationTest新增25项：3次放行/第4次429、响应头、拒绝不延长窗口、不跨IP共享、忽略转发头、IPv4映射/IPv6规范化、哈希键最小化、校验/重名计数、JSON绑定边界、登录/注册隔离、36并发恰好3次放行、业务服务前拒绝、真实TTL到期、坏计数/无TTL/错类型拒绝、不可达Redis503、不读写的显式关闭、配置边界、无DNS解析、CORS和成功no-store。原登录保护与API契约一同回归，文档新增1项。

registration-retry.test.mjs新增24项：响应头/错误码/回退/截止时间、真实request模块元数据，以及直接加载真实Vue脚本验证主动重试、无自动重发、表单保留、后台/卸载清理、迟到回执、503、普通错误和本地校验。真机运行与高并发容量须单独验收。
