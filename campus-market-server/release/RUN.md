# Campus Market 后端发布包

发布包包含JDK17可运行JAR、33份结构SQL合成的空库初始化脚本、外置配置示例和deployment部署工具。实际测试数量以本包manifest.json为准，quick不能代替完整集成回归。

先校验SHA256SUMS，再部署自己的环境。SHA-256用于完整性核对，不是来源签名。本包没有真实数据库内容、密码、证书私钥或上传图片。

## 本机启动

准备已有结构的MySQL和独立登录保护Redis，复制config/application.properties.example到发布包以外的runtime/application.properties，提供DB_URL、DB_USERNAME、DB_PASSWORD、UPLOAD_DIR和APP_LOG_FILE。不要把密码放进Java命令行或提交仓库。

    java -jar /绝对路径/app.jar --spring.config.additional-location=file:/绝对路径/runtime/application.properties

额外配置文件为必需文件，路径错误应启动失败。本机外置配置默认关闭模拟支付，因此不接受新订单；已有订单可查询、取消，商品可浏览。没有接通真实支付，不能声称可在线收款。

## 数据库

新空库可选择Flyway显式迁移，或一次性执行database/init.sql，两种方式不要混用。已有库必须按迁移历史升级，禁止重新初始化。默认不自动baseline、repair、clean，也不接管未知非空库。更详细的维护边界见源码MIGRATIONS.md和LEGACY-BASELINE.md。

## 公网部署

阅读[公开发布说明](deployment/PUBLIC-LAUNCH.md)。production profile会在业务Bean与数据库迁移前校验正式配置。deployment/render_deployment.py只生成Nginx和systemd审阅文件；不会自行安装、启动或暴露网络端口。

模板面向同机Nginx与回环Java后端，需专用账号、真实HTTPS证书、目标目录和受控环境变量。应用上传数据必须与版本目录分开保存和备份。

在目标Linux服务器执行nginx -t，并完成服务启动、HTTPS、健康、数据库、登录限流、回调、备份恢复与支付/退款/对账验收后才能准备公开交易；配置生成不等于上线。

本包不包含真实支付商户、域名、服务器、资金结算或手机安装包，也没有执行任何公开部署。

数据库环境账号、独立迁移、备份恢复和清理说明见 database/DATABASE.md。运行脚本位于scripts/。新生产环境使用独立campus_production库和campus_production_runtime账号，不使用应用运行账号执行迁移。
