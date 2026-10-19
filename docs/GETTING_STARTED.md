# 本地启动

[项目首页](../README.md) · [技术栈](TECH_STACK.md) · [测试说明](VERIFICATION.md)

## 1. 环境

- JDK17，Maven或后端目录的Maven Wrapper。
- MySQL，支持InnoDB与CHECK约束；当前完整验收使用MySQL9.6.0。
- Redis，提供redis-server/redis-cli；默认使用两个独立实例，详见下文。
- RabbitMQ及兼容Erlang，当前MQ验收使用RabbitMQ4.3.6 / Erlang28。仅在启用业务MQ时需要；完整后端测试需要RabbitMQ。
- HBuilderX及Vue3 uni-app编译支持。
- Node.js22.12+；Electron桌面端使用npm安装锁定依赖。
- Python3用于仓库验证脚本。完整隔离数据库验证入口支持macOS/Linux，要求mysql、mysqld、mysqldump、mvn和redis-server在PATH（也可设置REDIS_SERVER_BIN）；完整后端测试还需要rabbitmq-server/rabbitmqctl在PATH，或设置RABBITMQ_SBIN为RabbitMQ的sbin目录。

```sh
git clone https://github.com/StewardAndrecher/-.git campus-market
cd campus-market
```

## 2. 全新数据库

先启动自己的MySQL。仅在尚无campus_market数据库时，在仓库根目录执行：

```sh
mysql --host=127.0.0.1 --port=3306 --user=root --password < scripts/init-database.sql
```

客户端会提示输入你的数据库密码。脚本按顺序整合25份结构SQL，不含业务数据；已有课程数据库应按尚未执行的迁移升级，不能重复初始化。

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
21. `lesson69-market-orders.sql`
22. `lesson70-order-lifecycle.sql`
23. `lesson71-order-fulfillment.sql`
24. `lesson73-order-events.sql`
25. `lesson74-order-timeout-outbox.sql`

已有订单库升级第73课时先停止旧后端、备份并执行新迁移，再启动新后端。建表与历史快照补录是两条SQL，不是整个脚本原子提交，不要重复执行建表。


## 3. Redis与后端

先在后端目录启动两个项目专属Redis实例（安装Redis后，redis-server与redis-cli需在PATH）：

```sh
cd campus-market-server
python3 scripts/redis-local.py start
python3 scripts/redis-local.py start --role login
python3 scripts/redis-local.py status
python3 scripts/redis-local.py status --role login
```

默认缓存6379/128MB/allkeys-lru，登录保护6380/32MB/noeviction，只监听127.0.0.1、保护模式开启，不启用持久化。脚本遇到其他实例占用会退出，不会清空或停止未知实例。更换端口时同时设置REDIS_PORT、LOGIN_REDIS_PORT及后端对应环境变量；两个实例必须使用不同端口，不能靠Redis逻辑database隔离淘汰策略。

商品缓存故障会回退MySQL；登录保护故障时新登录返回503，已有会话继续有效。详细配置见[缓存](../campus-market-server/REDIS.md)和[登录限流](../campus-market-server/LOGIN_PROTECTION.md)。下面命令从仓库根目录执行，或在IDEA中设置后端运行配置。


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

可检查就绪接口：http://localhost:8080/api/health/readiness 。启动后自行在页面注册账号、发布商品；仓库不提供共享真实账号。可注册两个开发账号，依次验证下单、模拟付款、卖家交付、买家收货和订单时间线；模拟付款不扣款。

## 4. RabbitMQ订单超时处理

默认ORDER_MQ_ENABLED=false，数据库超时扫描仍工作。需要启用MQ时，macOS先通过Homebrew安装RabbitMQ（包含兼容Erlang），然后在后端目录执行：

```sh
python3 scripts/rabbitmq-local.py start
python3 scripts/rabbitmq-local.py configure-idea
python3 scripts/rabbitmq-local.py status
```

configure-idea要求已建立名为CampusMarketServerApplication的Spring Boot运行配置；会备份旧配置并写入本机MQ环境变量，随后在IDEA启动并确认timeout队列消费者连接成功。其他IDE或终端按自己的配置设置ORDER_MQ_ENABLED=true及RABBITMQ_HOST/PORT/USERNAME/PASSWORD/VHOST；不要将密码写入源码。

