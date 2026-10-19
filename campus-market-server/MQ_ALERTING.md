# 第77课：告警分组、去重和恢复通知

前置：第75课指标采集、第76课Grafana仪表盘和8条Prometheus规则。

本课链路：后端指标 → Prometheus规则Pending/Firing → Alertmanager路由/分组/去重/抑制 → 本机Webhook → SQLite通知历史。

## 新增软件和文件

- Alertmanager **0.34.1**（官方固定发行包，包含amtool）；安装脚本核对同一官方发行页的SHA-256清单。不注册Homebrew/系统开机服务。SHA校验检测下载完整性，不等同于独立发行签名。
- Python3标准库：http.server、sqlite3、urllib、subprocess，无新增pip、Maven或npm依赖。
- `scripts/install-alertmanager.py`：支持macOS/Linux、arm64/amd64；已有安装拒绝覆盖。
- `scripts/alerting-local.py`：初始化、配置校验、启动、状态、停止；只管理本项目配置路径对应的进程。
- `scripts/alert-receiver.py`：本机接收器；Bearer校验、JSON校验、落库与只读页面。
- `scripts/verify-alerting.py`：临时目录和独立端口的真实Alertmanager验收。
- `monitoring/alertmanager.yml.template`：无凭证路由模板。
- `monitoring/alert-inbox.html/css/js`：通知收件箱。
- 修改`scripts/monitoring-local.py`：增加Alertmanager目标及`--alertmanager-port`（默认9093）。
- 修改`.gitignore`：排除`data/alerting/`；`MQ_DASHBOARD.md`增加本课入口。

不涉及业务表迁移、Java业务逻辑、uni-app业务页面或Electron代码。

## 在后端目录运行

```sh
python3 scripts/install-alertmanager.py
python3 scripts/alerting-local.py start
python3 scripts/alerting-local.py status
```

已有本课安装时跳过安装；源码不含二进制，换电脑需要重新安装。代理被显式禁用；下载需要访问GitHub官方发行页。

让已经运行的Prometheus读取新增目标，需要停止并重新启动采集器；保留时序数据和凭证：

```sh
python3 scripts/monitoring-local.py stop
python3 scripts/monitoring-local.py start
```

访问：

- 告警收件箱：http://127.0.0.1:9094/
- Alertmanager：http://127.0.0.1:9093/
- Prometheus：http://127.0.0.1:9090/alerts
- Grafana：http://127.0.0.1:3000/

所有新增监听仅在127.0.0.1；Alertmanager集群监听禁用。两个新增本机技术界面无登录；收件箱页面只读，Webhook写入需要独立Bearer凭证，拒绝Origin。Host限制防止任意域名读取收件箱。Alertmanager本机API仍可管理告警/静默，不应将9093转发到公网。

```sh
python3 scripts/alerting-local.py check  # 需先stop，避免覆盖运行配置
python3 scripts/alerting-local.py stop
```

自定义端口：`alerting-local.py start --port 19093 --receiver-port 19094`，配套`monitoring-local.py start --alertmanager-port 19093`。修改已运行配置前先stop。环境变量`ALERTING_DATA_DIR`用于独立运行数据目录，`ALERTMANAGER_BIN`指定含同目录amtool的安装位置。

## 配置为何这样写

```yaml
route:
  receiver: discard
  group_by: [job, instance, component]
  group_wait: 10s
  group_interval: 30s
  repeat_interval: 4h
  routes:
    - matchers: ['job="campus-market"']
      receiver: local-history
```

1. `job + instance + component`定义一组：同一实例MQ组件的多个告警合并通知，不混合不同实例和组件。
2. `group_wait=10s`：新组首次通知前等10秒，收集同时发生的告警。因此规则达到Firing后，通知还需分组等待。
3. `group_interval=30s`：后续按30秒周期检查该组变化，包括恢复；并非每30秒无条件再发送。
4. `repeat_interval=4h`：组内持续且未变化的故障，4小时后再次提醒；重复告警刷新本身不会每次产生通知。单实例去重日志保留120小时并持久化，正常停止/启动保留去重数据；清除数据会失去该历史。
5. `send_resolved=true`：恢复也通知；部分恢复时组状态可能仍为firing，逐条告警各自有status。只有组内全部恢复，整组status才是resolved。
6. 默认`discard`丢弃非本项目job的通知；只转发`job=campus-market`。
7. 当同实例`CampusBackendScrapeDown`激活时，抑制该实例mq/outbox派生告警。不会抑制其他实例。现有规则本身也有`up`保护，两层作用不同。

