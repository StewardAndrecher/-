# 数据库发布、备份恢复与保留策略

本次增加V133～V136，不改写历史迁移。支付证据、身份关联和运维工具已实现；没有开通微信商户、真实扣款、退款到账或给卖家打款。不要把DEMO分录当成真实资金余额。

## 资金数据与状态

payment_attempts保留每次支付请求、商户订单号、渠道/商户范围、渠道交易号、金额（整数分）、CNY、状态和确认来源。一个订单只能有一条成功支付；重复请求、商户订单号、渠道交易号均有唯一约束。

payment_callback_receipts为未来验签后的回调保存事件唯一键与负载SHA256，不保存原始密文/证件/私钥。当前没有回调HTTP入口；不能通过客户端提交渠道交易号把订单改为已付款。

payment_refund_attempts与售后申请分开，保留商户退款号/渠道退款号/金额和执行状态。卖家同意仅创建CREATED记录，不计退款分录、不改变订单为已退款。真实退款确认、累计退款上限和分账回退需在已开通的渠道适配器中继续验收，数据库结构不能代替渠道执行。

finance_journals每行是一组等额借贷分录，不存在可直接修改的余额列；PAYMENT借PROVIDER_CLEARING、贷SELLER_PENDING，REFUND方向相反。DEMO/REAL账簿通过复合外键隔离；支付/退款分录有唯一键。运行账号只可读取和新增分录，不能UPDATE/DELETE。订单付款、证据和分录同一事务；任一步失败全部回滚。

历史SIMULATED成功订单按原始订单快照导入DEMO，confirmation_source=LEGACY_IMPORT，渠道交易号保持NULL，不伪造外部凭据。结算表仅预留持有/执行/成功/失败及渠道凭证，当前不自动创建或执行真实结算，不提供平台钱包或个人代收付。

对账工具database_reconcile.py接收人工核对来源的标准化JSON账单，保存文件摘要、逐行匹配、金额不符、本地缺失和渠道缺失，重复文件恢复原记录；不修改订单、资金或结算资格。它不验证账单下载来源或渠道签名。账单日期按数据库记录时区解释，生产连接固定UTC，导入前先归一化渠道日期边界。一个文件最多10000行、10MB。

## 开发、测试与生产隔离

新环境分别使用campus_development、campus_test、campus_production；独立主机/实例和独立上传目录，生产端口仅允许应用主机访问。当前Mac的campus_market保留作已有开发库，没有把它改成生产库，也没有公网开放数据库。

四类账号：runtime执行业务；migrator执行版本迁移；backup只读备份；maintenance仅清理明确的临时表。均限制到127.0.0.1和本环境库，无全局ALL、GRANT OPTION或通配主机。跨主机部署需把主机限制改成真实固定应用IP、配置网络白名单和证书校验后复核，不要直接使用%。凭据由运维保存在服务主机私有配置中。

1. 在新环境设置四个不同的24字符以上随机URL-safe密码，环境变量CAMPUS_RUNTIME_PASSWORD、CAMPUS_MIGRATOR_PASSWORD、CAMPUS_BACKUP_PASSWORD、CAMPUS_MAINTENANCE_PASSWORD。不要写进Git、聊天或命令行参数。
2. 用database_accounts.py --environment production --output /private/new-path生成私有SQL。管理员审核后执行bootstrap.sql：只创建新库和新账号，不覆盖已有对象。
3. 单独迁移进程设置MIGRATION_DB_URL、MIGRATION_DB_USERNAME=campus_production_migrator、MIGRATION_DB_PASSWORD，然后运行：

```sh
java -Dloader.main=com.campus.market.migration.DatabaseMigrate -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher campus_production
```

4. 执行runtime-after-migration.sql，赋予逐表权限。runtime不能执行DDL，不能改写/删除资金分录及审计记录；backup不能写入；maintenance不能访问资金表。
5. 使用production配置启动应用，DB_USERNAME=campus_production_runtime、DB_URL指向campus_production。应用禁止自动迁移，迁移凭据不交给应用进程。运行保护拒绝生产使用开发库、root或迁移账号。
6. 轮换/吊销迁移凭据并做发布演练；不要在现有业务库执行初始化SQL。

独立生产主机、网络白名单、跨机TLS、凭据托管、主从/高可用尚需在实际基础设施上配置和验收，本地私有实例演练不代表已拥有生产数据库。

## 真正的数据库和图片备份

数据库源码ZIP不是数据库备份。database_backup.py使用mysqldump --single-transaction保存InnoDB一致快照，同时持有campus-images-<库名>数据库建议锁，阻止应用物理清理图片。图片不可覆盖写入，SQL快照完成后复制全部对象；上传可继续，额外孤立文件允许存在。失败、丢锁、非InnoDB、未知文件、图片变化或摘要不符都拒绝成功，不清理原文件。

