# 第86课：已有数据库显式接入Flyway

## 本课解决的问题

第85课的新空库可以执行25份迁移；已有14张业务表的数据库不能直接重复执行建表SQL。本课提供独立工具，在核对真实结构之后，通过Flyway.baseline()明确记录版本74。该操作只创建flyway_schema_history及一条BASELINE历史，不执行25份历史DDL，不修改业务表或业务行。

baseline表示“已经核对到这个版本”，不是备份，也不是补执行历史SQL。版本≤74会被跳过；未来新增结构使用更高版本的V脚本，再通过正常migrate升级。

## 新增内容

无需新增Maven/npm软件包，复用第85课Flyway及JDBC。独立入口：

- src/main/java/com/campus/market/migration/LegacyDatabaseBaseline.java
- src/main/resources/db/baseline/legacy-v74.properties
- 两个LegacyDatabaseBaseline测试类，12项单元测试及14项真实MySQL测试。
- pom.xml明确Spring Boot主类，保证额外的工具main方法不影响java -jar启动应用。

工具不暴露HTTP接口，不在应用启动时调用。默认迁移开关仍为false，baseline-on-migrate仍为false，clean仍禁用。

## 核对范围与适用条件

本工具仅支持显式端口的127.0.0.1或localhost MySQL，schema必须与参数完全一致。数据库账号必须拥有该schema完整的元数据可见性，以及创建Flyway历史表的权限；推荐由数据库所有者在维护窗口执行，暂停其他管理员的DDL。

冻结结构由全部25份迁移在独立MySQL9.6.0中生成。检查14张表的SHOW CREATE TABLE完整定义，保留列、默认值、字符集、索引、外键、CHECK和表选项，只忽略表级AUTO_INCREMENT当前计数器。拒绝缺表、多表、视图、触发器、存储例程或事件。不同MySQL版本可能因DDL显示格式不同而被保守拒绝；不能直接删除检查或强行接管，需重新审查版本兼容性。

本机MySQL9.6中，ALTER TABLE ... AUTO_INCREMENT还可能重写CHECK表达式的字符集表示，因此不能把该DDL假定为“只改计数”。测试通过插入数据推进自增，确认真正的运行计数变化不影响指纹；其他DDL表示变化仍保守拒绝，不自动改写约束。

同时逐一检查25份SQL的SHA-256；工具不会根据“最大的版本号”猜baseline版本。第87课起允许V87等更高版本保持待执行；基线工具固定target=74、baselineVersion=74，只核对冻结前缀，不执行未来SQL。正常应用启动再migrate到87。不要提高baseline版本来跳过尚未执行的结构变更。25份已有迁移保持冻结。

只读check输出schema、版本、表数和fingerprint；指纹包含server_uuid、schema及结构，不包含密码或业务数据。结构相同的两个数据库也不能互用指纹。业务行和自增计数器变化不影响结构指纹，本工具不检查业务数据的完整性。

GET_LOCK防止本工具的并发操作；它不会阻止外部管理员执行DDL。建立基线前会重新检查结构，但MySQL DDL无法用这里的事务实现原子核对与接管，仍需维护窗口。

## 操作步骤

1. 先备份数据库、上传文件与源码，并验证备份可读。不要在未核对的生产库操作。
2. 使用第84课的发布入口构建本课app.jar，提供既有DB_URL、DB_USERNAME、DB_PASSWORD环境变量。密码不放命令行或代码中。
3. 先运行check，人工核对schema与输出。

```sh
java -Dloader.main=com.campus.market.migration.LegacyDatabaseBaseline \
  -cp /absolute/path/to/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  check campus_market
```

检查成功输出示意（fingerprint以真实结果为准）：

```json
{"status":"CHECKED","schema":"campus_market","baselineVersion":"74","businessTables":14,"fingerprint":"64位真实摘要","historyRows":0}
```

4. 使用刚才的真实指纹，执行明确的baseline命令：

```sh
java -Dloader.main=com.campus.market.migration.LegacyDatabaseBaseline \
  -cp /absolute/path/to/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  baseline campus_market <上一步真实fingerprint>
```

成功为BASELINED、historyRows=1。Flyway日志也会输出到控制台，最后一行为工具结果JSON。重复执行会拒绝已有历史，不会自动删除或重建。

5. 核查历史及业务表：

```sql
SELECT version, type, description, success FROM flyway_schema_history;
```

应有一条version=74、type=BASELINE、success=1；业务行及原业务表定义保持一致。

6. 在之后的应用运行环境设置DB_MIGRATION_ENABLED=true，继续使用原外置配置启动。首次受管启动验证历史，第87课已有待执行V87时会执行这一份；没有新版本则执行0份，以后正常升级。IDEA环境变量与终端环境变量独立，分别配置。本课不悄悄修改IDEA环境或重启既有8080进程。

## 失败处理与校验边界

错误指纹、错误目标、缺列/约束或额外对象均在写历史之前拒绝。连接失败不会打印驱动异常中的凭证；普通失败退出码2。

Flyway创建历史表也属于MySQL DDL。若创建表后写历史或后续校验失败，可能留下历史表，需核对后人工处理；不要自动重试、repair、clean或删表。保留备份与现场日志。

旧库只有一条BASELINE记录，没有25条历史SQL checksum。普通Flyway validate不会替旧库回溯验证baseline之前的SQL内容，也不会持续发现所有人工DDL漂移。本课的接管工具会验证冻结SQL和结构；迁移之后仍应执行代码审查、备份和必要的结构检查。

## 验收

```sh
python3 -B scripts/test_backend.py --offline
python3 -B scripts/build_release.py --offline
```

完整all为63类889项；quick仍8类73项。新测试在私有MySQL验证只读check、显式基线、重复执行、错误指纹、缺列/CHECK/外键、多表、视图、触发器、并发锁、结构变化、目标身份，以及接管后真实V87及临时V88迁移保留原商品。V88仅为测试临时文件，不进入正式迁移目录。

参考：[Flyway baseline命令](https://github.com/flyway/flyway/blob/main/documentation/Reference/Commands/Baseline.md)、[Redgate基线说明](https://www.red-gate.com/hub/product-learning/flyway/flyways-baseline-command-explained-simply/)。


第87课：最新列表先分页再关联，新增首页及分类复合索引V87；见[PERFORMANCE.md](PERFORMANCE.md)。新库26份迁移，V74基线库只补1份；完整63类889项，quick仍73项。