默认只监听回环，AMQP5672、管理页15672，vhost /campus-market。随机凭证、Cookie及磁盘消息保存在被忽略的data/rabbitmq目录；不要上传。脚本不启用开机服务、不停止未知节点，其他RabbitMQ安装路径可用RABBITMQ_SBIN指定。

已有第73课数据库升级前先停旧后端并备份，再执行lesson74-order-timeout-outbox.sql；只新增Outbox表，不改写历史订单。正常延迟15分钟，队列参数变更应采用新版本前缀并处理旧消息。详细设计及停机操作见[MQ说明](../campus-market-server/MQ.md)。

## 5. Web与微信小程序

用HBuilderX打开campus-market-client，运行到浏览器。Web默认访问http://localhost:8080后端，前端开发服务地址以HBuilderX实际输出为准。前端由HBuilderX管理依赖，没有独立npm开发启动脚本。

配置文件为campus-market-client/config/server.js：Web、微信开发者工具和手机开发环境分别使用不同地址。192.168.1.100是示例，请改成电脑当前可访问的局域网地址；手机与电脑需网络可互通。正式环境地址目前留空，配置有效HTTPS域名后再使用正式环境。

manifest.json中的wx0000000000000000与__UNI__CAMPUSDEMO是发布示例，保持编译结构合法；运行微信小程序、DCloud基座或制作正式包时需填写自己的有效AppID。不要填写或提交AppSecret。微信开发者工具运行入口见[小程序说明](../campus-market-client/docs/weixin-development.md)。

## 6. 可校验的三端资源

在仓库根目录运行（macOS默认HBuilderX安装路径已适配）：

```sh
node campus-market-client/tools/build.mjs all
```

其他安装位置通过UNI_COMPILER_HOME指定HBuilderX的uniapp-cli-vite目录。产物位于campus-market-client/unpackage/verified/h5、mp-weixin、app。App目录是资源，不能当作APK/IPA直接安装。

## 7. Mac桌面客户端

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

## 8. 监控、告警与接口文档

这些都是可选开发工具，先确认后端和MQ正常。监控需要本机Prometheus，面板需要Grafana，通知链路需要Alertmanager；安装和启动入口、版本及端口见下方项目指南。默认回环访问，不会自动部署公网服务。

- [指标与Prometheus](../campus-market-server/MQ_MONITORING.md)
- [Grafana与告警规则](../campus-market-server/MQ_DASHBOARD.md)
- [Alertmanager与本机通知收件箱](../campus-market-server/MQ_ALERTING.md)
- [HTTP请求日志](../campus-market-server/REQUEST_LOGGING.md)、[MQ事件日志](../campus-market-server/MQ_LOGGING.md)
- [自动接口文档](../campus-market-server/docs/API_DOCUMENTATION.md)：开发环境设置API_DOCS_ENABLED=true后重启，通过http://localhost:8080/docs.html访问；默认关闭、只允许本机。

凭证与日志均保存在被忽略的后端data目录，不需要上传。监控、文档开关与IDEA配置分别管理，运行各指南中的configure-idea前先建立对应运行配置。

## 9. 快速检查与完整回归

在仓库根目录执行：

```sh
python3 -B scripts/test_backend.py --check --suite quick
python3 -B scripts/test_backend.py --suite quick
python3 -B scripts/test_backend.py
```

quick只需要JDK17、Maven和Python3.9+，无需启动MySQL/Redis/MQ；all需要相应中间件二进制，但由工具启动临时服务，不连接真实业务库。依赖已缓存时可以加--offline。终端JAVA_HOME应指向17。

工作流位于仓库根.github/workflows，支持独立后端及多项目布局，云端使用Ubuntu24.04和Temurin17；运行结果见Actions页面。详见[测试指南](../campus-market-server/TESTING.md)与[CI说明](../campus-market-server/CI.md)。

## 常见问题

- IDEA无法识别Spring类：确认打开的是包含pom.xml的后端项目，JDK17，Maven依赖已导入。
- HTTP500或数据库未就绪：检查MySQL服务、数据库结构与运行配置中的账号；不要把真实密码写进源码。
- 手机请求localhost失败：localhost指向手机自身，开发配置应使用电脑可访问的局域网地址。
- 重开页面需要登录：当前Token只保存在内存；草稿和浏览记录按账号在本机保留。
- 空数据库没有商品或意向：初始化脚本只建结构，先注册自己的账号并创建测试商品。