凭据使用当前OS用户所有的0600 MySQL defaults文件。备份目录0700，SQL/图片包/清单0600，不自动删除历史备份。manifest记录数据库、图片包及每张图的SHA256。自定义触发器/事件/存储过程须另行审核，本工具拒绝不支持的对象。发布迁移期间须暂停备份（MySQL一致事务不能保护并行DDL）。

```sh
python3 scripts/database_backup.py backup --defaults-file /etc/campus-market/backup.cnf --schema campus_production --uploads /srv/campus-market/uploads --backup /srv/campus-market/backups/new-id
```

提供campus-backup.service和.timer：Asia/Shanghai每天03:15，最多随机延迟5分钟，补跑错过的任务，非零退出报告失败，latest.json保存最近状态。需要在真实Linux主机创建campus-backup系统用户、赋予上传目录只读和备份目录写权限后安装启用。当前Mac不安装Linux服务，正式定时任务尚未启用。后续监控需对最近备份失败或超过26小时未成功告警。

保留建议：本地每日30份、异地加密每日90份、月度12份；本工具不自动执行删除。正式上线还应配置独立故障域的加密副本、容量告警、binlog与时间点恢复（PITR），按运营需求确定RPO/RTO。当前完整备份只能恢复到备份时点，不能承诺零数据丢失。

## 恢复演练

restore只允许全新campus_restore_<12位hex>，使用CREATE DATABASE且不带IF NOT EXISTS，不覆盖原库；图片必须写入新目录。先校验摘要、限制SQL切库/外部文件操作和归档路径，再恢复；再次完整导出对比SQL快照，逐张核对图片摘要，验证全部存活图片元数据一致。恢复目录和SQL留作私下验收，不能上传GitHub。

```sh
python3 scripts/database_backup.py restore --defaults-file /private/isolated-restore.cnf --schema campus_restore_123456abcdef --uploads /private/new-restore-images --backup /private/reviewed-backup
```

每月及每次重大数据库变更后在隔离实例执行演练，记录用时与差异；切换生产应用到恢复实例必须另行审核连接目标，不由此工具自动切换。失败的全新恢复库保留供检查。

## 历史数据清理

默认preview，不直接删除。预览生成10分钟有效的SHA256确认摘要；execute要求明确摘要，单次每表最多500条，重新检查资格，同一事务写维护记录。过期或被改的计划拒绝执行。

| 数据 | 保留与处理 |
| --- | --- |
| 登录会话 | 过期或撤销后30天再清理 |
| 邮件验证码挑战 | 过期7天后清理 |
| 写入频率窗口 | 7天后清理 |
| 通知投递 | 已送达且用户已读，均超过90天且无有效租约，才清理并级联通知；未读、失败、未送达保留 |
| 超时消息 | 已发布90天且订单非待支付、无有效租约，才清理 |
| 未使用图片 | 90天后人工核对引用与未同步草稿；本工具不删除图片。回收站30天后仍通过管理员预览确认 |
| 资金、订单、对账、结算和审计 | 本规则不清理；待正式运营主体与适用要求确定单独保留/归档方案，不据此声明合规 |

```sh
python3 scripts/database_retention.py preview --defaults-file /private/maintenance.cnf --schema campus_production --plan /private/retention-plan.json
# 审核计划后才执行，并填写该计划给出的fingerprint。
python3 scripts/database_retention.py execute --defaults-file /private/maintenance.cnf --schema campus_production --plan /private/retention-plan.json --fingerprint REVIEWED_SHA256
```

## 查询与容量

V136为买家/卖家+状态+创建时间/ID分页增加复合索引。database_benchmark.py只允许所属私有MySQL临时目录，创建新测试库，生成1万用户、10万商品和10万订单，比较EXPLAIN ANALYZE，记录7次热查询分位数、8并发80次只读探测和表/索引容量。它不往现有业务库灌入压测数据，不代表生产QPS或SLA。

包含关键词的LIKE '%词%'统计仍需扫描匹配范围，普通BTREE不能消除；不要声称搜索已无限扩展。已有游标连续浏览可避免深OFFSET，带页码的深分页仍有扫描成本。达到真实数据瓶颈后，再验证中文全文检索或独立搜索服务对现有搜索语义的影响。

生产慢日志建议单独配置long_query_time=0.2、log_queries_not_using_indexes=OFF、log_slow_admin_statements=OFF、min_examined_row_limit=1000、轮转与受限权限。日志可能含私人检索文本，不上传公共仓库。先采样再调整，不由应用账号修改全局数据库配置。

依据：[MySQL备份文档](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)、[微信支付退款开发指引](https://pay.wechatpay.cn/doc/v3/partner/4013080623)。
