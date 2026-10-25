# 第39课：多端构建隔离与样式修复

## 这次发现的问题

ProductCard的开发产物WXML含data-v-1abb8588，WXSS却含data-v-fe52aa40，导致scoped选择器不能匹配，标题、描述、价格在微信模拟器挤在一行。之前把独立工作目录生成的产物复制进HBuilderX维护的unpackage/dist/dev/mp-weixin，与开发编译结果混用。

scoped样式应保留。修复构建流程后，完整的WXML、WXSS、JS来自同一次构建即可匹配；无需删掉scoped或重写原商品卡片样式。

## 新增的软件包与文件

新增软件包：无。使用HBuilderX已经安装的Node、Vue3 uni-app编译器和Node内置fs/path/crypto/child_process/test。

新增tools/build.sh、tools/build.mjs、tools/build-files.mjs、tools/build-check.mjs、tools/build-check.test.mjs、本说明，修改.gitignore补充node_modules忽略规则。后端、数据库、业务页面和组件代码均不改。

## 使用方法

在终端执行：

```sh
cd /path/to/campus-market-client
./tools/build.sh all
```

只编译指定平台：

```sh
./tools/build.sh h5
./tools/build.sh mp-weixin
./tools/build.sh app
```

微信开发者工具导入目录为：

`/path/to/campus-market-client/unpackage/verified/mp-weixin`

网页静态构建目录为：

`/path/to/campus-market-client/unpackage/verified/h5`

第41课增加 App 资源目录 `unpackage/verified/app`。`all` 现在顺序编译网页、微信、App三个平台；App资源不是APK/IPA，也不能当网页打开。

它们是本项目脚本的验收目录，HBuilderX自身的开发/发行目录仍按官方方式使用。不要把独立构建的文件复制进dist/dev。

使用当前已安装编译器重新构建，没有安装、更新依赖。换电脑可用UNI_COMPILER_HOME指定uniapp-cli-vite目录，NODE_BIN指定Node可执行文件。本课验收环境为macOS和当前HBuilderX；不声称测试了Windows/Linux。

检查产物：

```sh
/Applications/HBuilderX.app/Contents/HBuilderX/plugins/node/node tools/build-check.mjs unpackage/verified/mp-weixin
/Applications/HBuilderX.app/Contents/HBuilderX/plugins/node/node tools/build-check.mjs unpackage/verified/h5
/Applications/HBuilderX.app/Contents/HBuilderX/plugins/node/node tools/build-check.mjs unpackage/verified/app
```

运行构建工具测试：

```sh
/Applications/HBuilderX.app/Contents/HBuilderX/plugins/node/node --test tools/build-check.test.mjs
```

## 一次构建如何完成

1. 创建构建锁，防止两个本项目脚本同时写验收目录。
2. 对源码做SHA-256清单，再复制到临时目录。unpackage、node_modules、.git、.idea和.hbuilderx不进快照。
3. 临时快照链接已安装编译器的依赖，生成适配HBuilderX项目的package.json与vite.config.js，原项目不生成这两个文件。
4. 从同一份快照顺序编译所选平台，输出到临时目录。
5. 验证11个小程序页面的文件完整性，检查WXML/WXSS/JS的scoped标识；网页检查入口和引用的本地资源。第41课新增App的DCloud AppID、Android/iOS平台、启动资源、路由和页面样式检查。
6. 写build-meta.json，记录编译器版本、Node版本、源码和产物哈希。
7. 再核对源码没有在编译期间变化。所有所选平台的编译与检查都通过后，逐个平台替换验收目录。
8. 上一份产物移动到unpackage/verified/history，编译/校验失败不会替换当前验收目录。单个平台替换失败会尝试恢复上一份；跨平台目录替换不是文件系统整体事务。
9. 清理临时快照和锁。Ctrl+C会取消编译并清理。强制kill/系统断电可能留下锁；先核对owner.json里的PID确已退出，再移走残留锁，不能在另一个构建仍运行时删锁。

project.private.config.json属于微信工具本机设置，.DS_Store属于系统目录信息，二者不计入编译产物哈希。工具修改实际JS/WXML/WXSS或公开项目配置时会被校验发现。哈希清单用于检测错配或误覆盖，不是数字签名或供应链认证。

## 本课验收

12项Node测试包括样式标识错配、文件缺失/改写/多出、网页资源缺失、生成快照与清单、旧产物留存、第二平台失败、构建期间源码变化、并发锁和Ctrl+C清理。真实旧ProductCard错配也被校验器拒绝。

实际网页与微信编译、两端数据读取和微信样式恢复另外验收。后端代码不变，177项后端测试为第38课的已通过基线，本课不重复宣称重新执行。

## 官方参考

- [uni-app命令行工具说明](https://zh.uniapp.dcloud.io/worktile/CLI.html)
- [uni-app快速上手：运行与发行目录](https://uniapp.dcloud.net.cn/quickstart)
- [Vue作用域CSS说明](https://vuejs.org/api/sfc-css-features.html#scoped-css)

本课不生成APK/IPA或桌面应用安装包。多端可靠构建是后续App与桌面端交付的基础。

第41课将构建工具测试扩展为16项，包括App资源完整性、路由解析不执行代码、错误DCloud AppID以及第三个平台失败时保留全部旧产物。手机接入步骤见[App开发说明](app-development.md)。
