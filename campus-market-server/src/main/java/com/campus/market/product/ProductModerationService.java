package com.campus.market.product;

import com.campus.market.admin.AdminAccessService;
import com.campus.market.auth.SessionService;
import com.campus.market.order.OrderRepository;
import com.campus.market.user.UserResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;
import java.util.UUID;
import static com.campus.market.product.ProductModerationModels.*;

@Service
public class ProductModerationService {
    private final com.campus.market.notification.NotificationEvents notifications;
    private final ProductRepository products;private final ProductModerationRepository reviews;
    private final SessionService sessions;private final AdminAccessService admins;private final OrderRepository orders;private final ProductDetailCache cache;
    public ProductModerationService(ProductRepository products,ProductModerationRepository reviews,SessionService sessions,AdminAccessService admins,OrderRepository orders,ProductDetailCache cache,com.campus.market.notification.NotificationEvents notifications) {
        this.notifications=notifications;
        this.products=products;this.reviews=reviews;this.sessions=sessions;this.admins=admins;this.orders=orders;this.cache=cache;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page adminProducts(int page,int size,String keyword,String status,String state,String authorization) {
        admins.requireAdmin(authorization);
        if(page<1||size<1||size>20)throw new InvalidProductException("页码至少为1，每页数量为1～20");
        String text=keyword==null?"":keyword.strip(),filter=status==null?"":status,review=state==null?"":state;
        if(text.length()>50||!Set.of("","ON_SALE","OFF_SHELF","SOLD").contains(filter)||!Set.of("","CLEAR","BLOCKED","APPEALED").contains(review))throw new InvalidProductException("商品筛选条件不正确");
        long total=reviews.count(text,filter,review);return new Page(reviews.page(text,filter,review,size,((long)page-1)*size),total,page,size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail adminDetail(long id,String authorization) {admins.requireAdmin(authorization);id(id);return detail(id);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail sellerDetail(long id,String authorization) {
        var user=sessions.currentUser(authorization);id(id);var product=products.findById(id).orElseThrow(()->new ProductNotFoundException("商品不存在"));owner(product,user);return detail(id);
    }
    private Detail detail(long id) {return new Detail(reviews.item(id),reviews.events(id));}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt adminAction(long id,Action request,String authorization) {
        var admin=admins.requireAdmin(authorization);
        if(request==null||!Set.of("BLOCK","RELEASE").contains(request.action()==null?"":request.action()))throw new InvalidProductException("请指定下架或解除限制操作");
        return mutate(id,request.action(),request.reason(),request.version(),request.requestId(),admin,false);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt appeal(long id,Appeal request,String authorization) {
        var seller=sessions.currentUser(authorization);if(request==null)throw new InvalidProductException("请填写复核申请");
        return mutate(id,"APPEAL",request.reason(),request.version(),request.requestId(),seller,true);
    }
    private Receipt mutate(long id,String action,String rawReason,Long version,String rawRequestId,UserResponse actor,boolean seller) {
        id(id);String reason=rawReason==null?"":rawReason.strip();
        if(reason.length()<2||reason.length()>200||reason.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw new InvalidProductException("处理原因或申请说明需要2～200个字符");
        if(version==null||version<0||version>9_007_199_254_740_991L)throw new InvalidProductException("请提供有效的商品版本号");
        String requestId;
        try {requestId=UUID.fromString(rawRequestId).toString();if(!requestId.equals(rawRequestId))throw new IllegalArgumentException();}
        catch(IllegalArgumentException|NullPointerException invalid){throw new InvalidProductException("请求标识必须为规范UUID");}
        var product=products.findByIdForUpdate(id).orElseThrow(()->new ProductNotFoundException("商品不存在"));
        if(seller)owner(product,actor);
        var previous=reviews.existing(actor.id(),requestId);
        if(previous.isPresent()) {
            var old=previous.get();if(old.productId()!=id||!old.action().equals(action)||!old.reason().equals(reason)||old.version()!=version)throw new ProductConflictException("请求标识已用于不同操作");
            return new Receipt(detail(id),true);
        }
        if(product.version()!=version)throw new ProductConflictException("商品或处理记录已更新，请刷新后再操作");
        if(version==9_007_199_254_740_991L)throw new ProductConflictException("商品版本已达上限，暂不能处理");
        if(product.status()==ProductStatus.SOLD)throw new ProductConflictException("商品已售出，不能通过商品审核改变交易结果");
        orders.requireUnreserved(id);
        var before=reviews.item(id).moderation();String from=before.state(),to;
        switch(action) {
            case "BLOCK" -> {if(!from.equals("CLEAR"))throw new ProductConflictException("商品已受平台限制，请刷新处理记录");to="BLOCKED";}
            case "APPEAL" -> {if(!from.equals("BLOCKED"))throw new ProductConflictException("仅受限且尚未申请复核的商品可以申请");to="APPEALED";}
            case "RELEASE" -> {if(from.equals("CLEAR"))throw new ProductConflictException("商品当前没有平台限制");to="CLEAR";}
            default -> throw new InvalidProductException("处理操作不正确");
        }
        // All three operations remain OFF_SHELF; releasing a restriction never publishes on behalf of the seller.
        if(reviews.keepOffAndAdvance(id,version)!=1)throw new ProductConflictException("商品已更新，请刷新后再操作");
        long event;
        try {event=reviews.append(product,actor.id(),seller?"SELLER":"ADMIN",action,reason,from,to,requestId);}
        catch(DuplicateKeyException collision){throw new ProductConflictException("请求标识已被使用，请刷新并核对处理结果");}
        reviews.state(id,to,action.equals("APPEAL")?before.reason():reason,action.equals("APPEAL")?reason:null,event);
        notifications.moderation(event,id,product.seller()==null?null:product.seller().id(),action,product.title(),reason);
        cache.evictAfterCommit(id,version);
        return new Receipt(detail(id),false);
    }
    private void id(long id){if(id<1||id>9_007_199_254_740_991L)throw new InvalidProductException("商品ID不正确");}
    private void owner(ProductResponse product,UserResponse user) {
        if(product.seller()==null||product.seller().id()!=user.id())throw new ProductNotFoundException("商品不存在或不属于当前账号");
    }
}
