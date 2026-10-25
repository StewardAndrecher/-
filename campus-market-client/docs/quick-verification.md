# 第83课：前端与桌面快速验收

## 范围与依赖

使用Python3.9+标准库和Node.js22.12+，无需npm install，无需启动后端、MySQL、Redis、MQ，也不依赖HBuilderX编译器或已安装的Electron。当前96项前端工具与状态测试、17项桌面规则测试是已有测试，第88课新增12项游标状态测试；这些测试不等于完整页面验收。

- client：构建清单/存储/网络/请求编号/商品游标状态。构建工具测试用临时模拟编译器验证失败、取消与产物一致性；不是用真实uni-app编译器执行三端编译。
- desktop：本机静态服务、路径/Host限制、资源清单与窗口规则。使用回环临时端口，不启动Electron界面，不等于桌面打包或原生启动验收。
- 完整前端47组行为与真实三端构建仍按原有入口单独执行。

## 命令

在campus-market-client目录运行：

```sh
python3 -B tools/test_quick.py --check
python3 -B tools/test_quick.py
python3 -B tools/test_quick.py --suite client
python3 -B tools/test_quick.py --suite desktop
python3 -B -m unittest discover -s tools/tests -v
```

通过NODE_BIN指定Node可执行文件；未设置时从PATH查找。默认all要求campus-market-desktop与campus-market-client并列，只有客户端项目时使用--suite client。入口不依赖终端当前目录。当前按POSIX进程组编写，实测Mac；Linux云端首跑待上传后验证，Windows原生不在本课范围。

## 一次验收

1. 校验Node版本，发现所选范围的全部*.test.mjs；没有文件就失败。
2. 获取独占验收锁，复制源码到私有临时目录，排除data、依赖、产物、.env、IDE、Git和Python字节码。
3. 只继承基础环境，过滤NODE_OPTIONS、NODE_PATH、真实业务凭证和UNI_COMPILER_HOME；所有临时夹具使用本次专属TMPDIR。
4. 每个测试文件单独执行Node原生JUnit报告器。普通断言失败后继续其他文件，完整保存各文件的退出码和报告。
5. 核对报告集合，空报告、缺报告、非零退出码、失败、错误、跳过均不能通过。Node可能将没有声明test的空文件记为一个通过的文件占位项；入口会明确拒绝这种占位项，避免空文件假通过。
6. 结束拥有的进程组，清理临时源码与夹具，保存权限受限的JSON/JUnit/日志。

运行中可捕获的SIGINT/SIGTERM会进入清理流程；无法确认进程停止时保留临时目录并失败。kill -9、断电或清理期间重复强制中断不能保证完成清理。

结果位于已被忽略的unpackage/quick-tests，含latest.json及各runId目录的summary.json、日志与junit/*.xml。不会覆盖Web/小程序/App构建产物，也不会修改桌面web资源。

## GitHub工作流

模板在[client-desktop-quick.yml](../.github/workflows/client-desktop-quick.yml)，需放到多项目仓库根.github/workflows。它要求根目录同时有campus-market-client与campus-market-desktop，不能仅放在客户端子目录下。课程另提供保留根目录结构的ZIP。

工作流使用Ubuntu24.04和Node22，先检查Python工具，再执行同一快速入口，报告保留7天。只读仓库权限、固定Action完整提交SHA，不执行npm install或下载Electron、不编译真实三端、不生成安装包、不部署。第82课后端工作流保持独立。

本课只完成本机验收和工作流静态检查，没有自动推送GitHub，也没有把本机通过描述为Linux云端成功。后续上传时，应同时更新源码和根目录工作流，再检查Actions与原始JUnit。

Node原生JUnit报告器：[官方文档](https://nodejs.org/download/release/latest-v22.x/docs/api/test.html#test-reporters)。工作流Node配置：[官方setup-node](https://github.com/actions/setup-node/releases/tag/v7.1.0)。

第88课连续浏览实现与协议见 [product-feed.md](product-feed.md)。

第89课新增12项管理员权限响应和请求竞争状态测试；与桌面共91项。业务实现见 [admin-access.md](admin-access.md)。

第90课新增22项审核响应、幂等重试和请求竞争测试；前端与桌面共113项。见 [product-moderation.md](product-moderation.md)。

第91课新增reports.test.mjs的20项工单协议、未知重试、账号切换和旧响应测试。

第92课新增notifications.test.mjs的20项协议、路由白名单、读取确认和账号竞争测试。

第95课新增18项通知批量已读前端测试，包含确认快照、取消、账号变化及未知结果保护；详见[notification-batch-read.md](notification-batch-read.md)。


第96课交易运营概览新增14项前端校验与生命周期测试；完整运行tools/test_quick.py，三端编译及Mac打包分别验收。


第97课新增19项管理员订单查询前端测试，覆盖条件回显、排序、编号、分页收缩和异步生命周期；新页面为第27个路由。


第98课新增21项管理员订单详情校验与异步生命周期测试；管理员详情为第28个路由，列表增加入口，手机验收与编译分别记录。


第99课加入登录会话管理页面、24项状态与协议检查。详见[session-management.md](session-management.md)，手机运行仍需真机单独验证。


第100课新增修改密码页及26项协议/异步状态测试，详见[password-change.md](password-change.md)。密码不进入持久存储，未知结果不自动重发。


第101课新增交易评价页和26项协议/异步状态测试，详见[order-reviews.md](order-reviews.md)。


## 第103课：评价清单

我的订单新增待评价与已评价清单，按当前角色筛选分页。是否已评价只看本人记录。完整说明见 order-review-tasks.md。


## 第104课：交易问题反馈

新增用户侧反馈提交及本人记录，尚未接管理员处理或退款。新增V104空表迁移，新库32份迁移；旧31份迁移不变。完整说明见 order-issues.md。


## 第105课：反馈处理与结果通知

管理员可逐条答复或不予支持；本人记录显示处理结果，可靠站内通知仅提醒该提交人。不改变交易或执行退款。新增V105空结果表，旧32份迁移不变，新库33份迁移。完整说明见 order-issue-resolutions.md。


第106课：tools/order-issue-list.test.mjs验证本人反馈清单最小协议、筛选计数、状态与时间、账号/令牌隔离、过期结果和安全导航。共用现有快速测试入口；新增pages/order/issues页面（当前共36页），无新依赖。


第107课：tools/issue-overview.test.mjs验证聚合分区、时长子集、微秒边界、排序和管理身份隔离；原工单测试适配新列表回显。新增pages/admin/issue-overview（当前37页），无新依赖。


第108课：tools/admin-issue-search.test.mjs覆盖精确编号、响应回显、返回白名单、实际页码与过期请求；原工单/概览测试适配新字段。无新依赖或页面，仍37页。


第109课：新增trade-tasks.test.mjs；第38页pages/order/tasks。检验买卖双角色、到期子集、白名单列表入口及旧响应隔离。


第110课：notification-targets.test.mjs验证五类固定目的地、类别绑定、回执target一致及迟到响应隔离；路由总数仍38。


第111课order-search.test.mjs验证精确编号、响应回显、白名单返回上下文和范围；页面总数仍38。


第112课：tools/order-export.test.mjs新增38项，包括CSV、下载资源清理和真实Vue脚本的迟到/身份隔离；网页下载，App/小程序复制，原生运行须另验收。


第113课tools/registration-retry.test.mjs新增24项：规范Retry-After、真实请求模块和注册页脚本，包含后台计时清理和无自动重试。
