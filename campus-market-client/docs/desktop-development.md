# 第40课：复用uni-app网页生成桌面客户端

新增同级项目：/path/to/campus-market-desktop。

前端业务仍写在当前uni-app项目；用./tools/build.sh h5生成验收网页后，在桌面项目执行npm run sync:web。Electron使用这份独立产物，不加载开发热更新服务，不改动微信产物目录。

Electron 44.7.0和@electron/packager 20.3.0是桌面项目的开发依赖，前端和Java后端不新增依赖。

启动：
```sh
cd /path/to/campus-market-desktop
npm start
```

打包：npm run package。当前Mac ARM64应用为dist/CampusMarket-darwin-arm64/CampusMarket.app，打开前需要启动IDEA后端。它是本机教学版本，连接8080上的现有服务；没有部署公网、签名公证或打包手机App。

桌面端仍走H5条件编译，使用config/server.js的web后端地址。桌面页面origin固定http://127.0.0.1:5188，后端已有本机开发CORS覆盖此origin；不要关闭webSecurity绕过CORS。桌面网页和普通浏览器、微信各自有独立本机存储。

Token继续只在内存，重启桌面应用后需重新登录。本机待确认发布/留言记录、最近浏览等可以在原账号登录后恢复。主进程负责系统窗口与静态资源，渲染进程负责uni-app页面和接口请求。本课没有开放Node或IPC给业务页面。

完整源码及运行说明见桌面项目README.md。本课只验证本机Mac ARM64，其他桌面系统、安装包签名、手机端与部署在后续课程分别处理。
