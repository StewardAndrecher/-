# 第78课：HTTP请求编号与日志定位

第77课完成告警通知；本课解决收到“HTTP500”后，如何定位具体后端请求的问题。

## 链路与边界

每个同步HTTP请求由后端生成独立32位小写十六进制编号，通过`X-Request-Id`响应头返回。过滤器把编号放入SLF4J MDC，Controller、Service以及原有GlobalExceptionHandler在同一请求线程中的日志自动带上该编号。结束时恢复原上下文，避免线程复用串号。

uni-app公共请求封装读取响应头，把合法编号放入`ApiError.requestId`。5xx错误的message附加`（请求编号：...）`，现有页面即可显示；4xx保持简短提示，仍可在错误对象中读取编号。网络超时未收到响应时没有编号，不在前端伪造。

这不是跨服务分布式追踪：当前只覆盖同步Servlet请求，不自动传播到线程池、定时任务或MQ。请求编号不能替代登录令牌、订单ID或幂等键，也不作为Prometheus标签。

## 新增与修改

无新增软件、Maven/npm/pip包和业务表；使用现有Spring Web、SLF4J/Logback及uni.request/uni.uploadFile。

- 新增`config/RequestCorrelationFilter.java`：生成响应头、MDC作用域、完成摘要、CORS暴露头合并。
- 新增`RequestCorrelationFilterTest.java`：11项检查，覆盖真实异常处理器、线程隔离、CORS和摘要字段边界。
- 修改`application.properties`：MDC日志格式、文件输出与轮转。
- 修改`.gitignore`：忽略`data/logs/`。
- 前端新增`utils/request-reference.mjs`与`tools/request-reference.test.mjs`；修改`api/request.js`和`api/image.js`。
- 本说明与前端`docs/request-logging.md`介绍定位方法。

## 用法

在后端项目目录发出只读请求：

```sh
curl --noproxy '*' -i http://localhost:8080/api/products
```

响应头示意（实际每次请求不同）：

```text
X-Request-Id: 0123456789abcdef0123456789abcdef
```

随后搜索该编号：

```sh
rg -F '0123456789abcdef0123456789abcdef' data/logs/campus-market.log
```

IDEA Run窗口也可以搜索编号。若请求返回500，应能在同一编号下找到GlobalExceptionHandler的异常首行与RequestCorrelationFilter的完成摘要，例如：

```text
ERROR [requestId:...] ... GlobalExceptionHandler : 处理请求失败
WARN  [requestId:...] ... RequestCorrelationFilter : http_request method=GET route=/api/products status=500 durationMs=...
```

异常堆栈后续行通常不重复编号，阅读时从对应异常首行往下查看。不存在编号或编号属于另一进程/旧日志时，先核对浏览器使用的后端地址和进程。

## 为什么这样实现

1. 忽略来访`X-Request-Id`，统一由服务端生成，避免调用者伪造编号干扰定位；不增加客户端请求头和额外跨域预检。
2. `MDC.put`只设置当前线程；`finally`恢复先前值。16个并行请求测试验证编号各自独立。
3. 访问摘要记录方法、路由模板、HTTP状态、毫秒耗时。比如`/api/products/{id}`，不记录真实商品ID、原始URL、查询串、请求体、IP或Authorization。
4. 路由和方法采用有限字符与白名单，不允许换行进入摘要。未匹配路由记为`unmatched`。
5. 指标采集与健康探针仍有响应编号，但不每5秒写完成摘要，减少噪声。
6. 响应包装器把`X-Request-Id`合并到`Access-Control-Expose-Headers`，保留登录限流的`Retry-After`；不更改允许来源、方法或登录权限。陌生站点预检仍403。
7. 前端按大小写不敏感读取响应头，仅接受单一32位小写十六进制值；重复、数组、空白和非法值均丢弃。不从错误正文猜测编号。
8. 不改变现有JSON响应形状；`ApiErrorResponse`仍为code与message两字段。只有前端5xx展示文本增加编号。

## 日志文件与轮转

```properties
logging.pattern.level=%5p [requestId:%X{requestId:-none}]
logging.file.name=${APP_LOG_FILE:./data/logs/campus-market.log}
logging.logback.rollingpolicy.max-file-size=10MB
logging.logback.rollingpolicy.max-history=7
logging.logback.rollingpolicy.total-size-cap=100MB
```

默认相对路径以启动时工作目录为准。本机IDEA工作目录应为后端项目。`APP_LOG_FILE`可将隔离测试日志放入临时目录。

Logback按默认时间/大小策略轮转：单文件10MB，保留7个历史时间周期（默认按天），归档总量上限100MB；活动日志另计。没有HTTP上下文的启动、定时任务和MQ日志显示requestId为none。

新增的请求摘要不记录敏感请求内容；本课没有重写所有历史异常或第三方库日志。因此本机日志仍属于私有运行文件，不提交Git、不开放网页下载。

## 验收

- 后端新增11项检查，连同原792项回归测试完整运行，目标803项。
- 前端新增13项检查，连同原37项Node测试共50项。
- 编译Web、微信小程序和App资源；App资源不是可安装APK/IPA。
- 独立后端连接未运行的隔离数据库端口，真实GET商品接口返回500。核对响应编号、异常日志和请求摘要相同；用独立Web页面确认实际显示的编号也能找到这两类日志。
- 原有数据库不停机、不故意触发实际服务告警。十三张业务表、图片和其他源码另行核对。

官方资料：[Spring过滤器](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html)、[Spring Boot日志](https://docs.spring.io/spring-boot/reference/features/logging.html)、[uni.request响应](https://uniapp.dcloud.net.cn/api/request/request.html)。

第79课继续使用已有事件编号关联异步阶段，见[MQ关联日志](MQ_LOGGING.md)。
