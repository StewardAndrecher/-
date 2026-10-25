# 公开发布与微信支付接入进度

目标：面向公众运营，先微信支付，再支付宝。当前尚无支付商户号；不是已经具备真实收款能力的版本。

## 本轮已落实

- 网页在公网HTTPS域名下使用同域 /api，不再连接访客自己的localhost；本机预览保持原接口。
- 显式 production 配置：Java回环监听，Nginx终止HTTPS，代理覆盖客户端转发头。
- production 启动校验在业务Bean、数据库迁移之前执行；禁止模拟付款、公开开发文档、空必需项、root业务数据库账号、错误代理信任范围。
- GET /api/trade-capabilities 返回 SIMULATED 或 UNAVAILABLE，无凭据、no-store。
- 未接通支付时，前端隐藏模拟付款/新下单按钮；后端拒绝新订单，不占用商品。已有幂等订单仍可恢复，已有订单仍可查询和取消。商品的可用性与支付能力分开判断，不把支付未开通显示成商品已被预订。
- 发布包附带部署配置生成器与只读运行预检，均不自动安装、开端口、上传或执行真实支付。

## Linux部署文件

先取得自己的域名与有效HTTPS证书，准备专用Linux账号、MySQL、独立登录Redis、上传与日志目录。下面只生成文件，不执行部署；域名示例需替换成自己的真实域名。

    python3 -B deployment/render_deployment.py --origin https://你的实际域名 --output /新建的审阅目录

生成 nginx.site.conf、campus-market.service、runtime.env.example。输出目录必须不存在，避免覆盖已有配置。证书路径采用 /etc/letsencrypt/live/<域名>/，如证书由其他方式托管需自行调整模板后通过 nginx -t。

目录约定：

- /srv/campus-market/current/server/app.jar：当前后端发布包中的JAR。
- /srv/campus-market/current/web：前端已验证的H5构建。
- /srv/campus-market/uploads：独立且持久的上传目录。
- /var/log/campus-market：日志目录。
- /etc/campus-market/runtime.env：只在服务器填写的真实运行变量，限制读取权限，不提交仓库。

服务使用 production profile。必须提供 PUBLIC_ORIGIN、DB_URL、非root的DB_USERNAME、DB_PASSWORD、UPLOAD_DIR、APP_LOG_FILE。先按迁移文档维护数据库，默认不会自动接管旧库。MQ是否开启由既有独立环境变量控制，本模板不修改RabbitMQ权限。

Nginx模板假定直接接收公网连接，与Java在同一台主机，没有额外CDN/LB；覆盖X-Forwarded-For为TCP来源地址，不信任访客提交的转发链。如果将来加入CDN/LB，必须按真实拓扑另做可信代理配置和限流测试，不能直接放宽到所有代理。

Linux与nginx的实际安装、证书获取、nginx -t、systemd启动和公网演练均需在目标服务器上完成；生成文件和单元测试不等于部署验收。

## 只读预检

    python3 -B deployment/check_readiness.py --base-url https://你的实际域名

只发匿名GET读取存活、就绪与交易能力；不带账号、不注册、不下单、不付款，不跟随重定向。非HTTPS只允许显式指定的回环本机预览。模拟付款或支付未开通返回失败。此检查只证明所列运行检查，不代替资金结算、退款、对账、权限、隐私、备份恢复或正式发布批准。

## 真实支付仍待完成

1. 确认运营主体、支付产品与卖家结算模式。平台多卖家交易需要核实平台产品及卖家入驻条件；不能把普通商户收款代码直接当作多卖家结算方案。
2. 开通匹配场景的微信支付产品，完成小程序认证、AppID与商户绑定及卖家相关入驻；这些申请由你在官方平台操作，不把私钥或API密钥发到聊天。
3. 接入服务端金额快照、微信订单、调起支付参数、验签和通知解密；小程序支付需要与该AppID对应的用户身份，现有账号密码登录不能直接代替微信OpenID。
4. 处理通知重放、金额/币种/商户/订单匹配、支付与超时关闭竞态、主动查询补偿。前端支付成功回调不能直接作为数据库已付款的依据。
5. 退款、卖家结算、资金流水、微信账单对账、差错处置与运营审计。
6. 提供真实域名、服务器及小程序正式合法域名；完成目标机部署、备份恢复演练、适当规模负载测试和受控真实支付/退款验收。
7. 完成适用于实际业务的用户协议、隐私说明、客服、举报处理、卖家规则和各端发布审核。

这些未完成项没有被计为通过，当前不应开放真实在线交易。

## 官方依据（2026-10-24核对）

- [微信小程序支付接入准备](https://pay.wechatpay.cn/doc/v3/merchant/4015459512)：认证、可申请主体、权限与AppID绑定。
- [平台收付通产品介绍](https://pay.wechatpay.cn/doc/v3/partner/4012086891)：平台与二级商户、交易资金和结算机制。
- [平台收付通开发准备](https://pay.wechatpay.cn/doc/v3/partner/4012086921)：接入与进件条件；具体适配需以申请审核结果为准。
- [Spring Boot代理配置](https://docs.spring.io/spring-boot/how-to/webserver.html)：native转发头与Tomcat可信代理。
- [Nginx代理指令](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)：请求转发和头覆盖。

这是项目实施与验收记录；没有申请商户、接受协议、部署公网或发生扣款。
