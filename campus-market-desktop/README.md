# Campus Market 桌面客户端

基于Electron44.7.0，复用同仓库uni-app的H5构建与Java后端。当前本机已完成Mac ARM64打包及启动验收。

完整环境与运行步骤见[根目录启动指南](../docs/GETTING_STARTED.md#6-mac桌面客户端)。先构建前端H5，安装本目录依赖后执行npm run prepare:runtime、npm run sync:web和npm start。打包执行npm run package，默认保留已有输出而不覆盖。

本机静态服务固定127.0.0.1:5188，后端单独运行于8080。本应用不打包Java/MySQL。渲染进程无Node权限，启用contextIsolation、sandbox和webSecurity；检查静态路径、Host、CSP、导航和请求目标。

npm test运行17项Node测试。源码入口为src/main.mjs、local-server.mjs、window-policy.mjs和web-artifacts.mjs。

尚未完成Windows/Linux安装包、签名、公证、自动更新或应用商店发布。重开页面后需重新登录；本机草稿与浏览记录按账号保留。
