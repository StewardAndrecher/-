# 本地启动

[项目首页](../README.md) · [技术栈](TECH_STACK.md) · [测试说明](VERIFICATION.md)

## 1. 环境

- JDK17，Maven或后端目录的Maven Wrapper。
- MySQL，支持InnoDB与CHECK约束；当前完整验收使用MySQL9.6.0。
- HBuilderX及Vue3 uni-app编译支持。
- Node.js22.12+；Electron桌面端使用npm安装锁定依赖。
- Python3用于仓库验证脚本。完整隔离数据库验证入口支持macOS/Linux，要求mysql、mysqld、mysqldump、mvn在PATH。

```sh
git clone https://github.com/StewardAndrecher/-.git campus-market
cd campus-market
```

## 2. 全新数据库

先启动自己的MySQL。仅在尚无campus_market数据库时，在仓库根目录执行：

```sh
mysql --host=127.0.0.1 --port=3306 --user=root --password < scripts/init-database.sql
```

客户端会提示输入你的数据库密码。脚本按顺序整合20份结构SQL，不含业务数据；已有课程数据库应按尚未执行的迁移升级，不能重复初始化。

需要逐份查看时，顺序如下：

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
11. `lesson31-product-category.sql`
12. `lesson38-product-publications.sql`
13. `lesson43-product-sold.sql`
14. `lesson49-product-draft-backups.sql`
15. `lesson53-purchase-intents.sql`
16. `lesson54-intent-cancellation.sql`
17. `lesson55-intent-acceptance.sql`
18. `lesson59-intent-messages.sql`
19. `lesson60-intent-message-withdrawal.sql`
20. `lesson61-intent-message-read-state.sql`


## 3. 后端

用IDEA打开campus-market-server作为Maven项目，选择JDK17。启动配置设置：

```text
DB_USERNAME=你的MySQL用户名
DB_PASSWORD=你的MySQL密码
```

运行com.campus.market.CampusMarketServerApplication。默认访问127.0.0.1:3306/campus_market，HTTP端口8080。上传目录默认data/uploads，可通过UPLOAD_DIR指定。

也可在已设置环境变量的终端运行：

```sh
cd campus-market-server
./mvnw spring-boot:run
```

可检查就绪接口：http://localhost:8080/api/health/readiness 。启动后自行在页面注册账号、发布商品；仓库不提供共享真实账号。

## 4. Web与微信小程序

用HBuilderX打开campus-market-client，运行到浏览器。Web默认访问http://localhost:8080后端，前端开发服务地址以HBuilderX实际输出为准。前端由HBuilderX管理依赖，没有独立npm开发启动脚本。

配置文件为campus-market-client/config/server.js：Web、微信开发者工具和手机开发环境分别使用不同地址。192.168.1.100是示例，请改成电脑当前可访问的局域网地址；手机与电脑需网络可互通。正式环境地址目前留空，配置有效HTTPS域名后再使用正式环境。

manifest.json中的wx0000000000000000与__UNI__CAMPUSDEMO是发布示例，保持编译结构合法；运行微信小程序、DCloud基座或制作正式包时需填写自己的有效AppID。不要填写或提交AppSecret。微信开发者工具运行入口见[小程序说明](../campus-market-client/docs/weixin-development.md)。

## 5. 可校验的三端资源

在仓库根目录运行（macOS默认HBuilderX安装路径已适配）：

```sh
node campus-market-client/tools/build.mjs all
```

其他安装位置通过UNI_COMPILER_HOME指定HBuilderX的uniapp-cli-vite目录。产物位于campus-market-client/unpackage/verified/h5、mp-weixin、app。App目录是资源，不能当作APK/IPA直接安装。

## 6. Mac桌面客户端

先构建Web资源，再运行：

```sh
cd campus-market-desktop
npm ci
npm run prepare:runtime
npm run sync:web
npm test
npm start
```

后端仍需单独启动。桌面本地静态服务固定127.0.0.1:5188，业务访问本机8080。打包使用npm run package；默认拒绝覆盖同名输出，保留旧包后再打包。当前实际验证的是Mac ARM64，未验证Windows/Linux安装包，未配置签名、公证和自动更新。

## 常见问题

- IDEA无法识别Spring类：确认打开的是包含pom.xml的后端项目，JDK17，Maven依赖已导入。
- HTTP500或数据库未就绪：检查MySQL服务、数据库结构与运行配置中的账号；不要把真实密码写进源码。
- 手机请求localhost失败：localhost指向手机自身，开发配置应使用电脑可访问的局域网地址。
- 重开页面需要登录：当前Token只保存在内存；草稿和浏览记录按账号在本机保留。
- 空数据库没有商品或意向：初始化脚本只建结构，先注册自己的账号并创建测试商品。
