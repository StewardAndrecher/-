package com.campus.market.admin;

import com.campus.market.order.*;
import com.campus.market.notification.NotificationEvents;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import static com.campus.market.admin.AdminOrderIssueModels.*;

@Service
public class AdminOrderIssueService {
    private final JdbcTemplate jdbc;
    private final AdminAccessService admins;
    private final IssueResolutionRepository resolutions;
    private final NotificationEvents notifications;
    private record Case(Detail detail,long buyer,long seller,IssueResolutionRepository.Stored stored) {}
    public AdminOrderIssueService(JdbcTemplate jdbc,AdminAccessService admins,IssueResolutionRepository resolutions,NotificationEvents notifications){this.jdbc=jdbc;this.admins=admins;this.resolutions=resolutions;this.notifications=notifications;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String status,int requested,int size,String auth){return page(status,"ALL","",requested,size,auth);}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String status,String age,int requested,int size,String auth){return page(status,age,"",requested,size,auth);}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String status,String age,String rawOrder,int requested,int size,String auth) {
        admins.requireAdmin(auth);
        if(rawOrder==null)throw bad("订单编号格式不正确");
        Long exact=rawOrder.isEmpty()?null:id(rawOrder);
        if(status==null||!Set.of("OPEN","RESOLVED","ALL").contains(status)||age==null||!Set.of("ALL","OVER_24H","OVER_72H").contains(age)
                ||(!age.equals("ALL")&&!status.equals("OPEN"))||requested<1||requested>1000000||size<1||size>20)throw bad("工单筛选参数不正确");
        Timestamp sampled=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
        if(sampled==null)throw unavailable();
        String from=" FROM order_issues i LEFT JOIN order_issue_resolutions r ON r.order_id=i.order_id AND r.applicant_id=i.applicant_id";
        String filter=status.equals("ALL")?" WHERE 1=1":status.equals("OPEN")?" WHERE r.order_id IS NULL":" WHERE r.order_id IS NOT NULL";
        java.util.List<Object> params=new ArrayList<>();
        if(exact!=null){filter+=" AND i.order_id=?";params.add(exact);}
        if(!age.equals("ALL")){filter+=" AND i.created_at<=?";params.add(Timestamp.valueOf(sampled.toLocalDateTime().minusHours(age.equals("OVER_24H")?24:72)));}
        long total=jdbc.queryForObject("SELECT COUNT(*)"+from+filter,Long.class,params.toArray());
        if(total<0||total>9007199254740991L||(exact!=null&&total>2))throw unavailable();
        long pages=Math.max(1,(total+size-1)/size);int page=(int)Math.min(requested,pages);
        String direction=age.equals("ALL")?" DESC":" ASC";
        params.add(size);params.add((long)(page-1)*size);
        var items=jdbc.query("SELECT i.order_id,i.applicant_id,i.reason,i.created_at,o.buyer_id,o.seller_id,r.order_id resolved"+from+
                " JOIN market_orders o ON o.id=i.order_id"+filter+" ORDER BY i.created_at"+direction+",i.order_id"+direction+",i.applicant_id"+direction+" LIMIT ? OFFSET ?",(r,n)->{
                    long applicant=r.getLong("applicant_id"),buyer=r.getLong("buyer_id"),seller=r.getLong("seller_id");String reason=r.getString("reason");var at=r.getTimestamp("created_at");
                    if((applicant!=buyer&&applicant!=seller)||at==null||at.after(sampled)||reason==null||!Set.of("ITEM_CONDITION","HANDOVER","OTHER").contains(reason))throw unavailable();
                    return new Item(r.getLong("order_id"),applicant,applicant==buyer?"BUYER":"SELLER",reason,at.toLocalDateTime().toString(),r.getObject("resolved")==null?"OPEN":"RESOLVED");
                },params.toArray());
        return new Page(status,age,exact,sampled.toLocalDateTime().toString(),page,size,total,pages,List.copyOf(items));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(String order,String applicant,String auth){admins.requireAdmin(auth);return load(id(order),id(applicant)).detail();}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt resolve(String rawOrder,String rawApplicant,Resolve request,String auth) {
        long actor=admins.requireAdmin(auth).id(),order=id(rawOrder),applicant=id(rawApplicant);
        if(request==null||request.decision()==null||!Set.of("ANSWERED","DISMISSED").contains(request.decision()))throw bad("请选择有效的处理结果");
        String explanation=OrderIssueText.normalize(request.explanation()),key=key(request.requestId());
        // Resolve locks only the immutable issue row. It never acquires product/order locks afterwards.
        var locked=jdbc.queryForList("SELECT order_id FROM order_issues WHERE order_id=? AND applicant_id=? FOR UPDATE",order,applicant);
        if(locked.isEmpty())throw missing();
        admins.requireAdmin(auth); // Re-check live session and grant after any lock wait.
        Case value=load(order,applicant);
        if(actor==value.buyer()||actor==value.seller())throw new OrderException(403,"ISSUE_SELF_REVIEW_FORBIDDEN","不能处理自己参与交易的反馈");
        if(value.stored()!=null) {
            var previous=value.stored();var result=previous.resolution();
            if(previous.resolverId()==actor&&previous.requestId().equals(key)&&result.decision().equals(request.decision())&&result.explanation().equals(explanation))return new Receipt(value.detail(),true);
            throw conflict("反馈已被处理，请重新加载查看结果");
        }
        try{jdbc.update("INSERT INTO order_issue_resolutions(order_id,applicant_id,resolver_id,decision,explanation,request_id) VALUES(?,?,?,?,?,?)",order,applicant,actor,request.decision(),explanation,key);}
        catch(DuplicateKeyException duplicate){throw conflict("处理请求标识已使用，请重新加载核对");}
        notifications.orderIssueResolved(order,applicant);
        return new Receipt(load(order,applicant).detail(),false);
    }
    private Case load(long order,long applicant) {
        var records=jdbc.query("SELECT i.reason,i.description,i.created_at,o.buyer_id,o.seller_id,o.status,o.product_title_snapshot,o.price_in_cents_snapshot,o.paid_at FROM order_issues i JOIN market_orders o ON o.id=i.order_id WHERE i.order_id=? AND i.applicant_id=?",(r,n)->{
            long buyer=r.getLong("buyer_id"),seller=r.getLong("seller_id");String reason=r.getString("reason"),text=r.getString("description"),status=r.getString("status");var at=r.getTimestamp("created_at");var paid=r.getTimestamp("paid_at");
            try{if((applicant!=buyer&&applicant!=seller)||reason==null||!Set.of("ITEM_CONDITION","HANDOVER","OTHER").contains(reason)||!Set.of("PAID","DELIVERED","COMPLETED").contains(status)||!OrderIssueText.normalize(text).equals(text)||at==null||paid==null||at.before(paid))throw unavailable();}
            catch(OrderException invalid){throw unavailable();}
            var stored=resolutions.find(order,applicant,at);
            var detail=new Detail(order,applicant,applicant==buyer?"BUYER":"SELLER",status,r.getString("product_title_snapshot"),r.getLong("price_in_cents_snapshot"),reason,text,at.toLocalDateTime().toString(),stored==null?"OPEN":"RESOLVED",stored==null?null:stored.resolution());
            return new Case(detail,buyer,seller,stored);
        },order,applicant);
        return records.stream().findFirst().orElseThrow(AdminOrderIssueService::missing);
    }
    private static long id(String raw){if(raw==null||!raw.matches("[1-9][0-9]{0,15}"))throw bad("工单编号格式不正确");try{long id=Long.parseLong(raw);if(id>9007199254740991L)throw bad("编号超出范围");return id;}catch(NumberFormatException invalid){throw bad("编号超出范围");}}
    private static String key(String raw){try{String value=UUID.fromString(raw).toString();if(!value.equals(raw))throw new IllegalArgumentException();return value;}catch(IllegalArgumentException|NullPointerException invalid){throw bad("处理请求标识必须为规范UUID");}}
    private static OrderException bad(String text){return new OrderException(400,"INVALID_ISSUE_RESOLUTION",text);}
    private static OrderException missing(){return new OrderException(404,"ISSUE_NOT_FOUND","反馈工单不存在");}
    private static OrderException conflict(String text){return new OrderException(409,"ISSUE_RESOLUTION_CONFLICT",text);}
    private static OrderException unavailable(){return new OrderException(503,"ISSUE_RESOLUTION_UNAVAILABLE","反馈工单数据异常，请稍后重试");}
}
