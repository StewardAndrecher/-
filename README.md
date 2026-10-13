# 校园二手交易平台

基于 Java / Spring Boot / MySQL 与 Vue 3 / uni-app 的全栈学习项目，当前代码对应第28课。前后端分别位于 `campus-market-server` 和 `campus-market-client`。

## 已实现

- 用户注册、密码哈希、登录、会话过期与退出。
- 商品分页、关键词搜索、发布、卖家编辑和上下架。
- 商品封面上传、图片校验与访问。
- 商品版本控制，阻止过期页面覆盖新修改。
- 登录用户收藏、分页收藏列表与账号隔离。
- 商品公开留言、卖家标记、作者撤回。
- 留言提交幂等：同一次提交重试返回原结果；待确认草稿按账号和商品保存；已撤回留言不会因重试重新出现。

目前完成 Web 开发与验收。小程序、手机App和桌面应用是后续课程目标，尚未完成各端运行验收。

## 环境

- JDK 17。
- Maven，或者后端目录中的 Maven Wrapper。
- MySQL，使用 InnoDB 并支持 CHECK 约束。
- HBuilderX，使用 Vue 3 的 uni-app 项目支持。本项目由 HBuilderX 管理前端依赖，没有独立 npm 启动脚本。
- 后端使用 Spring Boot 4.1.1，具体依赖见 `campus-market-server/pom.xml`。

## 初始化数据库

仅在**全新数据库**执行以下文件；已有开发数据库不要重复初始化或重复执行 ALTER TABLE。

使用 MySQL 客户端连接数据库后，按顺序执行后端 `sql` 目录中的文件：

1. `00-create-database-and-products.sql`
2. `lesson15-users.sql`
3. `lesson19-login-sessions.sql`
4. `lesson21-product-seller.sql`
5. `lesson23-product-status.sql`
6. `lesson24-product-cover.sql`
7. `lesson25-product-version.sql`
8. `lesson26-product-favorites.sql`
9. `lesson27-product-comments.sql`
10. `lesson28-comment-idempotency.sql`

例如，在 MySQL 客户端中用 `SOURCE /完整路径/campus-market-server/sql/文件名.sql;` 依次执行。

这些脚本只建立结构，不包含真实用户、密码、会话、商品、留言或上传图片。启动后可以在页面注册自己的账号并发布商品。

## 启动后端

默认数据库地址为 `127.0.0.1:3306/campus_market`，后端端口为 `8080`。

在 IDEA 中将 `campus-market-server` 作为 Maven 项目打开，选择 JDK 17，在启动类的运行配置中设置环境变量：

```text
DB_USERNAME=你的数据库用户名
DB_PASSWORD=你的数据库密码
```

运行 `com.campus.market.CampusMarketServerApplication`。运行配置与真实密码不提交到 Git。

也可以在终端配置环境变量后，从后端目录运行：

```sh
./mvnw spring-boot:run
```

## 启动前端

用 HBuilderX 打开 `campus-market-client`，选择“运行到浏览器”。

接口地址位于 `api/request.js`，当前为 `http://localhost:8080`。先启动后端，再打开前端；商品页的数据来源应显示“后端接口”。

页面刷新后内存中的登录状态清除，需要重新登录。待确认留言草稿会在原账号重新登录后恢复。

手机或小程序中的 localhost 指向设备自身。后续多端部署需要配置可访问的后端地址、HTTPS及相应平台网络设置；当前默认配置用于本机 Web 开发。

## 后端验收

当前版本包含83项后端测试。数据库集成测试使用 `LESSON*_DB_URL` 环境变量和严格测试库名称校验；直接运行 `mvn test` 时，未配置环境变量的数据库集成测试会跳过。

仓库提供隔离验收脚本，需要 Python 3，并确保 `mysql`、`mysqld`、`mvn` 位于 PATH，JAVA_HOME 指向 JDK 17：

```sh
python3 scripts/test_backend.py
```

脚本创建临时 MySQL 数据目录，申请临时端口，建立第21～28课的专用测试库，执行迁移与全部测试，最后清理临时数据目录。它不连接真实开发数据库。运行日志保存在 `campus-market-server/target/integration.log`。

测试覆盖身份与权限、分页和校验、图片、商品版本冲突、收藏、留言撤回、并发提交幂等、事务回滚和缓存响应头。

## 关键接口

| 功能 | 方法与路径 |
|---|---|
| 注册 / 登录 / 退出 | POST `/api/users/register`、`/api/users/login`、`/api/users/logout` |
| 商品列表 / 发布 | GET / POST `/api/products` |
| 商品详情 / 编辑 | GET / PUT `/api/products/{id}` |
| 留言列表 / 发表 | GET / POST `/api/products/{id}/comments` |
| 作者撤回 | DELETE `/api/products/{id}/comments/{commentId}` |

需要登录的请求使用 `Authorization: Bearer <Token>`。具体字段与其他接口见对应 Controller 和前端 api 文件。

留言发表提交 `{ "content": "留言内容", "requestId": "小写标准UUID" }`：首次201；同内容同标识重试200；同标识改内容409。幂等范围是用户、商品与提交标识，依赖原数据库记录保留。
