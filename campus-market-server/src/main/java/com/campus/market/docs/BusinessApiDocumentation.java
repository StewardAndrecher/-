package com.campus.market.docs;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.*;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.security.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@Configuration
@ConditionalOnProperty(name="springdoc.api-docs.enabled",havingValue="true")
public class BusinessApiDocumentation {
    private static final String BEARER="bearerAuth";
    private static final Set<String> PUBLIC=Set.of("GET /api/users/email/capabilities","POST /api/users/recover-by-email","POST /api/users/recover","GET /api/trade-capabilities","POST /api/users/register","POST /api/users/login",
            "POST /api/products/lookup","GET /api/products","GET /api/products/feed","GET /api/product-sorts","GET /api/categories","GET /api/images/{id}","GET /api/sellers/{id}/products");
    private static final Set<String> OPTIONAL=Set.of("POST /api/users/email/challenges","GET /api/products/{id}",
            "GET /api/products/{productId}/comments","GET /api/products/{id}/order-state");
    private static final Set<String> CREATED=Set.of("POST /api/users/register","POST /api/products","POST /api/images");
    private static final Set<String> REPLAY=Set.of("POST /api/orders/{id}/refunds","POST /api/products/{id}/reports","POST /api/products/{id}/orders","POST /api/product-publications",
            "POST /api/products/{productId}/purchase-intent","POST /api/products/{productId}/comments",
            "POST /api/purchase-intents/{intentId}/messages","PUT /api/product-draft-backups/{id}");
    private static final Set<String> EMPTY=Set.of("PUT /api/users/me/images/{id}/trash","PUT /api/users/me/images/{id}/restore","POST /api/users/logout","DELETE /api/product-draft-backups/{id}",
            "DELETE /api/products/{productId}/comments/{commentId}");
    private static final Map<String,String> TAGS=Map.ofEntries(Map.entry("EmailBindingController","邮箱验证与找回"),Map.entry("GovernanceController","内容与账号治理"),Map.entry("ImageLibraryController","图片空间管理"),Map.entry("ProductQualityController","商品发布检查"),
            Map.entry("AccountProfileController","公开个人资料"),Map.entry("AccountRecoveryController","账号恢复"),Map.entry("OrderRefundController","退款与售后申请"),Map.entry("TradeCapabilitiesController","交易能力"),Map.entry("IssueOverviewController","反馈处理概览"),Map.entry("AdminOrderIssueController","交易问题工单"),Map.entry("AdminOrderController","交易运营"),Map.entry("AdminNotificationController","通知投递管理"),Map.entry("NotificationController","站内通知"),Map.entry("ReportController","商品举报"),Map.entry("AdminProductModerationController","商品审核"),Map.entry("ProductModerationController","商品复核"),Map.entry("AdminController","平台管理"),Map.entry("PermissionController","账号权限"),Map.entry("UserController","账号"),Map.entry("LoginController","账号"),Map.entry("PasswordChangeController","账号密码"),Map.entry("SessionListController","有效登录明细"),Map.entry("SessionManagementController","登录会话管理"),Map.entry("SessionController","会话"),
            Map.entry("UserProfileController","个人资料"),Map.entry("ProductLookupController","公开商品批量读取"),Map.entry("ProductController","商品"),Map.entry("CategoryController","分类"),Map.entry("ProductSortController","商品排序"),
            Map.entry("ImageController","图片"),Map.entry("FavoriteBatchController","收藏批量管理"),Map.entry("FavoriteController","收藏"),Map.entry("CommentController","商品留言"),
            Map.entry("PublicationController","发布幂等"),Map.entry("DraftBackupController","草稿备份"),
            Map.entry("PurchaseIntentController","购买意向"),Map.entry("PurchaseIntentMessageController","意向沟通"),
            Map.entry("PurchaseIntentUnreadController","未读概览"),Map.entry("SellerStoreController","卖家主页"),Map.entry("OrderTaskController","我的交易待办"),Map.entry("OrderIssueListController","本人反馈清单"),Map.entry("ReceivedReviewController","收到的交易评价"),Map.entry("OrderReviewTaskController","评价清单"),Map.entry("OrderIssueController","交易问题反馈"),Map.entry("OrderReviewController","交易评价"),Map.entry("OrderExportController","个人订单导出"),Map.entry("OrderController","订单"));
    private static final Map<String,String> VERBS=Map.ofEntries(
            Map.entry("issueOverview","反馈处理时效采样"),Map.entry("adminOrderIssues","反馈工单列表"),Map.entry("adminOrderIssue","反馈工单详情"),Map.entry("resolveOrderIssue","处理交易反馈"),Map.entry("adminOrderDetail","管理员订单详情与进度"),Map.entry("adminOrders","管理员订单检索"),Map.entry("orderOverview","交易运营概览"),Map.entry("notificationOverview","投递任务概览"),Map.entry("readNotificationBatch","本页通知批量已读"),Map.entry("notifications","我的通知"),Map.entry("unreadNotifications","通知未读数"),Map.entry("readNotification","标记通知已读"),Map.entry("createReport","提交商品举报"),Map.entry("ownReports","我的举报"),Map.entry("ownReportDetail","我的举报详情"),Map.entry("adminReports","举报工单"),Map.entry("adminReportDetail","举报证据与当前商品"),Map.entry("resolveReport","处理举报"),Map.entry("moderationProducts","巡查商品"),Map.entry("moderationDetail","审核记录"),Map.entry("moderateProduct","下架或解除限制"),Map.entry("sellerModeration","我的商品处理记录"),Map.entry("appealModeration","申请商品复核"),Map.entry("adminOverview","管理概览"),Map.entry("adminMe","管理员身份"),Map.entry("myPermissions","当前账号权限"),Map.entry("ownOrderExport","生成个人订单导出预览"),Map.entry("ownTradeTasks","本人交易待办采样"),Map.entry("ownOrderIssues","我的交易反馈清单"),Map.entry("removeFavoriteBatch","取消所选收藏"),Map.entry("receivedReviews","我收到的交易评价"),Map.entry("reviewTasks","我的待评价与已评价订单"),Map.entry("orderIssue","本人交易问题记录"),Map.entry("createOrderIssue","提交交易问题反馈"),Map.entry("orderReviews","订单双方评价"),Map.entry("createOrderReview","提交订单评价"),Map.entry("changeMyPassword","修改当前账号密码"),Map.entry("mySessionSummary","我的登录状态"),Map.entry("revokeOtherSessions","退出其他登录"),Map.entry("register","注册"),Map.entry("login","登录"),Map.entry("logout","退出登录"),Map.entry("me","当前用户"),
            Map.entry("lookupPublicProducts","按编号批量读取"),Map.entry("listProductFeed","连续浏览商品"),Map.entry("listProducts","公开商品列表"),Map.entry("getProduct","商品详情"),Map.entry("listMyProducts","我发布的商品"),
            Map.entry("mySummary","我的发布概览"),Map.entry("createProduct","发布商品"),Map.entry("updateProduct","编辑商品"),
            Map.entry("updateProductStatus","修改商品状态"),Map.entry("create","创建"),Map.entry("page","分页查询"),Map.entry("list","列表"),
            Map.entry("detail","详情"),Map.entry("read","读取"),Map.entry("save","保存"),Map.entry("delete","删除"),
            Map.entry("add","添加"),Map.entry("remove","移除"),Map.entry("selectedIds","查询指定商品的收藏状态"),
            Map.entry("upload","上传"),Map.entry("image","读取图片"),Map.entry("cancel","取消"),Map.entry("accept","接受"),
            Map.entry("send","发送"),Map.entry("withdraw","撤回"),Map.entry("readState","读取已读位置"),Map.entry("markRead","标记已读"),
            Map.entry("simulatePayment","教学模拟付款"),Map.entry("deliver","卖家确认交接"),Map.entry("complete","买家确认收货"),
            Map.entry("batch","批量查询未读"),Map.entry("summary","汇总"),Map.entry("getStore","卖家公开主页"),Map.entry("updateNickname","修改昵称"),Map.entry("timeline","状态时间线"),Map.entry("state","商品下单状态"));

