# 第89课：管理员权限基础与只读管理中心

本课增加数据库管理员授权、统一管理路由拦截、只读概览、首个管理员本机授权工具和审计。普通注册用户仍然是USER，不改变原登录UserResponse，不在Token或浏览器存储中保存权威角色。没有新增Maven/npm依赖。

## 三个接口

- GET /api/users/me/permissions：当前会话用户的userId、roles、permissions。普通账号USER/空权限；管理员USER+ADMIN/ADMIN_ACCESS。未登录401。
- GET /api/admin/me：管理员自身账号和ADMIN身份。未登录401、普通账号403，数据库角色存储异常500，不能放行。
- GET /api/admin/overview：用户总数、商品总数、在售商品数、订单总数；不返回其他用户的个人信息或订单内容。相同管理员校验。

响应均no-store。管理权限由AdminWebConfiguration对/api/admin和/api/admin/**统一注册拦截器；新管理路由无需复制检查，但不能注册到其他命名空间绕过此规则。拦截器核验原有Bearer会话，然后每次读取实时授权表；不信任请求中的role、userId、X-Role或本地缓存。CORS预检不要求Bearer，真实请求仍核验；现有本机来源限制保留。

权限撤销后，下一次管理请求拒绝；已经通过检查的在途请求不会被强行终止。管理员仍受原会话过期和退出登录约束。角色在其他模块不自动绕过卖家归属、买家身份或订单状态规则；ADMIN_ACCESS只表示允许进入本课管理空间。

## V89迁移

新增user_role_grants和user_role_audit两张表。前者只记录ADMIN显式授权，USER为默认身份；后者保存目标ID、用户名快照、GRANT/REVOKE、本机操作来源、原因和时间。角色变更与审计同一事务提交，审计失败则权限变更回滚。

27份迁移用于新库；当前V74基线+V87库只新增V89一份。原26份迁移及V74参考不改。原14张业务表结构、行、图片与自增不变。本课不会创建默认管理员、硬编码密码、让首个注册账号自动成为管理员，或开放HTTP提权接口。

默认DB_MIGRATION_ENABLED仍false，baseline-on-migrate仍false，clean禁用。新增两表是两个DDL语句，MySQL不保证整份迁移事务回滚；异常必须检查数据库结构和Flyway历史，不能盲目repair/clean或删除现有表。

## 首个管理员的本机授权

先由账号本人正常注册并保管密码，然后本机操作人员执行检查，再按明确的目标授权。工具不创建账号、不修改密码、不自动执行数据库迁移、不在Web启动时授权。

使用发布JAR（启动的只是CLI，不运行Web服务器）：

```sh
java -Dloader.main=com.campus.market.admin.AdminRoleTool \
  -cp /absolute/path/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  check campus_market your_admin_username
```

运行环境需设置DB_URL、DB_USERNAME、DB_PASSWORD；不要把真实凭证写进命令行、文档或Git。DB_URL须为明确IPv4回环地址，形如jdbc:mysql://127.0.0.1:3306/campus_market?characterEncoding=UTF-8&useSSL=false，且库名与参数一致。

check返回userId、username、admin、changed=false、fingerprint，不写权限与审计。检查输出后，使用返回的指纹：

```sh
java -Dloader.main=com.campus.market.admin.AdminRoleTool \
  -cp /absolute/path/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  grant campus_market your_admin_username CHECK_FINGERPRINT '用户指定的首个管理员'
```

撤销使用revoke并重新check。工具在事务中先锁users行，再读取授权状态；指纹绑定库名、用户ID、用户名和原授权时间，防止检查后换目标或状态变化。指纹不是授权凭证，工具依赖本机数据库操作权限；不会将它放进公开HTTP接口。

状态已经符合目标时无变更、不重复写审计；原指纹在授权状态变化后会失效，重试前需重新检查。两个工具竞争同一旧指纹只允许一次变更。CLI错误不输出连接字符串、密码或完整驱动异常。

## 前端

首页仅为已登录用户读取自身权限；只有验证通过且包含ADMIN_ACCESS时显示“管理中心”。普通账号不显示入口；直接输入管理页URL仍需服务端验证。管理页展示管理员自身账号和四项统计，支持刷新和返回首页。

所有响应校验当前用户ID、角色/权限组合与安全整数统计；切换账号、Token变化、刷新、页面隐藏或卸载后不接受旧请求返回。刷新先清空旧管理数据，403清空旧身份和统计，网络失败可手动重试。界面入口不是权限边界，不通过存储role或隐藏按钮实现鉴权。

## 验收范围

本课新增32项私有MySQL+Spring MVC真实权限测试，以及1项OpenAPI契约测试。完整66类948项；专项权限/新库迁移/旧库接管/旧索引66项。前端新增12项权限响应和请求竞争测试，前端及桌面共91项。后端发布quick仍73项，Python后端工具45项、前端工具14项。

Web、小程序和App资源使用同一页面，共16路由；App资源编译不是APK/IPA或手机真机验收。桌面复用H5并在Mac ARM64打包运行。本课只有只读概览，没有商品审核、用户封禁、举报或管理员在线授权界面，后续按业务增加。
