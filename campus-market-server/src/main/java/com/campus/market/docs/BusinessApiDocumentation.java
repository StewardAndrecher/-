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
    private static final Set<String> PUBLIC=Set.of("POST /api/users/register","POST /api/users/login",
            "GET /api/products","GET /api/product-sorts","GET /api/categories","GET /api/images/{id}","GET /api/sellers/{id}/products");
    private static final Set<String> OPTIONAL=Set.of("GET /api/products/{id}",
            "GET /api/products/{productId}/comments","GET /api/products/{id}/order-state");
    private static final Set<String> CREATED=Set.of("POST /api/users/register","POST /api/products","POST /api/images");
    private static final Set<String> REPLAY=Set.of("POST /api/products/{id}/orders","POST /api/product-publications",
            "POST /api/products/{productId}/purchase-intent","POST /api/products/{productId}/comments",
            "POST /api/purchase-intents/{intentId}/messages","PUT /api/product-draft-backups/{id}");
    private static final Set<String> EMPTY=Set.of("POST /api/users/logout","DELETE /api/product-draft-backups/{id}",
            "DELETE /api/products/{productId}/comments/{commentId}");
    private static final Map<String,String> TAGS=Map.ofEntries(
            Map.entry("UserController","账号"),Map.entry("LoginController","账号"),Map.entry("SessionController","会话"),
            Map.entry("UserProfileController","个人资料"),Map.entry("ProductController","商品"),Map.entry("CategoryController","分类"),Map.entry("ProductSortController","商品排序"),
            Map.entry("ImageController","图片"),Map.entry("FavoriteController","收藏"),Map.entry("CommentController","商品留言"),
            Map.entry("PublicationController","发布幂等"),Map.entry("DraftBackupController","草稿备份"),
            Map.entry("PurchaseIntentController","购买意向"),Map.entry("PurchaseIntentMessageController","意向沟通"),
            Map.entry("PurchaseIntentUnreadController","未读概览"),Map.entry("SellerStoreController","卖家主页"),Map.entry("OrderController","订单"));
    private static final Map<String,String> VERBS=Map.ofEntries(
            Map.entry("register","注册"),Map.entry("login","登录"),Map.entry("logout","退出登录"),Map.entry("me","当前用户"),
            Map.entry("listProducts","公开商品列表"),Map.entry("getProduct","商品详情"),Map.entry("listMyProducts","我发布的商品"),
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
                else if(REPLAY.contains(key))responses.addApiResponse("200",response("幂等重放或更新成功",success)).addApiResponse("201",response("首次创建",success));
                else responses.addApiResponse(CREATED.contains(key)?"201":"200",response(CREATED.contains(key)?"创建成功":"成功",success));
                responses.addApiResponse("400",error("请求参数或JSON格式无效")).addApiResponse("500",error("服务器错误；使用响应X-Request-Id定位日志"));
                if(!open)responses.addApiResponse("401",error("未登录或会话无效"));
                if(!open && !optional)responses.addApiResponse("403",error("无权执行该操作"));
                if(path.contains("{"))responses.addApiResponse("404",error("资源不存在或当前用户不可见"));
                if(method!=PathItem.HttpMethod.GET && !EMPTY.contains(key) && !key.equals("POST /api/users/login"))responses.addApiResponse("409",error("状态、版本或幂等请求发生冲突"));
                if(key.equals("POST /api/users/login")) {
                    responses.addApiResponse("401",error("用户名或密码错误"));
                    for(String status:List.of("429","503"))responses.addApiResponse(status,error(status.equals("429")?"登录请求受限":"登录保护暂时不可用")
                            .addHeaderObject("Retry-After",new Header().description("建议等待的秒数").schema(new IntegerSchema().minimum(BigDecimal.ONE))));
                }
                operation.setResponses(responses);
                if(REPLAY.contains(key))operation.setDescription("首次创建返回201，重放或更新返回200。请求编号与业务幂等键用途不同；相同操作重试应按该接口约定复用幂等键或版本。");
                if(path.endsWith("/simulate-payment"))operation.setDescription("教学模拟付款，不连接真实支付渠道；过期订单可能先被关闭并返回409。");
            }));
            api.getComponents().getSchemas().forEach((name,schema)->{
                if(name.endsWith("LoginUserRequest")){schema.setRequired(List.of("username","password"));password(schema);}
                if(name.endsWith("RegisterUserRequest")){schema.setRequired(List.of("username","password","nickname"));password(schema);}
                if(name.endsWith("CreateProductRequest"))schema.setRequired(List.of("title","priceInCents"));
                if(name.endsWith("OrderModels.Create")) {
                    schema.setRequired(List.of("productVersion","requestId"));
                    schema.addProperty("productVersion",new IntegerSchema().format("int64").minimum(BigDecimal.ZERO).maximum(new BigDecimal("9007199254740991")));
                    schema.addProperty("requestId",new StringSchema().format("uuid").description("客户端生成的下单幂等键；同一操作重试复用此值"));
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
