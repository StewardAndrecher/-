# 第40课：校园集市桌面客户端第一版

本项目用Electron运行已有uni-app网页构建，复用Java后端及所有业务页面。本机端口5188提供打包网页，业务接口仍访问localhost:8080。固定origin和固定userData目录保留本机业务草稿、浏览历史；登录Token仍由uni-app会话模块保存在内存，重启需重新登录。

## 新增开发依赖

- electron 44.7.0
- @electron/packager 20.3.0

精确版本和传递依赖记录在package-lock.json。没有修改后端或前端业务页面。

## 本机开发运行

```sh
cd campus-market-desktop
npm ci
npm run prepare:runtime
npm run sync:web
npm start
```

第一次需先在前端项目执行：

```sh
cd campus-market-client
./tools/build.sh h5
```

默认前端项目为桌面项目的同级campus-market-client。其他位置可通过CAMPUS_CLIENT_DIR指定。sync:web核对第39课的哈希清单后复制网页，失败保留原产物；只会复制构建文件。

## 打包

```sh
cd campus-market-desktop
npm test
npm run package
```

Mac ARM64输出：dist/CampusMarket-darwin-arm64/CampusMarket.app。可以直接打开。打包用已有网页副本，先sync:web更新业务页面；npm run package拒绝覆盖已有同名输出，保留旧版本后再打包。

本课仅本机Mac ARM64验证，未做Windows/Linux打包验收，也未申请开发者签名、公证、自动更新或发布到应用商店。该客户端需要教学后端在本机8080运行，不包含Java/MySQL服务。

## 进程分工

主进程src/main.mjs负责窗口、应用生命周期、菜单、单实例和本地静态服务。渲染进程运行原uni-app网页；没有Node权限、没有preload/IPC桥接。contextIsolation、sandbox、webSecurity均启用。

src/local-server.mjs只绑定127.0.0.1:5188，GET/HEAD读取web目录，检查Host、路径和realpath，限制符号链接越界。CSP只允许应用本地资源和两个明确的本机8080后端origin；既有HTML内联脚本按内容哈希放行，不开启unsafe-eval。视图拒绝外部导航、新窗口、webview和额外网络目标。

持久化分区为persist:campus-market-desktop，用户数据目录为系统appData/campus-market-desktop。固定端口若被其他程序占用会启动失败，不会自动跳到随机端口或加载别的程序页面。摄像头、麦克风、定位、任意剪贴板读取权限没有启用。只允许本应用页面写入剪贴板和连接受CSP/请求过滤限制的本机后端。

Mac关闭窗口后应用可继续运行，菜单“退出校园集市”会关闭应用和本机静态服务。重新加载页面会丢失内存中的登录Token，需要重新登录；本机草稿与浏览历史保留。

## 验收

17项Node测试验证静态服务、GET/HEAD/MIME、方法/Host/路径/符号链接限制、端口冲突、CSP、窗口配置、导航/请求/权限规则以及产物同步校验。实际打包应用另外验收商品列表、详情、卖家主页、登录、收藏恢复及重启后的浏览记录。

## 官方资料

- https://www.electronjs.org/docs/latest/tutorial/security
- https://www.electronjs.org/docs/latest/api/app
- https://electron.github.io/packager/main/index.html
