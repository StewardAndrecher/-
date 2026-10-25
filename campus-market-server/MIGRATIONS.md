# 第85课：Flyway数据库版本管理

## 本课范围

新增spring-boot-starter-flyway与org.flywaydb:flyway-mysql。版本由Spring Boot4.1.1管理，Flyway实际为12.4.0。迁移默认关闭，不给当前业务库自动创建历史表、baseline或修改数据；新数据库明确开启后才执行。

## 为什么需要版本管理

Flyway按V版本号顺序执行classpath:db/migration内的SQL，把版本、脚本名、checksum与成功状态记入flyway_schema_history。再次启动只执行未应用的版本；已应用脚本被修改时校验失败，防止不同环境悄悄使用不同结构。

本课将已有25份历史结构SQL转换成V1、V15、V19、V21…V74，保留历史编号方便追溯，版本可以有间隔。SQL内容保留原DDL/DML，去掉CREATE DATABASE和USE，所有操作针对数据源选择的数据库。历史sql目录保留，兼容第84课手动初始化交付。

迁移文件一旦应用便冻结；新增结构用新的更高版本文件，不修改旧文件、不重命名旧文件，不通过自动repair掩盖校验失败。

## 新空库如何使用

选择下列一种初始化方式，同一个数据库不要混用。

### Flyway方式

1. 仅创建空数据库，例如CREATE DATABASE campus_market CHARACTER SET utf8mb4；不要执行第84课database/init.sql。
2. 按RELEASE.md准备外置配置与DB_URL/DB_USERNAME/DB_PASSWORD、独立登录保护Redis等运行条件。
3. 在自己的运行环境设置DB_MIGRATION_ENABLED=true后启动本课JAR。
4. 首次启动执行26个版本（初始25份加V87），得到14张业务表和1张flyway_schema_history；确认健康、迁移历史和业务接口后再使用。

```sh
export DB_MIGRATION_ENABLED=true
java -jar /absolute/path/to/app.jar \
  --spring.config.additional-location=file:/absolute/path/to/runtime/application.properties
```

数据库需预先存在，应用不会执行CREATE DATABASE。以后对这个已受管数据库保留开启状态，启动时验证已有历史并执行后续版本。

### 原手动方式

继续按第84课执行database/init.sql，保持DB_MIGRATION_ENABLED未设置或false。本课仍支持此方式；手动初始化的非空库没有Flyway历史，不能直接切换成true。

## 当前已有业务库

第86课新增独立的显式接管工具，先比对14张表、额外对象及25份冻结SQL，再使用目标库指纹执行baselineVersion=74。详细操作、维护窗口及失败边界见[LEGACY-BASELINE.md](LEGACY-BASELINE.md)。默认启动仍不自动baseline，不使用baseline-on-migrate=true跳过核对。

关闭迁移时，健康readiness仍按原有规则检查数据库连接，不意味着自动校验全部业务表结构。

## 配置

```properties
spring.flyway.enabled=${DB_MIGRATION_ENABLED:false}
spring.flyway.locations=classpath:db/migration
spring.flyway.baseline-on-migrate=false
spring.flyway.clean-disabled=true
spring.flyway.validate-on-migrate=true
spring.flyway.validate-migration-naming=true
spring.flyway.fail-on-missing-locations=true
spring.sql.init.mode=never
```

不混用Spring基础SQL自动初始化。Flyway与应用使用同一个DataSource，连接变量、账号权限由运行环境提供；不要另设flyway.url/user去指向未经核对的数据库。配置项是默认行为，不是数据库授权边界；运行账号权限仍需由部署者管理。

## MySQL DDL失败的边界

MySQL的部分DDL会隐式提交。某个迁移先建表、随后SQL失败时，已提交的DDL可能保留，不能宣称整批迁移会自动回滚。Flyway会记录失败并阻止继续启动；需要备份、排查实际结构与历史后人工制定修复方案。本课不自动repair、不清库、不自动重试失败DDL。

## 验收

新增10项真实MySQL集成测试：首次全部版本、重复执行、分阶段升级保留商品、checksum变化、非空未受管库拒绝、clean禁用、DDL部分失败记录、重复版本、并发迁移和约束保留。只在测试入口创建的私有MySQL中运行，并额外验证@@datadir包含测试目录身份。

```sh
python3 -B scripts/test_backend.py --offline
```

第85课入口为853项、60类；第86课增加26项后为879项、62类；第87课再增加10项，完整入口为889项、63类；quick仍为73项，不包含真实MySQL迁移测试。未通过完整入口直接运行Maven时，环境保护可能跳过该集成类，不应将跳过算作完整通过。

本课还用发布JAR实测Spring Boot自动迁移、再次启动、旧手动库的默认关闭、非空库拒绝与checksum启动失败，确认不只是调用Flyway Java API。当前实测Mac ARM64、MySQL9.6.0；未声明所有数据库版本或公网环境均已验收。

参考：[Spring Boot初始化与Flyway](https://docs.spring.io/spring-boot/how-to/data-initialization.html)、[Flyway MySQL模块](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql)、[baseline-on-migrate](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting)。


第87课新增[V87性能迁移](PERFORMANCE.md)，25份历史SQL保持冻结。新空库26条SQL历史、版本87；已有V74基线库补V87后为2条历史。旧库接管仍固定V74，更高版本保持待执行，不能提高baseline来跳过。


第101课新增V101__order_reviews.sql，旧30份SQL不变。全新库31份迁移、23张表（含历史）；现有92库仅执行101，新增空order_reviews。不回填历史评价。


## 第104课：交易问题反馈

新增用户侧反馈提交及本人记录，尚未接管理员处理或退款。新增V104空表迁移，新库32份迁移；旧31份迁移不变。完整说明见 ORDER-ISSUES.md。


## 第105课：反馈处理与结果通知

管理员可逐条答复或不予支持；本人记录显示处理结果，可靠站内通知仅提醒该提交人。不改变交易或执行退款。新增V105空结果表，旧32份迁移不变，新库33份迁移。完整说明见 ORDER-ISSUE-RESOLUTIONS.md。
