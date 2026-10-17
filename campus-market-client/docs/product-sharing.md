# 第34课：商品分享

网页详情页使用uni.setClipboardData复制当前站点的详情地址；仅包含商品ID，保留当前静态网页部署路径，不复制浏览器旧查询参数或登录信息。复制失败提供可选中的链接，供手动复制。localhost开发链接仅在同一电脑上有效；面向其他设备需要它们可访问的网站地址。

微信详情页使用button open-type=share和页面级onShareAppMessage，路径为/pages/product/detail?id=编号。只为已加载的在售商品显示入口；加载中、错误、下架和留言提交期间隐藏分享菜单，回调返回首页通用内容。分享标题只取公开商品标题，图片采用代码包/static/logo.png，避免使用其他设备无法访问的本机图片URL。

分享是用户主动点击的动作。分享回调生成卡片不表示好友已收到；开发者工具的分享面板可检查内容，正式接收与打开需要后续真机验收及可访问的后端。App和桌面应用的分享入口另行安排，本课不增加其SDK配置。

链接中的商品ID必须是规范正整数，禁止0、负数、小数、科学计数法、前导零及超过JavaScript安全整数范围的值。无效链接不发商品请求；合法编号但不存在时仍由后端返回404。分享链接不是授权凭据，接收者仍受原有后端商品状态与权限检查约束。

参考：[uni-app分享](https://uniapp.dcloud.io/api/plugins/share?id=onshareappmessage)、[剪贴板](https://uniapp.dcloud.io/api/system/clipboard.html)。

H5额外监听hashchange：同一详情页仅改变编号时更新或清空商品，不复用旧内容；重复id参数也拒绝。页面隐藏时停止自动读取，卸载后移除监听。
