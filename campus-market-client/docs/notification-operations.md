# 第93课：管理员通知投递概览

新增软件包0，无数据库迁移。复用Spring JDBC、现有管理员拦截器和uni-app页面状态加载器。管理中心新增“通知投递概览”，接口为GET /api/admin/notifications/overview；未登录401、普通用户403，撤销管理员权限后下一请求即拒绝，成功响应no-store。

## 统计口径

单条只读SQL以数据库时间采样notification_outbox，返回同一条语句内一致的聚合数据：

| 字段 | 含义 |
|---|---|
| deliveryEnabled | 当前应用的通知投递配置开关，不代表Broker可连接 |
| pending | delivered_at为空的全部任务 |
| unconfirmed | pending中published_at为空的任务 |
| awaitingDelivery | pending中published_at非空的任务 |
| retrying | pending中attempts大于1的任务，含租约恢复/再次投递，不等同永久失败 |
| leased | pending中lease_until仍大于数据库当前时间的任务 |
| delivered | delivered_at非空的任务累计数，不是用户已读数 |
| oldestPendingSeconds | 最早待投递任务从created_at到数据库当前时间的整秒数；空集或未来时间为0 |
| maxPendingAttempts | pending中的最大领取次数；空集为0 |
| sampledAt | 数据库采样时刻，无时区偏移，页面按服务器时间显示，不转成本机时间 |

unconfirmed + awaitingDelivery = pending。retrying和leased都是pending的子集，二者可能重叠，不可直接相加。delivered根据Outbox完成标记统计，不额外扫描Inbox，也不能用来判断用户阅读状态。

## 故障与性能边界

聚合读取失败返回服务器错误，页面清空旧结果并显示错误，不显示“0条积压”假成功。每条统计语句查询超时5秒，事务只读；不发送MQ、不领取任务、不重置租约、不插入通知、不自动清理或重放。响应只含汇总数字和采样时刻，不含收件人、用户名、正文、source_key、eventId或租约Token。

当前实现是按需全表聚合，适用于当前教学项目数据规模；没有新增索引，因为多种条件统计仍需扫描，不能靠单个索引声称解决大表聚合成本。数据量增大后应加入后台采样/汇总表及保留策略。查询超时只约束查询，不等于端到端HTTP耗时上限（连接获取等仍有自己的超时）。

## 页面和排查

管理中心进入“通知投递概览”。打开或手动刷新时采样，无定时轮询。最老待投递时长达到60秒提示排查；这里只是页面提示，不是Prometheus告警或通知服务SLO。开关关闭且有pending时提示保留任务，开启后继续投递。无pending时只提示本次采样无待投递任务，不声称队列健康。

账号/Token切换或页面隐藏时清空结果，旧请求不能回写；刷新失败时不保留旧成功状态。复用createAdminLoader，后端实时鉴权才是权限边界。页面只读，无消息删除和重放按钮。

排查顺序：核对投递开关、待投递数量与最老时长；观察两次手动采样是否下降；结合后端日志和第92课本机MQ状态查看工作/parking队列及消费者。已确认待入库增多只说明来源任务还没有完成标记，不能仅凭此断定Broker丢消息。

## 验收范围

新增15项隔离MySQL集成测试、1项API契约测试、12项前端测试，覆盖混合任务统计、重叠子集、过期租约、未来时间、完成任务排除、只读行为、无个人信息、401/403/授权撤销/退出会话、数据库故障和账号切换。写入夹具仅使用临时数据库，不在真实业务库伪造积压。

新页面使uni-app路由从24个增加到25个。Web和Mac页面验收与小程序/App编译分别记录；编译通过不代替手机运行。
