# 第41课：手机App接入第一阶段

使用同一个uni-app项目，先打通App平台配置与资源编译。新增软件包：无。使用现有HBuilderX Vue3编译器；后端和数据库不改。

## 平台和地址

config/server.js使用条件编译：H5、MP-WEIXIN、APP-PLUS分别选择对应平台。App通过uni.getDeviceInfo().platform识别android/ios；开发模式选appDevelop，发行模式选appRelease。不能识别设备/模式或地址不合法时，在调用uni.request/uni.uploadFile之前报配置错误。

第41课检查时电脑en0地址是10.70.105.23。手机开发地址为http://10.70.105.23:8080；小程序真机开发地址同时更新。Wi-Fi改变后重新检查电脑IP并修改config/server.js，重新运行。网页仍用localhost:8080，微信电脑模拟器仍用127.0.0.1:8080。

手机上的localhost/127.0.0.1指向手机自身，不是开发电脑。App开发配置明确拒绝这些回环地址。手机和电脑需可相互访问（通常同一Wi-Fi）；本课在电脑上实际检查了LAN接口，但不因此宣称手机可以访问。

appRelease保持空值，因为还没有部署正式HTTPS后端。配置器要求发行地址为HTTPS域名，未配置则阻止请求，不能默默回退到本机测试服务器。正式后端部署后填写真实域名再发行。

## 两种编译用途

开发调试：HBuilderX打开桌面campus-market-client，选“运行→运行到手机或模拟器→App”，使用开发模式和appDevelop。Android需设备/模拟器及基座；iOS需相应设备或Xcode模拟器支持。具体基座、系统版本限制以官方文档为准。

编译检查：终端执行./tools/build.sh app（或all）。现有Vue3编译器使用build -p app，产生生产模式JS/CSS及manifest等资源，保存到unpackage/verified/app。appRelease未配置时这些生产资源中的业务请求会被阻止，这是预期行为。

这些资源不是APK/IPA，未签名，不能直接装手机。构建清单记录源码/产物SHA-256，校验11页路由与样式、核心运行资源、启动页及DCloud AppID。这个校验器适配当前编译器输出；编译器改变输出格式时会拒绝产物，需要核对格式后更新解析器。它只解析JSON，不执行生成的业务代码。

## AppID与网络限制

DCloud AppID为manifest.json顶层appid（__UNI__YOUR_APP_ID）；微信AppID为mp-weixin.appid（wxYOUR_MINIPROGRAM_APPID），两者各自使用，不能替换。

本课更新应用名称/描述，未修改原生权限、Android网络策略、iOS ATS或签名配置。原生系统/基座可能限制开发HTTP连接；未来手机验收如有错误，应根据真实日志检查，而不是先放开全部网络权限。

## 本课验收边界

这台电脑未发现可用Android adb/模拟器及完整Xcode iOS模拟器，因此本课完成平台逻辑检查、Web/微信/App三平台资源编译、真实LAN健康/商品接口，以及原Web/微信回归。Android/iOS画面、相机/相册选择、系统网络策略、返回键/安全区域、APK/IPA安装均待真实设备阶段验证，不能写成已通过。

## 官方参考

- https://uniapp.dcloud.net.cn/api/system/getDeviceInfo.html
- https://uniapp.dcloud.net.cn/worktile/CLI.html （Vue3 App使用app参数，CLI资源与APK/IPA区别）
- https://uniapp.dcloud.io/tutorial/run/run-app.html （App真机/模拟器运行）

## 第42课：网络状态与应用前后台

新增utils/network-monitor.mjs（纯状态控制）、utils/network-status.js（Vue只读状态）和NetworkStatusBanner组件；首页和开发诊断页复用组件。App.vue在应用回前台onShow中重新查询设备网络。第42课电脑当前LAN为192.168.1.100，配置已更新；上文第41课地址是历史记录，使用时以config/server.js当前配置和电脑IP为准。

组件挂载取得监听，最后一个使用者卸载时用原回调解除监听；不调用无参数off接口清除其他模块监听。旧查询不能覆盖更新的网络事件，卸载/重新挂载后旧回调也不生效。API缺失或异常只显示未知，业务请求仍正常尝试。

断网时显示提示，恢复后只提示用户手动刷新商品。不自动重发发布、留言、上传或收藏；不会清空草稿、登录态或本地缓存。已有首页onShow会按原逻辑重新读取商品，这个行为与网络事件监听分开。设备online仅说明网络连接，不能证明后端、数据库或互联网可用。未知网络状态不等于断网，不拦截请求。

测试命令：使用HBuilderX的Node执行--test tools/network-monitor.test.mjs。测试模拟设备API，网页/微信实际界面单独验证；按用户安排跳过手机实机验收，不将其记为通过。
