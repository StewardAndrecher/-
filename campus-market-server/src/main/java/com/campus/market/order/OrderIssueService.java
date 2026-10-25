package com.campus.market.order;

import com.campus.market.auth.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.util.Set;
import static com.campus.market.order.OrderIssueModels.*;

@Service
public class OrderIssueService {
    private static final Set<String> ELIGIBLE=Set.of("PAID","DELIVERED","COMPLETED");
    private static final Set<String> REASONS=Set.of("ITEM_CONDITION","HANDOVER","OTHER");
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final IssueResolutionRepository resolutions;
    private record Owned(long id,long buyer,long seller,String status,Timestamp paidAt) {}
    public OrderIssueService(JdbcTemplate jdbc,SessionService sessions,IssueResolutionRepository resolutions){this.jdbc=jdbc;this.sessions=sessions;this.resolutions=resolutions;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Summary summary(String rawId,String auth){long user=sessions.currentUser(auth).id();return snapshot(owned(id(rawId),user,false),user);}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt create(String rawId,Create request,String auth) {
        long user=sessions.currentUser(auth).id();long id=id(rawId);
        if(request==null||request.reason()==null||!REASONS.contains(request.reason()))throw invalid("请选择有效的问题类型");
        String description=normalize(request.description());
        // Only the order row is locked; this operation never takes a product lock.
        Owned order=owned(id,user,true);sessions.currentUser(auth);
        if(!ELIGIBLE.contains(order.status()))throw error(409,"ISSUE_NOT_ELIGIBLE","已模拟付款、已交付或已完成订单可提交交易问题");
        Summary before=snapshot(order,user);
        if(before.issue()!=null) {
            if(!before.issue().reason().equals(request.reason())||!before.issue().description().equals(description))
                throw error(409,"ISSUE_ALREADY_SUBMITTED","你已提交交易问题，本次内容不同；请查看首次记录");
            return new Receipt(before,true);
        }
        jdbc.update("INSERT INTO order_issues(order_id,applicant_id,reason,description) VALUES(?,?,?,?)",id,user,request.reason(),description);
        return new Receipt(snapshot(order,user),false);
    }
    private Owned owned(long id,long user,boolean lock) {
        var values=jdbc.query("SELECT id,buyer_id,seller_id,status,paid_at FROM market_orders WHERE id=?"+(lock?" FOR UPDATE":""),
                (r,n)->new Owned(r.getLong("id"),r.getLong("buyer_id"),r.getLong("seller_id"),r.getString("status"),r.getTimestamp("paid_at")),id);
        var order=values.stream().findFirst().orElseThrow(OrderIssueService::missing);
        if(user!=order.buyer()&&user!=order.seller())throw missing();return order;
    }
    private Summary snapshot(Owned order,long user) {
        var issues=jdbc.query("SELECT reason,description,created_at FROM order_issues WHERE order_id=? AND applicant_id=?",(r,n)->{
            String reason=r.getString("reason"),description=r.getString("description");Timestamp created=r.getTimestamp("created_at");
            try{if(reason==null||!REASONS.contains(reason)||!normalize(description).equals(description))throw unavailable();}
            catch(OrderException invalid){throw unavailable();}
            if(!ELIGIBLE.contains(order.status())||order.paidAt()==null||created==null||created.before(order.paidAt()))throw unavailable();
            return new Issue(reason,description,created.toLocalDateTime().toString(),null);
        },order.id(),user);
        if(issues.size()>1)throw unavailable();
        Issue issue=issues.isEmpty()?null:issues.get(0);
        if(issue!=null){var resolved=resolutions.find(order.id(),user,Timestamp.valueOf(java.time.LocalDateTime.parse(issue.createdAt())));issue=new Issue(issue.reason(),issue.description(),issue.createdAt(),resolved==null?null:resolved.resolution());}
        return new Summary(order.id(),user,order.status(),user==order.buyer()?"BUYER":"SELLER",ELIGIBLE.contains(order.status())&&issue==null,issue);
    }
    private static String normalize(String raw){return OrderIssueText.normalize(raw);}
    private static long id(String raw) {
        if(raw==null||!raw.matches("[1-9][0-9]{0,15}"))throw invalid("订单编号格式不正确");
        try{long value=Long.parseLong(raw);if(value>9007199254740991L)throw invalid("订单编号超出范围");return value;}
        catch(NumberFormatException e){throw invalid("订单编号超出范围");}
    }
    private static OrderException invalid(String message){return error(400,"INVALID_ORDER_ISSUE",message);}
    private static OrderException missing(){return error(404,"ORDER_ISSUE_NOT_FOUND","订单不存在或当前账号不可见");}
    private static OrderException unavailable(){return error(503,"ORDER_ISSUE_UNAVAILABLE","交易问题记录异常，请稍后重试");}
    private static OrderException error(int status,String code,String message){return new OrderException(status,code,message);}
}
