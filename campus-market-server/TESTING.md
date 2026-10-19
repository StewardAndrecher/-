# 第81课：项目自带的一键隔离后端验收

## 本课解决的问题

原有完整验收入口依赖课程工作目录、早期SQL附件和手工拼接的测试类名单。本课把入口、MySQL历史结构文件、临时MQ工具和报告校验放进后端项目。拿到后端目录即可检查环境并运行测试，不再依赖Codex目录或某台电脑的用户名路径。

没有增加Maven/npm依赖，使用Python标准库与已安装的Java、Maven、MySQL、Redis、RabbitMQ。当前实际验收环境是macOS ARM64、JDK17；脚本按POSIX编写，Linux尚未做实际运行验收，Windows原生不在本课范围。

## 第82课补充：快速分层

新增`--suite quick`：预检只需要JDK17和Maven，执行8类基础测试（当前73项），不启动中间件；完整all和docs入口保持原有覆盖范围。详见[CI.md](CI.md)。工具自身检查现为32项。

## 准备与运行

需要Python3.9+、JDK17、Maven及mysql/mysqld/mysqldump、redis-server、rabbitmq-server/rabbitmqctl。请先安装这些工具；入口不会静默安装软件，也不会修改系统服务。macOS会查找PATH和常见Homebrew安装位置。

IDEA的项目SDK与终端JAVA_HOME是两个配置。若检查提示JDK版本错误，在macOS终端选择已安装的17：

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

进入后端目录：

```sh
# 只检查环境和测试类，不启动服务
python3 -B scripts/test_backend.py --check

# 完整验收；首次可能需要Maven下载项目依赖
python3 -B scripts/test_backend.py

# 本机依赖已缓存时
python3 -B scripts/test_backend.py --offline

# 只验收第80课文档功能
python3 -B scripts/test_backend.py --offline --suite docs

# 验收测试工具自身，不启动数据库/MQ
python3 -B -m unittest discover -s scripts/tests -v
```

也可以从其他目录用脚本的绝对路径调用。路径含空格时记得用引号。

若工具没有放在PATH，Redis可通过REDIS_SERVER_BIN指定，RabbitMQ可通过RABBITMQ_SBIN指定安装目录。JAVA_HOME应指向JDK17。`--offline`仅控制Maven依赖解析；测试仍会使用本机临时网络端口。

## 一次完整验收发生什么

1. 预检依赖、JDK版本和源文件。all自动发现当前59个Java测试类；docs选择3个指定文档测试类。
2. 获取本项目测试锁；在私有临时目录中复制源码，排除data、target、IDE和Git目录。
3. 临时目录沿用campus-lesson24-前缀，以满足历史上传测试的清理身份断言，不放宽原断言。启动独立MySQL数据目录与随机回环端口，重放历史结构、建立各测试库，检查历史迁移没有丢失数据。
4. 启动两个临时Redis：商品缓存与noeviction登录保护分开。
5. 启动随机节点名、随机凭证和独立vhost的RabbitMQ；不连接桌面正在运行的MQ业务节点。
6. 构造测试专用环境，不继承真实数据库/MQ密码、Spring配置注入、API文档开关和JVM注入选项。
7. 在临时源码副本运行Maven clean test。原项目target/classes不会被删除或重编译。
8. 校验JUnit报告覆盖所有被选中的测试类，并要求失败、错误、跳过均0；退出码为0才算通过。
9. 关闭本次拥有的临时进程组，删除临时运行目录，保留私有验收结果。测试失败、超时或可捕获的SIGINT/SIGTERM中断也走清理流程；无法确认清理成功则报失败并保留目录供排查。

强制kill -9、断电等情况无法保证Python执行清理；此时应检查摘要中记录的临时目录和所属进程，不能将运行状态当成通过。

Erlang的本机epmd可能由其他RabbitMQ节点共用，本工具不会停止这个共享服务。

## 如何查看结果

```text
data/test-results/
  latest.json                  最近一次正式运行的摘要
  日期时间-随机标识/
    summary.json               此次摘要与测试数量
    maven.log                  此次Maven日志
    junit/TEST-*.xml            此次JUnit原始报告
```

目录仅当前用户可访问，摘要/报告/日志不提交Git。历史运行分别保留，入口不覆盖以前的结果。`--check`没有正式运行结果，不覆盖latest.json。

摘要示例（具体数字以当前运行结果为准）：

```json
{
  "suite": "all",
  "status": "passed",
  "expectedTestClasses": 59,
  "buildUsesTemporarySourceCopy": true,
  "junit": {
    "tests": 843,
    "failures": 0,
    "errors": 0,
    "skipped": 0,
    "suites": 59
  },
  "temporaryRuntimeRemoved": true
}
```

源码SHA-256来自编译前的临时副本，可以区分不同代码版本。它不是Git提交号，也不是制品签名。

## 为什么不能只看“构建成功”

Java集成测试使用EnabledIfEnvironmentVariable限制可写的测试数据库。直接运行Maven而没有测试环境时，部分测试可能被跳过。入口会核对报告中的测试类集合、用例数量和skipped：少报告、空报告、失败、错误或跳过都不能当成验收通过。

新测试类以Test.java或Tests.java结尾，并放在com.campus.market包下；自动发现后无需手改长名单。当前没有JUnit嵌套类报告；未来增加嵌套测试或特别的报告结构时，需同步调整覆盖校验，不能绕过它。

## 结构文件的范围

补回sql/lesson15-users.sql和sql/lesson19-login-sessions.sql，内容来自原有项目建表脚本，仅用于建立临时库；这是恢复测试输入，不是对当前业务库执行新迁移。其他历史迁移仍按原顺序使用。

## 文件职责

- scripts/test_backend.py：命令行、预检、运行锁、结果和退出码。
- scripts/backend_test_runtime.py：临时源码、数据库迁移与服务编排。
- scripts/backend_test_support.py：环境隔离、工具发现、报告覆盖、进程组清理。
- scripts/test_mq_runtime.py：独立测试MQ节点。
- scripts/tests/test_backend_tools.py：32项测试基础设施检查。

完整命令仍需已安装的本机中间件，不是Docker环境。第81课先完成可移植的验收入口；第82课已加入quick分层和GitHub Actions工作流模板，详见[CI.md](CI.md)。公开版本已包含仓库根目录工作流，云端运行结果以Actions页面为准，本机通过不代表远程CI已通过。