原有8条规则及其持续时间保持原样。生产MQ故障规则仍为30秒，不因验收而缩短；隔离业务订单TTL可加速到200ms，与生产15分钟配置区分。

## Webhook与SQLite

- 独立64位十六进制随机Token，私有文件600；与后端采集Token不同。
- 只接受`POST /webhook`、JSON、单个Authorization及Content-Length；请求体最多256KiB、告警1～100条。
- 校验version=4、firing/resolved一致性、项目job、组件、严重性、fingerprint、带时区时间戳（兼容RFC3339Nano的1～9位小数）；只保留技术标签与summary/description。丢弃externalURL、generatorURL及未知字段。
- SQLite事务插入成功且提交完成后返回200；保存失败503，允许Alertmanager重试。JSON校验失败400；未授权401；超限413。
- 每次接收都记录一条投递，保留最新1000条；页面显示最近100条，不提供删除/修改接口。groupKey不是永久唯一投递ID：同一组可以有故障、更新、重复提醒和恢复。因此不把groupKey设为UNIQUE，也不永久丢弃相同内容。
- 展示使用DOM `textContent`，不把上游说明当HTML执行；CSP、no-store、nosniff，无第三方脚本。
- 页面“最近一次通知”是历史投递状态，不等同于所有服务当前健康状态；查看Alertmanager确认当前告警。
- 无上游告警时收件箱为空，这是正常结果，不会生成演示通知来填充实际数据。

`data/alerting/`包含安装文件、凭证、运行配置、PID、日志、SQLite及Alertmanager存储，全部不提交Git。目录700，凭证和数据库600。该收件箱用于本机教学与技术告警，不是长期审计或生产值班系统。

## 实际联调发现的问题

真实上游使用RFC3339Nano格式，会按需去掉末尾零，本课实测时间戳为`.88Z`（2位小数）；本机Python3.9的`datetime.fromisoformat`只支持3位或6位小数。接收器先将1～9位小数归一化为6位用于格式验证，保存时仍保留原始时间字符串。新增9位/5位小数回归，再用真实MQ故障重跑整条链路。仅靠手工API样例无法保证与真实上游完全兼容。

## 验收命令与边界

```sh
python3 scripts/verify-monitoring.py
python3 scripts/verify-monitoring-runtime.py
python3 scripts/verify-alerting.py
```

`verify-alerting.py`启动隔离的真实Alertmanager和接收器，测试同组2告警合并、跨group_interval的重复刷新去重、跨实例分组、应用故障抑制、全组恢复、重启历史保留、错误请求及占用端口/伪造PID保护。它会占用约两分钟，退出时停止自己的进程并删除临时数据。

另外本课用独立MySQL/RabbitMQ/后端/Prometheus/Alertmanager进行真实故障演练：停止测试MQ、下单201、Outbox等待、原30秒规则Firing、收到故障通知、恢复MQ、自动补发并超时关单、收到恢复通知。验收测试数据不写入原有业务库。

这仍是单机告警链路：Alertmanager、Prometheus、SQLite和本机接收器都可能成为单点；没有实际手机推送、外部值班平台或高可用承诺。若接收器离线，上游会按自身重试机制重试，不能承诺无限离线期间所有变化必达。

官方资料：[Alertmanager配置](https://prometheus.io/docs/alerting/latest/configuration/)、[告警分组/抑制/静默概念](https://prometheus.io/docs/alerting/latest/alertmanager/)、[官方发行版](https://github.com/prometheus/alertmanager/releases/tag/v0.34.1)。
