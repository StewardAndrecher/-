# 账号安全、内容治理、图片与发版质量

新增依赖：spring-boot-starter-mail。邮件发送遵循 Spring 官方 JavaMailSender 接口与有限超时设置：https://docs.spring.io/spring-boot/reference/io/email.html 。

## 邮箱
默认 EMAIL_ENABLED=false，界面准确显示不可用。通过环境配置 EMAIL_SMTP_HOST、EMAIL_SMTP_PORT、EMAIL_SMTP_USERNAME、EMAIL_SMTP_PASSWORD、EMAIL_FROM、EMAIL_OTP_KEY（至少32字节随机值），然后设置 EMAIL_ENABLED=true。配置不要提交GitHub。默认587端口要求STARTTLS；465设置EMAIL_SMTP_SSL=true。验证服务端证书身份，连接/读/写超时3秒；服务端不会在响应或日志里返回验证码。先在自己的测试邮箱验收收信，再正式启用。

绑定先验证当前密码，再验证新邮箱。换绑必须先通过原邮箱验证码解除绑定。验证码10分钟、5次尝试、单次使用、60秒发送间隔、每天20次；数据库仅保存HMAC摘要。未知账号和不匹配邮箱的找回请求均返回相同受理格式，不发邮件。找回成功撤销所有旧会话、旧恢复码和旧邮件挑战。短信和微信账号关联尚未接入。

## 治理
风险文本规则配置 CONTENT_BLOCKED_TERMS（分号分隔）。这是有限的文本规则，不是AI审核，也不覆盖图片语义、全部违禁品或新型欺诈。规则在商品创建/编辑、留言、昵称与简介写入服务端强制执行。高频公开写入由数据库10分钟窗口保护，不依赖浏览器。管理员按用户名定位，携带版本、时长和原因限制发布；用户可查看原因并对每次处理申请一次复核。管理员账号不能通过该页面受限。限制不改变密码、权限、原订单或售后；商品下架继续使用原审核流程。

## 图片
ImageObjectStore 是存储接口；当前实现 LOCAL，不冒充已接入OSS/S3。上传重编码PNG并记录真实字节与SHA256；默认每用户500MB、200张，回收站仍计费。旧图片未核验前按10MB保守计费。图片库分页12张。删除前保护商品封面、多图、头像、发布回执与云端草稿引用。客户端未同步草稿无法被服务端识别，应先同步或自行保管。

回收站可恢复，超过30天且无引用才进入管理员清理预览（每批最多50张）。清理要求候选当前版本；先提交墓碑，再删物理对象，失败仍计费并可重试，禁止扫描删除未知文件。永久清理必须由管理员在页面明确确认，不默认定时删除。生产还需独立存储卷、备份恢复演练、对象存储接入与容量告警。

## 两种质量检查
商品表单自动显示基础资料建议，服务端 quality-check 核验格式、图片归属与风险文本；实际发布也强制复查，不能绕过接口校验。建议项不作为平台担保。

仓库根目录应放置 .github/workflows/release-quality.yml；本项目保留一份模板。每次push/PR进行完整后端隔离回归、前端/桌面逻辑回归和JAR打包，上传质量证据，权限仅contents:read。多项目仓库需将模板复制到仓库根目录才能被GitHub识别。本次没有上传或触发GitHub远端运行。

本机使用已有命令先运行 scripts/test_backend.py、客户端 tools/test_quick.py、客户端 tools/build.sh all、scripts/build_release.py，然后执行：

```
python3 -B scripts/release_quality.py --client ../campus-market-client --desktop ../campus-market-desktop
```

门槛会拒绝缺失报告、失败/跳过用例、未确认测试清理、源码改动、产物篡改或缺失三端构建。只检查当前源码对应的证据，不执行上传/部署；GitHub全量回归不替代HBuilderX构建、真机、生产部署与真实支付退款验收。GitHub环境审批配置参考：https://docs.github.com/en/actions/concepts/workflows-and-actions/deployment-environments 。
