# 第114课：注册保护可观测性

复用现有 Micrometer、Prometheus、Grafana、Alertmanager 和本机技术告警收件箱；不新增软件包、SQL迁移、Redis连接或用户权限。

## 指标语义

- `campus_registration_protection_enabled`：本进程生效的开关，1开启、0关闭。
- `campus_registration_protection_limit` / `campus_registration_protection_window_seconds`：本进程生效的每连接地址窗口上限和长度。多实例需保持同一策略。
- `campus_registration_decisions_total{outcome="allowed|limited|unavailable|disabled"}`：每次进入保护判断只增加一个结果。allowed 仅表示获准进入注册服务，后续仍可能400、409或500；不能当注册成功数。JSON绑定失败未进入保护判断，不计入。
- 三个策略Gauge和四个Counter预先注册。监控关闭时不创建这些指标；注册保护继续运行。
- 唯一业务标签是固定四值 `outcome`；不记录IP、地址哈希、用户名、密码或令牌。Prometheus自行附加job/instance。
- Counter属于Java进程，重启归零；配额属于共享Redis，并不会因指标归零而重置。多实例聚合先rate再sum。
- scrape只读内存，不访问Redis或数据库；零故障、无流量、保护关闭均不能证明Redis健康。此课没有添加主动Redis健康探测。

## 面板与告警

Grafana：`http://127.0.0.1:3000/d/campus-registration-protection`，8个面板，按实例查看策略、放行、普通限流、保护故障和累计判断。无数据保留“未知”，不补零。

Prometheus通过已有独立Bearer凭据采集 `/api/ops/metrics`；不把监控凭据放到前端或URL。启动脚本载入 `monitoring/registration-alerts.yml`，Grafana启动脚本复制新面板。

- `CampusRegistrationMetricsMissing`：应用可采集，但策略或任意一种结果指标连续1分钟不完整。
- `CampusRegistrationProtectionUnavailable`：保护开启，近5分钟发生保护判断故障，且条件持续30秒。最后一次故障离开5分钟窗口后才恢复；不是Redis即时健康信号。
- 普通429不触发技术故障通知。业务请求异常仍按原逻辑429/503返回，监控不改变保护决策。
- Alertmanager按job/instance/component分组，本机收件箱接受registration分类；应用采集失败仅抑制同实例registration等下游告警，不跨实例抑制。

## 本机更新与验证

全新启动按 `scripts/monitoring-local.py start`、`scripts/grafana-local.py start`、`scripts/alerting-local.py start` 使用现有流程。若服务已启动，start不会覆盖运行中配置：先备份运行配置，再对照源码增量更新配置并校验，向确认归属的Prometheus/Alertmanager发送SIGHUP，复制面板到现有Grafana provisioning目录；保留凭据、TSDB和收件历史。

`python3 scripts/verify-monitoring.py` 验证全部PromQL规则与面板；`python3 scripts/test_backend.py --suite quick --offline` 包含RegistrationMetricsTest；完整all回归包含真实隔离Redis下的判断与指标联动。`python3 scripts/verify-alerting.py --output /tmp/campus-alerting-check.json` 验证独立临时目录和端口上的真实告警分组、去重、恢复以及registration分类。不停止真实Redis做故障演练。
