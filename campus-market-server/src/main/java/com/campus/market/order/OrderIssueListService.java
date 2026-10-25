package com.campus.market.order;

import com.campus.market.auth.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;
import java.util.Set;
import static com.campus.market.order.OrderIssueListModels.*;

@Service
public class OrderIssueListService {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    public OrderIssueListService(JdbcTemplate jdbc,SessionService sessions){this.jdbc=jdbc;this.sessions=sessions;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String role,String status,int requested,int size,String auth) {
        long user=sessions.currentUser(auth).id();
        if(role==null||!Set.of("BUYER","SELLER").contains(role)||status==null||!Set.of("ALL","OPEN","RESOLVED").contains(status)
                ||requested<1||requested>1000000||size<1||size>20)throw OrderException.invalid("交易反馈清单参数不正确");
        // Only a server-owned role column enters SQL; applicant identity always comes from the session.
        String from=" FROM order_issues i JOIN market_orders o ON o.id=i.order_id"
                +" LEFT JOIN order_issue_resolutions r ON r.order_id=i.order_id AND r.applicant_id=i.applicant_id"
                +" WHERE i.applicant_id=? AND o."+(role.equals("BUYER")?"buyer_id":"seller_id")+"=?";
        Summary summary=jdbc.queryForObject("SELECT COUNT(*) total,COALESCE(SUM(r.order_id IS NULL),0) open_count,"
                +"COALESCE(SUM(r.order_id IS NOT NULL),0) resolved_count"+from,
                (r,n)->new Summary(r.getLong("total"),r.getLong("open_count"),r.getLong("resolved_count")),user,user);
        if(summary==null||summary.total()<0||summary.total()>9007199254740991L||summary.open()<0||summary.resolved()<0
                ||summary.open()+summary.resolved()!=summary.total())throw unavailable();
        long total=status.equals("ALL")?summary.total():status.equals("OPEN")?summary.open():summary.resolved();
        long pages=Math.max(1,(total+size-1)/size);int page=(int)Math.min(requested,pages);
        String filter=status.equals("ALL")?"":status.equals("OPEN")?" AND r.order_id IS NULL":" AND r.order_id IS NOT NULL";
        List<Entry> items=jdbc.query("SELECT i.order_id,i.reason,i.created_at,o.product_title_snapshot,o.status,o.paid_at,"
                +"r.order_id resolved,r.decision,r.created_at resolved_at"+from+filter
                +" ORDER BY i.created_at DESC,i.order_id DESC LIMIT ? OFFSET ?",(r,n)->{
                    long order=r.getLong("order_id");String title=r.getString("product_title_snapshot"),reason=r.getString("reason"),state=r.getString("status"),decision=r.getString("decision");
                    var at=r.getTimestamp("created_at");var paid=r.getTimestamp("paid_at");var resolved=r.getTimestamp("resolved_at");
                    boolean handled=r.getObject("resolved")!=null;
                    if(order<1||order>9007199254740991L||title==null||title.length()<2||title.length()>50||reason==null
                            ||!Set.of("ITEM_CONDITION","HANDOVER","OTHER").contains(reason)||state==null||!Set.of("PAID","DELIVERED","COMPLETED").contains(state)
                            ||at==null||paid==null||at.before(paid)
                            ||(handled?(decision==null||!Set.of("ANSWERED","DISMISSED").contains(decision)||resolved==null||resolved.before(at)):(decision!=null||resolved!=null)))throw unavailable();
                    return new Entry(order,title,reason,at.toLocalDateTime().toString(),decision,resolved==null?null:resolved.toLocalDateTime().toString());
                },user,user,size,(long)(page-1)*size);
        return new Page(user,role,status,page,size,total,pages,summary,List.copyOf(items));
    }
    private static OrderException unavailable(){return new OrderException(503,"ISSUE_LIST_UNAVAILABLE","交易反馈清单数据异常，请稍后重试");}
}
