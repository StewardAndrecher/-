> 当前入口与统计请看仓库根目录的 [验收记录](../docs/VERIFICATION.md) 和 [运行指南](../docs/GETTING_STARTED.md)。下文保留历次教学记录，数字对应当时版本。

# 第82课：快速验收分层与GitHub Actions配置

## 三种验收范围

| 命令 | 当前范围 | 启动MySQL/Redis/MQ |
| --- | --- | --- |
| --suite quick | 8个已审核的基础测试类，73项 | 否 |
| --suite docs | 3个文档测试类，20项 | 是 |
| 默认all | 自动发现的全部63类，889项 | 是 |

quick与all覆盖范围不同，不能把quick成功当作全部业务已通过，也不能用耗时差宣称相同任务加速。quick会编译项目主代码和测试代码，实际只执行明确列出的8类测试。

## 可以直接运行

```sh
python3 -B scripts/test_backend.py --check --suite quick
python3 -B scripts/test_backend.py --offline --suite quick
python3 -B -m unittest discover -s scripts/tests -v
```

quick预检只要求JDK17和Maven，不会搜索MySQL、Redis或RabbitMQ二进制。终端JAVA_HOME应指向17；IDEA项目SDK与终端环境是两个配置。首次缺少Maven依赖时去掉--offline。

构建仍在临时源码副本完成，不会清理IDEA正在使用的target/classes。结果仍保存到data/test-results；summary.json中的suite/expectedTestClasses/requiresMiddleware会明确本次范围。失败、错误、跳过、遗漏报告和空报告仍不算通过。

## 为什么分层

- quick：密码哈希、HTTP请求日志、文档访问过滤、MQ日志/指标/投递确认规则、指标接口；使用Mock或内存对象，不启动外部服务。
- docs：文档生成、认证说明、接口契约，需要隔离测试库。
- all：真实数据库、缓存、登录限流、订单事务、MQ集成等完整回归。

QUICK_CLASSES是经过人工审查的明确名单。新增quick测试需确认无需外部服务；入口也会拒绝包含@SpringBootTest的quick类，但这只是显式配置护栏，不是通用Java代码的网络沙箱。

## 新增文件与修改

- scripts/backend_quick_runtime.py：专门的无中间件执行分支，复用源码快照、Maven进程组清理与JUnit校验。
- scripts/backend_test_support.py：quick名单、分层预检，Maven停止确认回调。
- scripts/test_backend.py：新增--suite quick并选择对应执行器。
- scripts/tests/test_backend_tools.py：从20项增至32项，检查quick范围、缺失类、预检依赖、中断与不确定清理等。
- .github/workflows/backend-quick.yml：GitHub Actions工作流配置。

## 工作流的放置位置

GitHub仅从仓库根目录的.github/workflows加载工作流。你的仓库是多项目布局，上传时应是：

```text
.github/workflows/backend-quick.yml
campus-market-server/pom.xml
campus-market-server/scripts/test_backend.py
campus-market-client/
campus-market-desktop/
```

本后端目录内保存的同名文件可用于独立后端仓库；多项目仓库必须把它放到仓库根.github/workflows，不能只保留在campus-market-server/.github下。课程另提供包含根目录结构的工作流压缩包，上传时需与本课后端代码一起更新。

工作流会自动识别后端在仓库根还是campus-market-server子目录。触发事件为push、pull_request和手动workflow_dispatch；在ubuntu-24.04执行：

1. Checkout源码，关闭持久化Git认证。
2. 安装Temurin JDK17，使用Maven依赖缓存。
3. 运行32项Python工具检查。
4. 预检quick所需依赖。
5. 运行quick Java测试。
6. 无论测试成功或失败，尽可能上传摘要、Maven日志和JUnit报告，保留7天。

权限仅contents: read，不需要添加数据库密码、MQ密码或部署凭证。工作流本身不部署应用、不修改仓库或分支保护。要强制通过检查才能合并，还需要单独配置仓库分支保护。

## 验收范围与尚未执行的部分

本课在Mac上真实执行quick与完整回归，工具测试通过；工作流通过actionlint静态校验，Actions引用固定为官方发布的40位提交SHA。

云端首跑必须先把本课代码与根目录工作流推送到GitHub。本课没有推送，也没有把本机结果描述为Linux云端成功。首次云端执行后，应查看Actions页面和下载的JUnit报告再确认。

GitHub官方工作流语法：https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
官方Ubuntu镜像清单：https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md

完整模式如果在中间件启动阶段失败，会在该次结果目录的runtime-logs保留最多每份128KiB的启动日志；配置、Cookie和数据库文件不在此日志留存范围。日志随临时服务结束前保存，仍仅保留本机。


第86课：已有数据库显式接管见[LEGACY-BASELINE.md](LEGACY-BASELINE.md)。先check核对14张表与25份冻结SQL，再显式baseline到74；默认应用启动不接管旧库。新增26项测试，完整63类889项，quick仍8类73项。


第87课：最新列表先分页再关联，新增首页及分类复合索引V87；见[PERFORMANCE.md](PERFORMANCE.md)。新库26份迁移，V74基线库只补1份；完整63类889项，quick仍73项。

第94课：新增通知指标与采样器两个无需中间件的单元测试类，已加入quick明确名单。当前quick为10类89项，完整all为74类1106项。新增监控集成测试仅使用隔离MySQL/RabbitMQ；详见[NOTIFICATION-MONITORING.md](NOTIFICATION-MONITORING.md)。
