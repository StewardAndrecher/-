# 第76课：Grafana仪表盘与Prometheus告警规则

基于第75课已经采集的业务指标，新增15个Grafana面板与8条Prometheus规则。安装Grafana13.2.2及官方Prometheus数据源插件13.2.3；继续使用Prometheus3.15.0。没有新增Maven/npm依赖，没有修改Java、uni-app或Electron业务代码，也不需要业务数据库迁移。

## 职责与数据流

后端Micrometer指标 → 独立凭证保护的采集接口 → Prometheus时序存储与规则计算 → Grafana可视化。

本课告警规则由Prometheus计算，Grafana面板读取ALERTS序列。Grafana原生Alerting菜单可能没有本课规则；从仪表盘上方“Prometheus告警状态”进入可查看全部8条规则。未配置Alertmanager、邮件、微信、Webhook或其他外部通知，不自动清空队列或重投。

## 15个面板

上方8个状态面板：应用采集、MQ队列查询、Outbox采样、工作消费者、待发任务、最老等待时间、失败保留消息、MQ快照年龄。

中下方6个趋势面板：三个队列ready、Outbox待发/已尝试/租约、发送速率、消费速率、平均处理耗时、Pending/Firing告警时序。底部1个说明面板给出检查顺序。

未知值保留为未知，不使用or vector(0)伪装为零。最近一次采样成功仍需结合年龄判断；没有活动告警时面板显示当前无告警数据，完整规则状态见上方链接。没有消费调用时平均耗时可能NaN，不代表耗时为0或接口失败。未开启直方图，平均耗时不是P99。

## 告警规则

| 规则 | 条件与持续时间 |
| --- | --- |
| CampusBackendScrapeDown | 抓取失败或目标序列缺失，持续30秒 |
| CampusMqMetricsMissing | 应用可抓取，但MQ状态缺失或不是0/1，持续1分钟 |
| CampusMqBrokerUnavailable | 应用可抓取，MQ三个队列查询失败，持续30秒 |
| CampusMqSnapshotStale | 最近MQ状态成功，但样本年龄不满足小于30秒，持续30秒 |
| CampusOutboxSamplingUnavailable | 数据库采样失败、缺失或年龄不满足小于30秒，持续30秒 |
| CampusOutboxBacklog | 有待发任务且最老等待超过60秒，数据采样正常新鲜，再持续1分钟 |
| CampusMqParkingBacklog | 数据正常新鲜，parking的ready大于0，持续30秒 |
| CampusMqNoConsumer | 数据正常新鲜，timeout消费者为0，持续30秒 |

Prometheus每5秒评估；for要求表达式连续成立，不是查询窗口，也不是满足条件后立刻通知。正常→Pending→Firing；条件不成立后回到Inactive。实际观察时间还受采集和评估间隔影响，不承诺恰好第30秒触发。

所有规则限制job=campus-market，以job/instance匹配。指标缺失使用逐实例unless判断，一个正常实例不会掩盖另一个实例的缺失。积压/消费者规则同时检查采样成功和新鲜度，未知或过期数据由采样告警提示，避免误判为当前业务积压。

这些规则适用于已经启用MQ与监控的部署；主动关闭MQ功能时应同步调整对应规则。delay中等待15分钟的正常消息不会直接触发“积压故障”。

## 本机启动

macOS安装Grafana后，在后端项目目录：

```sh
python3 scripts/grafana-local.py start
python3 scripts/grafana-local.py status
python3 scripts/grafana-local.py login-info
```

默认页面：http://127.0.0.1:3000/d/campus-mq-overview 。用户名campus_admin，随机密码仅保存于data/grafana/credentials.json。登录可在本机查看该文件；不要把凭证提交Git或发到公开聊天。

Grafana只监听127.0.0.1，关闭匿名和自助注册，不启用开机服务、预安装插件或遥测。账号、会话SQLite数据库、配置、日志均在独立的data/grafana私有目录，不复用Homebrew全局数据目录。本机Prometheus数据源只访问127.0.0.1:9090，不把后端采集Token复制到Grafana。

Grafana13.2把Prometheus数据源拆成独立插件，因此首次启动脚本会从Grafana官方目录安装固定的prometheus13.2.3到项目私有plugins目录；需要联网。之后复用该版本，不自动更新，不关闭签名校验。其他预安装插件仍禁用。不能只检查数据源配置名存在，必须验证插件健康和真实查询。

数据源、仪表盘通过provisioning自动加载，固定UID为campus-prometheus、campus-mq-overview。allowUiUpdates=false，修改应更新源码JSON后重启项目Grafana；这约束配置来源，不等同于完整的运维权限隔离。

其他安装位置设置GRAFANA_BIN和GRAFANA_HOME。隔离验收可设置GRAFANA_DATA_DIR及--port/--prometheus-port。脚本遇到已有端口或未知进程归属会拒绝操作，不会停止其他实例。

停止：`python3 scripts/grafana-local.py stop`。配置、账号与SQLite数据保留；重复启动保持原凭证，不重置已有密码。

## Prometheus加载规则

更新后的monitoring-local.py会在配置中写入rule_files及5秒评估间隔。先完成规则检查，再正常重启本项目采集器：

```sh
python3 scripts/verify-monitoring.py
python3 scripts/monitoring-local.py stop
python3 scripts/monitoring-local.py start
```

本课已执行本机重启，既有时序数据和采集凭证保留；后端、MySQL、Redis、RabbitMQ不用重启。没有打开Prometheus生命周期写接口。

## 验收

```sh
python3 scripts/verify-monitoring.py
python3 scripts/verify-monitoring-runtime.py
```

promtool验证17个场景、168条规则断言和2条表达式断言，覆盖健康、短时故障、持续故障、Pending/Firing、恢复、NaN、缺失、过期、积压、消费者及多实例匹配。另验证两个运维脚本拒绝占用端口和未知PID，共4项行为检查。

隔离真实MySQL/RabbitMQ/Prometheus验收使用本课原始30秒规则：停止测试MQ后看到Pending和Firing并截图；恢复后告警解除，任务重试、订单关闭、商品释放，系统超时记录仅一次。没有停止真实MQ或修改真实交易。测试订单TTL200毫秒并只调整测试订单时间，真实业务仍15分钟。

本课不修改Java业务，保留第75课792项通过的基线，不重复宣称本课重跑792项。真实Grafana页面、数据源查询、规则加载、身份保护及本机监听另做验收。

## 边界

本机单节点演示，不证明线上高可用或容量。Prometheus自身停止时无法靠自身规则发出告警，外部存活检查与通知路由后续完善。仪表盘数据来自当前采集目标，不是交易审计账本。外网部署需另行配置网络隔离、HTTPS、账号权限和凭证管理。

## 官方资料

- [Grafana文件化配置](https://grafana.com/docs/grafana/latest/administration/provisioning/)
- [Prometheus规则配置](https://prometheus.io/docs/prometheus/latest/configuration/recording_rules/)
- [Prometheus规则测试](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)

第77课加入告警通知链路，见[告警分组、去重与恢复通知](MQ_ALERTING.md)。
