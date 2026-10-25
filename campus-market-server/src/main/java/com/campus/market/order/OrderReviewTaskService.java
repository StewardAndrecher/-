package com.campus.market.order;

import com.campus.market.auth.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;
import java.util.Set;
import static com.campus.market.order.OrderReviewTaskModels.*;

@Service
public class OrderReviewTaskService {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    public OrderReviewTaskService(JdbcTemplate jdbc,SessionService sessions){this.jdbc=jdbc;this.sessions=sessions;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String role,String status,int requestedPage,int size,String authorization) {
        long user=sessions.currentUser(authorization).id();
        if(role==null||!Set.of("BUYER","SELLER").contains(role)||status==null||!Set.of("PENDING","REVIEWED").contains(status)
                ||requestedPage<1||requestedPage>1000000||size<1||size>20)throw OrderException.invalid("评价清单参数不正确");
        // Only these server-owned column names enter SQL. The authenticated user is bound twice.
        String scope=" FROM market_orders o LEFT JOIN order_reviews r ON r.order_id=o.id AND r.reviewer_id=?"
                +" WHERE o."+(role.equals("BUYER")?"buyer_id":"seller_id")+"=? AND o.status='COMPLETED'";
        Summary summary=jdbc.queryForObject("SELECT COUNT(*) total,COALESCE(SUM(r.reviewer_id IS NULL),0) pending,"+
                "COALESCE(SUM(r.reviewer_id IS NOT NULL),0) reviewed"+scope,
                (r,n)->new Summary(r.getLong("total"),r.getLong("pending"),r.getLong("reviewed")),user,user);
        if(summary==null||summary.total()<0||summary.total()>9007199254740991L
                ||summary.pending()+summary.reviewed()!=summary.total())throw unavailable();
        long total=status.equals("PENDING")?summary.pending():summary.reviewed();
        long pages=Math.max(1,(total+size-1)/size);int page=(int)Math.min(requestedPage,pages);
        String filter=status.equals("PENDING")?" AND r.reviewer_id IS NULL":" AND r.reviewer_id IS NOT NULL";
        List<Entry> items=jdbc.query("SELECT o.id,o.product_title_snapshot,o.price_in_cents_snapshot,o.completed_at,r.created_at reviewed_at"+
                scope+filter+" ORDER BY o.completed_at DESC,o.id DESC LIMIT ? OFFSET ?",(r,n)->{
                    var completed=r.getTimestamp("completed_at");var reviewed=r.getTimestamp("reviewed_at");
                    if(completed==null||(reviewed!=null&&reviewed.before(completed)))throw unavailable();
                    return new Entry(r.getLong("id"),r.getString("product_title_snapshot"),r.getLong("price_in_cents_snapshot"),
                            completed.toLocalDateTime().toString(),reviewed==null?null:reviewed.toLocalDateTime().toString());
                },user,user,size,(long)(page-1)*size);
        return new Page(user,role,status,page,size,total,pages,summary,List.copyOf(items));
    }
    private static OrderReviewException unavailable(){return new OrderReviewException(503,"REVIEW_TASK_UNAVAILABLE","评价清单数据异常，请稍后重试");}
}
