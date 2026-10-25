package com.campus.market.admin;

import com.campus.market.order.OrderException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;

@Service
public class IssueOverviewService {
    public record IssueOverview(String sampledAt,long total,long open,long resolved,long answered,long dismissed,
                           long openOver24h,long openOver72h,String oldestOpenAt) {}
    private final JdbcTemplate jdbc;
    private final AdminAccessService admins;
    public IssueOverviewService(JdbcTemplate jdbc,AdminAccessService admins){this.jdbc=jdbc;this.admins=admins;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public IssueOverview sample(String auth) {
        admins.requireAdmin(auth);
        // A single statement uses database time for every count and time threshold, including an empty table.
        return jdbc.queryForObject("SELECT clock.sampled_at,COUNT(i.order_id) total,"
            +"COALESCE(SUM(i.order_id IS NOT NULL AND r.order_id IS NULL),0) open_count,"
            +"COALESCE(SUM(r.order_id IS NOT NULL),0) resolved_count,"
            +"COALESCE(SUM(r.decision='ANSWERED'),0) answered,COALESCE(SUM(r.decision='DISMISSED'),0) dismissed,"
            +"COALESCE(SUM(i.order_id IS NOT NULL AND r.order_id IS NULL AND i.created_at<=clock.sampled_at-INTERVAL 24 HOUR),0) over24,"
            +"COALESCE(SUM(i.order_id IS NOT NULL AND r.order_id IS NULL AND i.created_at<=clock.sampled_at-INTERVAL 72 HOUR),0) over72,"
            +"MIN(CASE WHEN r.order_id IS NULL THEN i.created_at END) oldest,"
            +"COALESCE(SUM(CASE WHEN i.order_id IS NOT NULL AND (o.id IS NULL OR o.paid_at IS NULL OR i.created_at<o.paid_at OR i.created_at>clock.sampled_at"
            +" OR i.applicant_id NOT IN(o.buyer_id,o.seller_id) OR o.status NOT IN('PAID','DELIVERED','COMPLETED')"
            +" OR (r.order_id IS NOT NULL AND (r.decision NOT IN('ANSWERED','DISMISSED') OR r.created_at<i.created_at OR r.created_at>clock.sampled_at))) THEN 1 ELSE 0 END),0) invalid_count"
            +" FROM (SELECT CURRENT_TIMESTAMP(6) sampled_at) clock LEFT JOIN order_issues i ON TRUE"
            +" LEFT JOIN order_issue_resolutions r ON r.order_id=i.order_id AND r.applicant_id=i.applicant_id"
            +" LEFT JOIN market_orders o ON o.id=i.order_id GROUP BY clock.sampled_at",(r,n)->{
                Timestamp sampled=r.getTimestamp("sampled_at"),oldest=r.getTimestamp("oldest");
                long total=r.getLong("total"),open=r.getLong("open_count"),resolved=r.getLong("resolved_count"),answered=r.getLong("answered"),dismissed=r.getLong("dismissed"),over24=r.getLong("over24"),over72=r.getLong("over72");
                for(long count:new long[]{total,open,resolved,answered,dismissed,over24,over72})if(count<0||count>9007199254740991L)throw unavailable();
                if(sampled==null||r.getLong("invalid_count")!=0||open+resolved!=total||answered+dismissed!=resolved||over72>over24||over24>open
                        ||(open==0)!=(oldest==null)||(oldest!=null&&oldest.after(sampled)))throw unavailable();
                return new IssueOverview(sampled.toLocalDateTime().toString(),total,open,resolved,answered,dismissed,over24,over72,oldest==null?null:oldest.toLocalDateTime().toString());
            });
    }
    private static OrderException unavailable(){return new OrderException(503,"ISSUE_OVERVIEW_UNAVAILABLE","反馈处理概览数据异常，请稍后重试");}
}
