# 第84课：后端可执行JAR与独立发布包

## 目标与依赖

用现有Spring Boot Maven插件生成包含运行依赖的可执行JAR，在没有IDEA、源码编译目录或Maven参与启动的情况下运行后端。没有新增Maven/npm业务依赖；构建需要Python3.9+、JDK17和Maven，运行JAR需要JDK17及对应业务基础设施。

本课没有安装Docker、创建容器、部署公网或设置开机服务。

## 构建与校验

在后端目录执行：

```sh
python3 -B scripts/build_release.py --check
python3 -B scripts/build_release.py --offline
```

终端JAVA_HOME应指向JDK17。首次依赖未缓存时去掉--offline。入口调用同一组8类73项quick测试，Maven使用clean package；JUnit完整且失败/错误/跳过均0，才允许生成发布包。完整989项回归仍按TESTING.md单独执行，不把quick描述为完整业务回归。

构建在私有临时源码副本完成，不会清理IDEA的target/classes，不启动MySQL/Redis/MQ、不继承真实DB/MQ/Spring/JVM注入环境。失败或中断保留本次私有日志；无法确认Maven进程停止时保留临时目录并返回失败。kill -9或断电不保证清理。

结果位于已忽略的data/releases：

```text
data/releases/
  latest.json
  runId/
    summary.json
    maven.log
    junit/TEST-*.xml
    bundle/
      app.jar
      database/init.sql
      config/application.properties.example
      RUN.md
      manifest.json
      SHA256SUMS
    release.zip
```

每次使用独立runId，不覆盖旧包。summary状态为passed、临时构建目录清理完成后，才算本次成功。ZIP包含顶层campus-market-server目录及上述6个交付文件，不包含日志、JUnit、实际密码、数据库内容或图片。

manifest.json记录发布ID、UTC构建时间、源码快照摘要、73项测试结果与4个载荷文件的SHA256；SHA256SUMS再覆盖载荷与manifest。它不包含自身哈希，避免循环依赖。摘要不是数字签名，也不承诺不同构建时间生成字节相同的包。

验证已生成的bundle目录，无需Maven/JDK参与校验：

```sh
python3 -B scripts/build_release.py --verify /absolute/path/to/bundle
```

入口会拒绝缺失、多余、被修改的文件、符号链接、错误的清单与测试依赖。也检查JAR启动器、主类、配置、运行依赖和ZIP条目完整性。结构检查不能替代真实启动，因此本课还实际运行JAR进行HTTP验收。

## 空库初始化

补回sql/00-create-database-and-products.sql，内容与已有公开仓库的基础脚本一致；与其余24份结构SQL按明确顺序拼成database/init.sql。它没有新增业务表设计，没有给现有业务库执行迁移，也不包含数据。

init.sql只用于全新且尚不存在的campus_market数据库。已有库按迁移记录升级；禁止反复初始化。Java启动不会自动执行此SQL。

## 外置配置

release/application.properties.example会随包交付，包含变量占位符。把它复制到发布包外的runtime/application.properties，提供：

- DB_URL、DB_USERNAME、DB_PASSWORD：数据库连接。
- UPLOAD_DIR、APP_LOG_FILE：可写的绝对路径，目录需预先准备。
- 如启用MQ，提供原有RABBITMQ_*与ORDER_MQ_ENABLED等变量。
- 独立登录保护Redis仍默认开启，按原配置准备服务或LOGIN_REDIS_*变量。

外置示例默认关闭模拟付款、开发文档、独立指标采集与MQ；它是本机发布启动示例，不是公网完整部署配置。默认回环绑定，端口8080；与IDEA并行验收时应改用空闲端口。

```sh
java -jar /absolute/path/to/bundle/app.jar \
  --spring.config.additional-location=file:/absolute/path/to/runtime/application.properties
```

不把密码写进Java命令、JAR或发布包。缺失必需配置文件应启动失败；UPLOAD_DIR与APP_LOG_FILE独立于JAR位置，换包不应携带或覆盖运行数据。完整操作说明会放进发布包RUN.md。

## 本课验收范围

构建后以独立进程执行java -jar，连接私有临时MySQL，执行整合SQL并检查14张空表，再验证健康、商品查询、注册登录和受保护接口；凭证与夹具仅在临时环境。验收为隔离启动检查，不代表完整989项回归或生产部署。

smoke环境会关闭缓存、登录限流、MQ与定时补偿，以聚焦JAR、外置配置和数据库连通性；不会把关闭这些组件的检查描述为全栈基础设施验收。发布示例仍保持登录保护开启，实际运行需准备对应Redis。

本课没有自动上传GitHub、上传制品或新增云端发布工作流。现有前端、桌面端和业务数据库保持不变。

## 第85课补充：Flyway新库初始化

新发布包已包含Flyway与26份版本迁移。默认关闭；选择Flyway时只创建空数据库，不执行本包init.sql，通过DB_MIGRATION_ENABLED=true显式开启。原手动方式继续可用。非空旧库没有迁移历史时拒绝自动接管，详见[MIGRATIONS.md](MIGRATIONS.md)。完整回归现为989项，quick仍73项。

Spring Boot参考：[可执行归档打包](https://docs.spring.io/spring-boot/maven-plugin/packaging.html)、[外置配置](https://docs.spring.io/spring-boot/reference/features/external-config.html)。


第86课：已有数据库显式接管见[LEGACY-BASELINE.md](LEGACY-BASELINE.md)。先check核对14张表与25份冻结SQL，再显式baseline到74；默认应用启动不接管旧库。新增26项测试，完整67类989项，quick仍8类73项。


第87课：最新列表先分页再关联，新增首页及分类复合索引V87；见[PERFORMANCE.md](PERFORMANCE.md)。新库26份迁移，V74基线库只补1份；完整67类989项，quick仍73项。


第88课：新增公开连续浏览API `/api/products/feed` 与uni-app连续浏览页，使用 `(created_at,id)` 双键游标和多取1条判尾，无总数和深OFFSET。复用V87索引，无新迁移或依赖。完整67类989项，专项25项；前端新增12项并发、重试与游标协议检查。见 [CURSOR-PAGINATION.md](CURSOR-PAGINATION.md)。


第89课：加入ADMIN实时授权、管理路由统一拦截、本机授权/撤销审计及只读管理中心。见 [ADMIN-ACCESS.md](ADMIN-ACCESS.md)。无新依赖；27份迁移、新增2表。完整67类989项，发布quick仍73项。


第90课：新增发布后商品巡查、平台下架限制、卖家复核、解除限制和商品证据审计。见 [PRODUCT-MODERATION.md](PRODUCT-MODERATION.md)。28份迁移、18张业务表；完整67类989项，专项74项，quick仍73项。

第91课发布包新增第29份初始化SQL，V91创建product_reports；已有库由Flyway只追加91。

第92课新增V92通知双表，手动初始化共30份SQL；未来业务事务写通知任务，MQ开启需允许本项目notification命名空间。