    @Bean public OpenAPI campusOpenApi() {
        return new OpenAPI().info(new Info().title("校园集市业务 API").version("1.0.0")
                .description("uni-app Web、小程序、App与Electron共用的业务接口。金额单位为分；登录采用数据库会话Bearer令牌。模拟付款仅用于教学。"))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes(BEARER,new SecurityScheme().type(SecurityScheme.Type.HTTP)
                        .scheme("bearer").bearerFormat("opaque session token").description("填写登录返回的accessToken；不是JWT，不需要手写Bearer前缀。")));
    }
    @Bean public OperationCustomizer operationNames() {
        return (operation,handler)->{
            String type=handler.getBeanType().getSimpleName(),method=handler.getMethod().getName();
            if(!TAGS.containsKey(type))return operation;
            String tag=TAGS.get(type);operation.setTags(List.of(tag));operation.setOperationId(type+"_"+method);
            operation.setSummary(tag+"："+VERBS.getOrDefault(method,method));return operation;
        };
    }
    private static Header requestId() {return new Header().description("本次HTTP请求编号，可在后端日志中查询").schema(new StringSchema().pattern("^[0-9a-f]{32}$"));}
    private static ApiResponse response(String description,Content content) {
        return new ApiResponse().description(description).content(content).addHeaderObject("X-Request-Id",requestId());
    }
    private static ApiResponse error(String description) {
        return response(description,new Content().addMediaType("application/json",new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError"))));
    }
    @Bean public OpenApiCustomizer businessContract() {
        return api->{
            // springdoc先裁剪未引用模型，再执行此定制器；错误引用与定义必须在同一阶段加入。
            api.getComponents().addSchemas("ApiError",new ObjectSchema().addProperty("code",new StringSchema()).addProperty("message",new StringSchema()).required(List.of("code","message")));
            api.getPaths().forEach((path,item)->item.readOperationsMap().forEach((method,operation)->{
                String key=method.name()+" "+path;boolean open=PUBLIC.contains(key),optional=OPTIONAL.contains(key);
                operation.setSecurity(open?List.of():optional?List.of(new SecurityRequirement(),new SecurityRequirement().addList(BEARER)):List.of(new SecurityRequirement().addList(BEARER)));
                if(operation.getParameters()!=null)operation.getParameters().removeIf(p->"header".equals(p.getIn())&&"Authorization".equalsIgnoreCase(p.getName()));
                Content success=operation.getResponses().entrySet().stream().filter(e->e.getKey().startsWith("2")).map(e->e.getValue().getContent()).filter(Objects::nonNull).findFirst().orElse(null);
                if(success!=null && success.containsKey("*/*"))success=new Content().addMediaType("application/json",success.get("*/*"));
                ApiResponses responses=new ApiResponses();
                if(EMPTY.contains(key))responses.addApiResponse("204",response("完成，无响应体",null));
                else if(key.equals("POST /api/users/email/challenges"))responses.addApiResponse("202",response("请求已受理；找回用途不暴露账号或邮箱是否存在",success));
                else if(REPLAY.contains(key))responses.addApiResponse("200",response("幂等重放或更新成功",success)).addApiResponse("201",response("首次创建",success));
                else responses.addApiResponse(CREATED.contains(key)?"201":"200",response(CREATED.contains(key)?"创建成功":"成功",success));
                responses.addApiResponse("400",error("请求参数或JSON格式无效")).addApiResponse("500",error("服务器错误；使用响应X-Request-Id定位日志"));
                if(!open)responses.addApiResponse("401",error("未登录或会话无效"));
                if(!open && !optional)responses.addApiResponse("403",error("无权执行该操作"));
                if(path.contains("{"))responses.addApiResponse("404",error("资源不存在或当前用户不可见"));
                if(method!=PathItem.HttpMethod.GET && !EMPTY.contains(key) && !key.equals("POST /api/users/login"))responses.addApiResponse("409",error("状态、版本或幂等请求发生冲突"));
                if(path.contains("/email") || key.equals("POST /api/users/recover-by-email")){
                    responses.addApiResponse("403",error("验证失败、验证码无效、已使用或已过期"));
                    responses.addApiResponse("429",error("验证或发送频率受限"));responses.addApiResponse("503",error("邮件服务或登录保护不可用"));
                    operation.setDescription("邮件服务默认关闭，配置SMTP后才可用。8位验证码10分钟内有效，最多5次尝试，只能使用一次；发送间隔60秒，每个账号每天最多20封。BIND/UNBIND需要当前会话和密码校验；RECOVER用途匿名受理，未知账号和不匹配邮箱返回相同格式。更换邮箱先验证旧邮箱解除绑定；找回成功撤销所有旧会话、恢复码及邮件挑战。验证码不进入API响应或日志，网络异常不自动重发。");
                }
                if(key.equals("POST /api/products/quality-check")||key.equals("POST /api/products")||key.equals("PUT /api/products/{id}")||key.equals("POST /api/products/{productId}/comments")||key.equals("PUT /api/users/me/profile")||key.equals("POST /api/users/register"))responses.addApiResponse("422",error("内容命中平台风险文本规则，需要修改或复核"));
                if(path.startsWith("/api/users/me/images")||path.equals("/api/admin/media/cleanup"))operation.setDescription("图片库只向本人提供清单，使用中图片禁止回收。回收站可恢复且仍计费，超过30天、无引用且候选版本一致后管理员才能永久清理；先提交墓碑，再删除物理对象，失败仍计费并可重试。每批最多50张，默认每人500MB和200张，历史未知大小按10MB计费。");
                if(path.contains("/governance/")||path.contains("/content-status"))operation.setDescription("公开内容发布限制与处理记录：管理员必须携带当前版本、处理原因及1～720小时时长；管理员账号受保护。本人可查看原因并对每个处理版本申请一次复核。不会改变密码、角色、交易结果或售后权限。");
                if(key.equals("POST /api/users/login")) {
                    responses.addApiResponse("401",error("用户名或密码错误"));
                    for(String status:List.of("429","503"))responses.addApiResponse(status,error(status.equals("429")?"登录请求受限":"登录保护暂时不可用")
                            .addHeaderObject("Retry-After",new Header().description("建议等待的秒数").schema(new IntegerSchema().minimum(BigDecimal.ONE))));
                }
                if(key.equals("POST /api/users/register")){
                    for(String status:List.of("429","503"))responses.addApiResponse(status,error(status.equals("429")?"REGISTRATION_RATE_LIMITED：来源注册频率受限":"REGISTRATION_PROTECTION_UNAVAILABLE：注册保护暂不可用，本次未创建账号")
                        .addHeaderObject("Retry-After",new Header().description("等待的整数秒数，前端不自动重试").schema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(86400)))));
                    operation.setDescription("公开注册，默认每个连接来源60秒最多60次，首次放行开始固定窗口。JSON成功绑定后，在用户名检查、数据库和密码哈希前使用现有独立登录Redis上的专用注册键及Lua原子计数；成功、重复账号或校验失败均消费已放行配额，不回退；被拒请求不延长TTL。只使用servlet连接地址，忽略Forwarded/X-Forwarded-For，反向代理须另外评估来源策略及校园NAT阈值。429/503无账号创建；Redis超时、错误类型、无TTL或计数异常均503，不绕过保护。响应no-store，跨域暴露Retry-After。限流不是验证码、实名验证或完整反滥用方案。");
                }
                operation.setResponses(responses);
                if(REPLAY.contains(key))operation.setDescription("首次创建返回201，重放或更新返回200。请求编号与业务幂等键用途不同；相同操作重试应按该接口约定复用幂等键或版本。");
                if(key.equals("GET /api/products/feed"))operation.setDescription("仅支持NEWEST。用上一页nextCursor继续，无COUNT和OFFSET；更换关键词/分类须清空cursor。nextCursor为空表示结束；游标不是鉴权令牌，也不提供跨请求快照。原页码接口保持兼容。");
                if(path.equals("/api/admin/me"))operation.setDescription("服务端对/api/admin/**统一验证当前会话和实时管理员授权；普通账号403，未登录401。没有公开提权接口，权限撤销后下一次请求生效。");
                if(path.contains("/notifications"))operation.setDescription("仅当前登录用户可查询或标记自己的通知，不能由客户端指定收件人。ALL/UNREAD筛选，最多20件；单条已读幂等保留首次时间。他人通知404。通知由未来业务事件经事务Outbox和MQ异步投递，不回填历史事件。");
                if(path.equals("/api/notifications")||path.equals("/api/notifications/{id}/read")||path.equals("/api/notifications/read-batch")) {
                    operation.setDescription((operation.getDescription()==null?"":operation.getDescription())+" 每条通知新增target白名单：ORDER_DETAIL、ORDER_REVIEWS、OWN_ORDER_ISSUE、REPORT_DETAIL、PRODUCT_MODERATION。由服务器持久来源推导，不返回sourceKey或任意URL，也不根据标题正文判断。评价直达交易评价，反馈结果仅本人页面；读取目标仍重新鉴权。未知或旧ORDER来源保持订单详情。单条与批量已读回执保留target；客户端须比对与原通知一致后才跳转。");
                }
                if(path.equals("/api/notifications")) {
                    operation.setDescription(operation.getDescription()+" 第119课：category=ALL/ORDER/REPORT/MODERATION，status=ALL/UNREAD/READ，默认ALL/ALL。身份与类型、已读状态取交集，先SQL筛选再COUNT/LIMIT，统计和本页共享只读REPEATABLE_READ快照，按通知编号倒序；未知类型或状态400，匿名/失效401。列表筛选不标记已读，不创建通知或MQ任务，仍只返回本人通知。首页与页首未读数仍为全类型的独立查询，不能当作当前分类数量或跨接口原子快照。批量已读仅当前页明确未读编号，不扩大到整个分类；详情目标保持原白名单并重新鉴权。未增加索引或宣称任意分类在生产规模下均为索引精确读取。");
                    if(operation.getParameters()!=null)operation.getParameters().forEach(parameter->{
                        if(parameter.getName().equals("category"))parameter.setSchema(new StringSchema()._enum(List.of("ALL","ORDER","REPORT","MODERATION"))._default("ALL"));
                        if(parameter.getName().equals("status"))parameter.setSchema(new StringSchema()._enum(List.of("ALL","UNREAD","READ"))._default("ALL"));
                    });
                }
                if(path.equals("/api/notifications/read-batch")){
                    operation.setDescription("仅当前用户，提交明确的1至20个不重复JSON整数ids。所有通知必须存在且属于自己，否则整批404且不修改。批量事务幂等保留首次已读时间，不按当前时间或最大ID扩大范围；响应records为这批通知的权威已读状态。");
                    responses.addApiResponse("404",error("部分通知不存在或当前用户不可见，整批未执行"));
                }
                if(path.equals("/api/notifications/read-batch"))operation.setDescription(operation.getDescription()+" records保留每条通知的target白名单，不返回sourceKey或外部URL。");
                if(path.equals("/api/admin/orders"))operation.setDescription("仅实时有效管理员可检索平台订单快照。status为ALL、六状态或OVERDUE_PENDING；orderId为空或1至9007199254740991的规范正整数文本，精确匹配并与状态取交集。每页1至20条，页码超出回到最后页。COUNT和分页在同一REPEATABLE_READ快照，复用固定数据库基准时间判断到期；只读，不关闭订单。响应不包含参与者、令牌或请求标识。非管理员403，未登录401。");
                if(path.equals("/api/admin/orders") && operation.getParameters()!=null)operation.getParameters().forEach(parameter->{
                    switch(parameter.getName()) {
                        case "status" -> parameter.setSchema(new StringSchema()._enum(List.of("ALL","PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT","OVERDUE_PENDING")));
                        case "orderId" -> parameter.setSchema(new StringSchema().pattern("^$|^[1-9][0-9]{0,15}$").maxLength(16).description("空文本为不限；非空值最多9007199254740991"));
                        case "page" -> parameter.setSchema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(new BigDecimal("2147483647")));
                        case "size" -> parameter.setSchema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(new BigDecimal("20")));
                    }
                });
                if(path.equals("/api/admin/orders/{id}")) {
                    operation.setDescription("仅实时有效管理员读取订单快照和进度时间线，同一只读REPEATABLE_READ事务。不返回参与者身份、操作人编号或昵称、令牌及请求标识；只展示BUYER/SELLER/SYSTEM角色。HISTORY_SNAPSHOT仅表示迁移观察到的状态，不补造此前历史。记录缺失或不一致返回503，不伪造事件，也不推进或关闭订单。规范正整数id，未知订单404，非管理员403，未登录401。");
                    responses.addApiResponse("503",error("订单进度缺失或不一致，需维护人员核查"));
                    if(operation.getParameters()!=null)operation.getParameters().forEach(parameter->{if(parameter.getName().equals("id"))parameter.setSchema(new StringSchema().pattern("^[1-9][0-9]{0,15}$").maxLength(16).description("规范正整数，最多9007199254740991"));});
                }
                if(path.equals("/api/admin/orders/overview"))operation.setDescription("仅管理员可读取平台全部订单的聚合采样，不包含订单编号、商品正文或参与者信息。单条SQL按数据库时间统计六种状态及已完成模拟交易金额；已到期待付款是待付款子集，查询不关闭订单。金额是整数分的订单快照之和，不是真实收款、余额或收入。非管理员403，未登录401。超出安全整数范围返回错误而非截断。");
                if(path.equals("/api/admin/notifications/overview"))operation.setDescription("仅管理员可读取通知任务聚合采样，不包含通知正文或收件人。单条SQL按数据库时间统计；投递开关不代表Broker健康。重试中、领取中是待投递的可重叠子集；列表无清理或重放操作。非管理员403，未登录401。");
                if(path.contains("/reports"))operation.setDescription("提交举报保存商品快照，举报人只能读取自己的工单；管理员实时授权，处理须工单版本和requestId，BLOCK额外需要商品版本且与商品下架同事务。重复原请求不重复创建或处理；同一用户对同一商品仅一条待处理举报。DISMISS不改商品，终态结果不可覆盖。");
                if(path.contains("/moderation"))operation.setDescription("发布后巡查。BLOCK下架并限制卖家重新上架；APPEAL申请复核；RELEASE解除限制但保持下架。写入须提供version、规范UUID requestId及2～200字符reason；相同请求幂等返回200，变更载荷或旧版本409。有效订单占用与已售出商品拒绝处理。记录只对管理员或商品卖家可见，最近20条；处理证据保存商品快照。");
                if(path.equals("/api/orders/export")) {
                    operation.setDescription("仅当前登录会话本人，BUYER/SELLER、订单状态和完整orderId取交集。复用列表编号规范，上限9007199254740991。按created_at及id倒序，单条只读SQL最多读取201条；超过200笔返回413 ORDER_EXPORT_LIMIT，不返回部分成功。成功返回全部匹配的最多200条最小字段快照及筛选回显；不含买卖双方编号/昵称、商品描述、评价、反馈、请求键或令牌。无分页，不接受客户端指定userId或limit。未知或他人订单返回200空记录。无业务写入、Redis共享缓存或MQ消息，no-store。此预览反映生成时状态，刷新重新鉴权查询；CSV由客户端生成，表格公式字符进行转义。模拟交易不代表真实账单或收支凭证。");
                    responses.addApiResponse("413",error("ORDER_EXPORT_LIMIT：超过200笔，请缩小筛选范围，不截断导出"));
                    if(operation.getParameters()!=null)operation.getParameters().forEach(parameter->{if(parameter.getName().equals("orderId"))parameter.setSchema(new StringSchema().pattern("^$|^[1-9][0-9]{0,15}$").maxLength(16));});
                }
                if(path.equals("/api/orders")) {
                    operation.setDescription("仅当前会话本人，role=BUYER/SELLER与status筛选。orderId缺省或空文本不限，非空为1至9007199254740991规范正整数文本；精确匹配，SQL绑定且与本人角色/状态取交集。未知或他人订单均200空列表，不接受userId切换身份。响应orderId数字或null；total/items为筛选结果，summary始终为本人当前角色全部订单统计，不随编号或状态过滤。过滤后分页，越界收缩，统计/计数/列表共享只读REPEATABLE_READ快照；查询不写订单、事件或通知。no-store。详情返回只携带白名单条件和实际页码，返回重新查询，不是跨请求冻结快照。");
                    if(operation.getParameters()!=null)operation.getParameters().forEach(parameter->{if(parameter.getName().equals("orderId"))parameter.setSchema(new StringSchema().pattern("^$|^[1-9][0-9]{0,15}$").maxLength(16).description("空文本不限，非空最多9007199254740991"));});
                }
                if(path.equals("/api/orders/tasks/overview")) {
                    operation.setDescription("仅当前会话本人，userId由服务端确定，客户端userId/role/时间参数不能切换统计对象。单条只读SQL同时统计买入和卖出待办，使用数据库sampledAt。pendingPayment=payable+overduePendingPayment（payable表示尚未到期）；到期边界含等号，读取不关闭订单。待确认仅买入DELIVERED，待交付仅卖出PAID，待评价仅COMPLETED且本人没有评价，对方评价不影响。仅返回本人编号、采样时间与计数，不含订单编号、标题、金额、对方身份、评价或反馈正文；匿名/失效会话401，成功no-store。跨页面重新查询，不承诺冻结快照；全部为模拟交易。");
                    responses.addApiResponse("503",error("待办计数异常，未伪造零结果"));
                }
                if(path.equals("/api/orders/issues")) {
                    operation.setDescription("仅当前会话本人已提交的交易反馈，身份BUYER/SELLER，筛选ALL/OPEN/RESOLVED；不接受客户端指定userId/applicantId。处理状态仅看本人结果，不受另一方反馈或处理影响。数据库筛选与分页，最多20条，越界收缩到末页；汇总与列表共享只读REPEATABLE_READ快照，按提交时间和订单ID倒序。只返回订单标题快照、问题类型、提交时间、处理决定与时间；不返回反馈正文、处理说明、管理员或对方身份及请求键。查询不写订单/反馈/处理/通知，响应no-store。跨请求不是冻结快照，翻页或处理后请刷新。");
                    responses.addApiResponse("503",error("反馈清单数据异常，未伪造空列表"));
                }
                if(key.equals("POST /api/products/lookup")) {
                    operation.setDescription("公开只读批量查询，不需要登录，忽略Bearer且不读取账号私有视图。请求仅productIds，1至20个不重复JSON正整数，最大9007199254740991；字符串、小数、重复键、未知字段拒绝400。单条商品SQL只读取ON_SALE当前公开投影，按请求编号顺序返回records，每项为productId/product；不存在、下架、售出均product:null，不透露原因或隐藏详情，包含历史未登记卖家的在售商品。数据库失败整批500，不伪造不可用。只发送当前页商品编号即可，不接收浏览时间/账号/全量历史，不创建云端历史、Cookie、收藏、Redis缓存或MQ消息。no-store；POST用于有界查询体而非创建，不返回201。请求数量减少不等于数据库性能保证，未新增索引。");
                    operation.getRequestBody().setRequired(true);
                    operation.getRequestBody().setContent(new Content().addMediaType("application/json",new MediaType().schema(new ObjectSchema().addProperty("productIds",new ArraySchema().items(new IntegerSchema().format("int64").minimum(BigDecimal.ONE).maximum(new BigDecimal("9007199254740991"))).minItems(1).maxItems(20).uniqueItems(true)).required(List.of("productIds")).additionalProperties(false))));
                }
                if(key.equals("GET /api/sellers/{id}/products")) {
                    operation.setDescription("公开卖家主页，无需登录，不根据Bearer切换身份。category缺省或空文本表示全部分类；非空只接受现有六种分类代码（含UNCLASSIFIED）。路径卖家、ON_SALE和category始终取交集，过滤后COUNT/LIMIT；卖家概要、计数与列表共享只读REPEATABLE_READ快照，排序复用现有四种白名单。只公开卖家id/nickname及当前在售商品；下架、售出、其他卖家和未登记卖家商品均排除。既有卖家无匹配200空列表，不存在404，非法参数400，数据库失败500。越界保留请求页码和筛选total并返回空records。no-store，无业务写入，不新增索引，不承诺生产规模性能；跨HTTP请求不是冻结快照。");
                    if(operation.getParameters()!=null)operation.getParameters().forEach(parameter->{
                        if(parameter.getName().equals("category"))parameter.setSchema(new StringSchema()._enum(List.of("","UNCLASSIFIED","BOOK","DIGITAL","DAILY","SPORT","OTHER"))._default(""));
                    });
                }
                if(key.equals("GET /api/favorites")) {
                    operation.setDescription("仅当前会话本人收藏；availability=ALL/AVAILABLE/UNAVAILABLE，默认ALL。AVAILABLE表示原收藏列表可返回当前在售商品，UNAVAILABLE表示原左连接不可见（下架、售出等），只返回productId和product:null，不泄露标题、卖家或其他详情。keyword缺省或去除首尾空白后为空表示不限；非空最多50个UTF-16代码单元，拒绝控制字符，按当前可查看标题做包含搜索，百分号、下划线、感叹号均为普通文本。已失效商品不参与标题搜索，即使其隐藏标题匹配也不影响total；UNAVAILABLE与非空keyword组合恒为空。所有条件取交集，先在数据库过滤再COUNT/LIMIT，计数与列表共享只读REPEATABLE_READ快照，按收藏时间和商品编号倒序。页码大于0，每页1至50；越界返回空records并保留原total/page，不写收藏或商品。附加userId/sellerId不改变会话身份，未知可用性400；成功绑定后先鉴权，匿名/失效401。no-store；不新增索引，不承诺生产规模性能。批量管理仍是独立的全部收藏列表，取消仅处理明确选择的编号。");
                    if(operation.getParameters()!=null)operation.getParameters().forEach(parameter->{
                        if(parameter.getName().equals("keyword"))parameter.setSchema(new StringSchema().maxLength(50)._default(""));
                        if(parameter.getName().equals("availability"))parameter.setSchema(new StringSchema()._enum(List.of("ALL","AVAILABLE","UNAVAILABLE"))._default("ALL"));
                    });
                }
                if(path.equals("/api/favorites/remove-batch")) {
                    operation.setDescription("仅当前账号，POST productIds为明确选择的1至20个不重复JSON正整数，最大9007199254740991；未知字段和重复productIds键拒绝，不接受字符串、小数或隐式转换。成功绑定后先鉴权；畸形JSON可先400。仅一条带user_id与明确IN编号的DELETE，READ_COMMITTED事务，全批参数通过后才写入。响应userId/productIds/removedCount，保留请求编号顺序；removedCount仅本次实际删除数，不存在、他人独有或已取消的关系为0，重放不重复删除。下架或售出商品仍可取消，不读取其私密详情。不扩展为全部收藏，不修改商品、通知或MQ；no-store。并发新收藏在本次删除后仍可能重新出现，成功后刷新核对。网络结果未知先刷新，禁止自动重发；未提供一键恢复或全库清理。");
                    responses.remove("409");
                    operation.getRequestBody().setRequired(true);
                    operation.getRequestBody().setContent(new Content().addMediaType("application/json",new MediaType().schema(new ObjectSchema().addProperty("productIds",new ArraySchema().items(new IntegerSchema().format("int64").minimum(BigDecimal.ONE).maximum(new BigDecimal("9007199254740991"))).minItems(1).maxItems(20).uniqueItems(true)).required(List.of("productIds")).additionalProperties(false))));
                }
                if(path.equals("/api/orders/received-reviews")) {
                    operation.setDescription("仅当前会话本人收到的评价，role=BUYER/SELLER；只关联已完成订单中对方提交的记录，本人给出的评价不算收到。rating=ALL/GOOD(4—5)/NEUTRAL(3)/LOW(1—2)。只接受role/rating/page/size单值参数，未知或重复400，先鉴权；userId不能由客户端指定。summary为本人当前角色全部收到评价的评分分布，不受rating筛选影响；筛选后分页，最多20条，越界收缩。汇总和本页共享只读REPEATABLE_READ快照；按评价时间和订单编号倒序。条目只含订单编号、标题快照、评分、文字和时间，不含对方身份、订单请求键或令牌，不公开到卖家主页。跨页重新采样，不是冻结快照；不创建评价或通知，no-store。");
                    responses.addApiResponse("503",error("RECEIVED_REVIEW_UNAVAILABLE：评价记录异常，不伪造空结果"));
                    operation.setParameters(new java.util.ArrayList<>());
                    operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name("role").in("query").schema(new StringSchema()._enum(List.of("BUYER","SELLER"))._default("BUYER")));
                    operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name("rating").in("query").schema(new StringSchema()._enum(List.of("ALL","GOOD","NEUTRAL","LOW"))._default("ALL")));
                    operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name("page").in("query").schema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(new BigDecimal("1000000"))._default(1)));
                    operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name("size").in("query").schema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(new BigDecimal("20"))._default(10)));
                }
                if(path.equals("/api/orders/review-tasks")) {
                    operation.setDescription("仅当前会话身份：BUYER/SELLER，PENDING/REVIEWED；仅统计已完成订单，是否已评价只看本人记录。数据库SQL筛选和分页，最多20条，越界收缩到最后页；汇总与列表共享只读REPEATABLE_READ快照。按完成时间和订单ID倒序，不返回对方身份、评分或评价文字，查询不写评价/订单/通知，不接受客户端userId。响应no-store。");
                    responses.addApiResponse("503",error("评价清单数据异常，未伪造为空列表"));
                }
                if(path.startsWith("/api/admin/order-issues")) {
                    operation.setDescription("仅实时有效管理员，普通用户403，匿名401。OPEN/RESOLVED/ALL，最多20条，SQL筛选与分页使用只读REPEATABLE_READ；详情仅单条反馈及订单快照，不合并另一方说明。处理需要ANSWERED/DISMISSED、10至1000字符explanation及规范UUID requestId；同管理员/同键/同内容重放200，不同或已被他人处理409。锁反馈后重新鉴权，禁止处理本人参与的订单；原反馈不覆盖，处理记录与通知Outbox同一事务，仅提醒提交人，通知无反馈或处理正文。不改变订单/商品/评价，不执行退款，响应no-store。");
                    responses.addApiResponse("503",error("反馈或处理记录异常，请重新加载"));
                }
                if(path.equals("/api/admin/order-issues")) {
                    operation.setDescription(operation.getDescription()+" age=ALL/OVER_24H/OVER_72H，仅OPEN可指定等待时长；满24/72小时含边界，按数据库sampledAt计算。默认ALL按最新提交倒序，时长筛选按最早提交及复合编号正序；COUNT与列表使用同一只读快照和固定采样时间。age与sampledAt回显，跨请求不是冻结快照。");
                    operation.setDescription(operation.getDescription()+" orderId可空或1至9007199254740991的规范正整数文本，精确匹配并与状态/时长取交集。查询参数绑定，不是身份或权限凭证；未找到或无反馈返回空列表。响应orderId为整数或null，同一订单最多双方两条反馈。返回列表保留条件和页码后重新查询，处理后允许收缩页码。");
                    if(operation.getParameters()!=null)operation.getParameters().forEach(parameter->{if(parameter.getName().equals("orderId"))parameter.setSchema(new StringSchema().pattern("^$|^[1-9][0-9]{0,15}$").maxLength(16).description("空文本为不限，非空最多9007199254740991"));});
                }
                if(path.equals("/api/admin/order-issues/overview")) {
                    operation.setDescription("仅实时有效管理员：匿名401，普通账号403。单条SQL用数据库sampledAt统计反馈总数、待处理/已处理、ANSWERED/DISMISSED、待处理满24/72小时及最早待处理时间。72小时是24小时子集；双方各自反馈分别计数，处理与原反馈按复合键关联，不含正文、订单/提交人/管理员编号或请求键。满24/72小时含边界，不是自动关闭或处理期限；只读、不执行退款、不写订单/反馈/处理/通知。no-store，跨页面重新采样，不保证两次请求数量相同；异常时间或记录503。");
                }
                if(path.equals("/api/orders/{id}/issue")) {
                    operation.setDescription("仅当前订单参与者的本人反馈，对方说明和身份不返回；匿名401，非参与者与未知订单统一404。PAID/DELIVERED/COMPLETED可提交，每方最多一次；类型限定ITEM_CONDITION/HANDOVER/OTHER，说明规范化后10至1000个Unicode字符。同内容重放200，不同内容409，首次201。写入锁订单并再次校验会话；读取REPEATABLE_READ快照、响应no-store。不改订单/商品/评价/通知，不触发退款；本人记录可查看处理结果，管理员身份和处理幂等键不返回。");
                    responses.addApiResponse("503",error("反馈数据异常，未伪造空记录"));
                    if(method==PathItem.HttpMethod.POST)responses.addApiResponse("201",response("首次提交",success));
                }
                if(path.equals("/api/orders/{id}/reviews")) {
                    if(method==PathItem.HttpMethod.POST)responses.addApiResponse("201",response("首次提交评价",success));
                    responses.addApiResponse("503",error("评价记录异常，拒绝返回不一致数据"));
                    operation.setDescription("仅订单双方可见，未登录401，非参与者或未知订单统一404。只有COMPLETED订单可评价，各方最多一次；评分必须为1至5的JSON整数，文字可空、最多500个Unicode字符，统一换行并裁剪首尾空白。身份由当前会话确定，不接受客户端指定评价人。首次201；相同规范化载荷重放200且不改首次时间，不同内容409；暂不修改或删除评价。首次评价与对方通知Outbox同一事务，重放不重复提醒，不回填历史评价；经既有MQ链路异步送达。通知仅通用提醒，不含评分/文字/对方身份，仍使用ORDER分类并进入订单详情。响应仅含BUYER/SELLER角色、评分、文字、时间及mine，不返回对方编号、昵称、令牌或公开卖家评分。GET共享只读事务快照；POST订单行锁与唯一键保证并发安全，no-store。网络结果未知时先重新加载，不自动提交。");
                }
                if(path.equals("/api/users/me/password")) {
                    responses.remove("409");
                    responses.addApiResponse("403",error("原密码不正确，CURRENT_PASSWORD_MISMATCH；当前登录仍保留"));
                    for(String status:List.of("429","503"))responses.addApiResponse(status,error(status.equals("429")?"密码验证请求受限":"密码验证保护暂时不可用")
                            .addHeaderObject("Retry-After",new Header().schema(new IntegerSchema().minimum(BigDecimal.ONE))));
                    operation.setDescription("仅当前账号，验证currentPassword并设置newPassword。新密码8至64字符、UTF-8最多72字节，不能全空白或与原密码相同；不裁剪首尾空格。成功200后包括当前在内的所有有效会话失效，必须重新登录。与登录校验/签发共享用户行锁，修改与会话撤销同一事务。使用现有Redis密码验证保护，429/503不修改；无自动重试。超时结果未知，应清理本机登录后使用新密码重新登录确认。不返回密码/哈希/令牌，no-store。401表示当前会话无效，403仅表示原密码错误。");
                }
                if(path.startsWith("/api/users/me/sessions"))operation.setDescription("仅当前登录账号，无客户端userId。GET返回当前会话创建/到期时间及有效会话数量，不返回令牌、令牌哈希或其他账号信息。POST退出服务器处理时该账号的其他有效会话，保留当前会话；处理完成后的新登录不受影响。账号锁与登录签发串行，拿锁后重新验证当前会话。重复请求可返回0；无自动重试，网络错误先刷新核对。未登录或当前会话失效401，响应no-store。");
                if(path.equals("/api/users/me/sessions/active")) {
                    operation.setDescription("只读分页查看当前账号的有效登录会话。只接受page（默认1，上限1000000）和size（默认10，上限20），每项只允许一个规范正整数；未知或重复参数400。身份先于参数校验，匿名/失效会话401。单次REPEATABLE_READ事务和同一数据库sampledAt保证鉴权、数量和本页一致；按创建时间倒序，内部哈希仅用于同秒稳定排序，不向客户端返回。越界页收缩到末页。条目仅current、createdAt、expiresAt，无令牌、哈希、会话编号、IP或设备信息。跨页重新采样，新增/退出/到期可能导致偏移分页重复或漏项；不是冻结快照。成功no-store，不创建、撤销或删除会话。");
                    operation.setParameters(new java.util.ArrayList<>());
                    operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name("page").in("query").description("单个规范正整数，越界收缩到末页").schema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(new BigDecimal("1000000"))._default(1)));
                    operation.addParametersItem(new io.swagger.v3.oas.models.parameters.Parameter().name("size").in("query").description("每页条数，单个规范正整数").schema(new IntegerSchema().minimum(BigDecimal.ONE).maximum(new BigDecimal("20"))._default(10)));
                }

                if(path.endsWith("/simulate-payment"))operation.setDescription("教学模拟付款，不连接真实支付渠道；过期订单可能先被关闭并返回409。");
            }));
            api.getComponents().getSchemas().forEach((name,schema)->{
                if(name.endsWith("NotificationModels.ReadBatch")) {
                    schema.setRequired(List.of("ids"));
                    schema.addProperty("ids",new ArraySchema().items(new IntegerSchema().format("int64").minimum(BigDecimal.ONE).maximum(new BigDecimal("9007199254740991"))).minItems(1).maxItems(20).uniqueItems(true));
                }
                if(name.endsWith("LoginUserRequest")){schema.setRequired(List.of("username","password"));password(schema);}
                if(name.endsWith("RegisterUserRequest")){schema.setRequired(List.of("username","password","nickname"));password(schema);}
                if(name.endsWith("CreateProductRequest"))schema.setRequired(List.of("title","priceInCents"));
                if(name.endsWith("OrderModels.Create")) {
                    schema.setRequired(List.of("productVersion","requestId"));
                    schema.addProperty("productVersion",new IntegerSchema().format("int64").minimum(BigDecimal.ZERO).maximum(new BigDecimal("9007199254740991")));
                    schema.addProperty("requestId",new StringSchema().format("uuid").description("客户端生成的下单幂等键；同一操作重试复用此值"));
                }
                if(name.endsWith("ProductModerationModels.Action") || name.endsWith("ProductModerationModels.Appeal")) {
                    schema.setRequired(name.endsWith(".Action")?List.of("action","reason","version","requestId"):List.of("reason","version","requestId"));
                    schema.addProperty("reason",new StringSchema().minLength(2).maxLength(200));
                    schema.addProperty("version",new IntegerSchema().format("int64").minimum(BigDecimal.ZERO).maximum(new BigDecimal("9007199254740991")));
                    schema.addProperty("requestId",new StringSchema().format("uuid").description("同一次操作重试复用；载荷不同不能复用"));
                    if(name.endsWith(".Action"))schema.addProperty("action",new StringSchema()._enum(List.of("BLOCK","RELEASE")));
                }
                if(name.endsWith("ReportModels.Create") || name.endsWith("ReportModels.Resolve")) {
                    schema.setRequired(name.endsWith(".Create")?List.of("category","reason","requestId"):List.of("action","reason","version","requestId"));
                    schema.addProperty("reason",new StringSchema().minLength(2).maxLength(300));
                    schema.addProperty("requestId",new StringSchema().format("uuid"));
                    if(name.endsWith(".Create"))schema.addProperty("category",new StringSchema()._enum(List.of("MISLEADING","PROHIBITED","SPAM","OTHER")));
                    else {schema.addProperty("action",new StringSchema()._enum(List.of("DISMISS","BLOCK")));schema.addProperty("version",new IntegerSchema().minimum(BigDecimal.ZERO).maximum(BigDecimal.ONE));schema.addProperty("productVersion",new IntegerSchema().format("int64").minimum(BigDecimal.ZERO).maximum(new BigDecimal("9007199254740990")).description("BLOCK必填；DISMISS必须省略或null"));}
                }
                if(name.endsWith("CreatePublicationRequest"))schema.setRequired(List.of("requestId","product"));
            });
        };
    }
    private static void password(Schema<?> schema) {
        if(schema.getProperties()!=null && schema.getProperties().get("password") instanceof Schema<?> p)
            p.setWriteOnly(true);
    }
}
