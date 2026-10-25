# 本地运行指南

从仓库根目录开始。下面使用空的本地开发库，不会导入作者的账号或商品。完整回归还需要 RabbitMQ；先看页面可以暂不启用 MQ。

## 1. 准备环境

| 工具 | 要求 |
| --- | --- |
| Java | JDK 17；IDEA SDK 与终端 JAVA_HOME 都需要检查 |
| Maven | 可使用已安装的 Maven，后端同时提供 Maven Wrapper |
| Python | 3.9+；自带工具目前面向 macOS / Linux |
| MySQL | 支持 InnoDB、CHECK 约束与所用迁移语法；以测试环境和 CI 结果确认兼容性 |
| Redis | 缓存与登录保护使用两个独立实例 |
| Node.js | 22.12+；用于 Node 测试与 Electron |
| HBuilderX | 使用 uni-app Vue 3 编译器运行/构建前端 |
| RabbitMQ + Erlang | 启用消息功能或执行完整后端回归时需要 |

```sh
git clone https://github.com/StewardAndrecher/-.git campus-market
cd campus-market
```

## 2. 创建开发数据库

先启动自己安装的 MySQL，用本机管理账号连接，**只对全新空库**执行：

```sql
CREATE DATABASE campus_market CHARACTER SET utf8mb4;
```

在本机建立仅能访问这个开发库的数据库账号；通过 IDEA 运行配置或私有环境文件设置下面三个变量，不要提交凭据：

```text
DB_URL=jdbc:mysql://127.0.0.1:3306/campus_market
DB_USERNAME=<你本机的开发数据库账号>
DB_PASSWORD=<该账号的密码>
DB_MIGRATION_ENABLED=true
```

首次运行由 Flyway 创建结构，之后保留迁移历史，新增迁移会按版本应用。当前有 42 份迁移，最新 V136。**不要在已有业务库运行初始化 SQL，也不要把手动初始化与 Flyway 混用。** 仓库根的 `scripts/init-database.sql` 仅供全新库结构参考，不能用作现有数据库升级脚本。

已有老库需要接管时先阅读 [LEGACY-BASELINE.md](../campus-market-server/LEGACY-BASELINE.md)，不要直接打开自动 baseline。

## 3. 启动 Redis 和后端

进入后端目录。Redis 已安装并在 PATH 中时，可使用项目工具启动两份独立本机实例：

```sh
cd campus-market-server
python3 scripts/redis-local.py start --role cache
python3 scripts/redis-local.py start --role login
```

默认端口分别为 6379 与 6380。缓存使用可淘汰策略，登录保护使用 `noeviction`；工具遇到不属于本项目的实例会拒绝接管。也可以自行配置独立实例，并同步 `REDIS_*`、`LOGIN_REDIS_*` 变量。

在上面的数据库环境变量已配置的终端中运行：

```sh
./mvnw spring-boot:run
```

或用 IDEA 打开 `pom.xml`、完成 Maven 导入后运行 `CampusMarketServerApplication`。开发默认监听 8080。

```sh
curl --noproxy '*' http://localhost:8080/api/health/readiness
curl --noproxy '*' http://localhost:8080/api/products
```

新库商品列表为空是正常现象。通过页面注册自己的测试账号并发布商品即可。无内置管理员或共享密码。

## 4. 在 HBuilderX 运行前端

1. 导入同级 `campus-market-client`，保留完整目录结构。
2. 检查 `config/server.js`：Web 默认访问 `http://localhost:8080`。
3. 使用“运行 → 运行到浏览器”，以 HBuilderX 实际输出的 URL 和端口为准。
4. 注册、登录、发布一件闲置，检查首页、详情和收藏。

前端是 HBuilderX 方式的 uni-app 工程，没有 `npm run dev` 入口。

手机和电脑连接同一局域网时，把 `weixinDevice` / `appDevelop` 改成**电脑当前局域网地址**；仓库里的 `192.168.1.100` 只是示例，手机上的 localhost 指向手机自身。小程序需要在 `manifest.json` 中填写自己的 `mp-weixin.appid`；App 构建需要自己的 DCloud AppID。仓库不发布作者的 AppID。

本机装有 HBuilderX 编译器时，也可在前端目录运行：

```sh
sh tools/build.sh h5
# 填好对应 AppID 后，按需构建：
sh tools/build.sh mp-weixin
sh tools/build.sh app
```

资源输出到 `unpackage/verified/`，不提交 Git。自定义安装位置见 [可复现构建说明](../campus-market-client/docs/reproducible-builds.md)。`app` 输出为 App 资源，不是 APK / IPA。

## 5. 可选服务与桌面端

- **RabbitMQ**：默认 `ORDER_MQ_ENABLED=false`，订单扫描补偿仍可运行。完整消息场景需要项目专用用户/vhost，配置 `RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VHOST`，再启用 `ORDER_MQ_ENABLED` 与 `NOTIFICATION_MQ_ENABLED`。部署和故障演练见 [MQ.md](../campus-market-server/MQ.md)。
- **邮件**：默认 `EMAIL_ENABLED=false`。真实邮箱验证码需要配置自己的 SMTP、发信地址和 OTP 密钥；不要把凭据放进前端或公共仓库。实现与校验见 [治理与账号文档](../campus-market-server/docs/platform-governance.md)。
- **OpenAPI**：仅本机开发需要时设置 `API_DOCS_ENABLED=true`，访问 `http://localhost:8080/docs.html`。生产配置关闭文档入口。
- **Electron**：先完成 H5 构建，再进入 `campus-market-desktop`：

```sh
npm ci
npm run prepare:runtime
npm run sync:web
npm start
```

桌面端复用本机 8080 后端，运行方式与 Mac 打包边界见 [桌面端说明](../campus-market-desktop/README.md)。

## 6. 运行检查

在仓库根执行：

```sh
# Python 工具自身检查
python3 -B -m unittest discover -s campus-market-server/scripts/tests -v
python3 -B -m unittest discover -s campus-market-client/tools/tests -v

# 前端 + 桌面逻辑测试：不需要 npm install 或启动业务服务
python3 -B scripts/test_frontend.py

# 后端快速检查：无需 MySQL / Redis / RabbitMQ
python3 -B campus-market-server/scripts/test_backend.py --suite quick

# 完整后端回归：已安装中间件时，创建私有临时实例并自动清理
python3 -B campus-market-server/scripts/test_backend.py
```

首次执行需下载 Maven 依赖；已有本机缓存时可给后端检查添加 `--offline`。不要用直接 `mvn test` 的成功替代完整入口验收，部分集成测试缺少指定测试环境时会跳过。

## 7. 正式部署前

开发库和本机运行并不等于公网部署。生产配置名为 `production`，使用专用库、运行账号和独立迁移进程；禁止 root/迁移账号运行应用，关闭模拟支付。服务器、域名、TLS、邮件、真实商户、异地备份和移动端发布仍需实际配置与验收。

- [发布目录与部署模板](../campus-market-server/release/)
- [数据库权限、备份恢复与保留策略](../campus-market-server/docs/database-maturity.md)
- [验收记录与功能边界](VERIFICATION.md)
