package com.campus.market.order;

import com.campus.market.auth.SessionService;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReceivedReviewService {
    public record Summary(long total,long good,long neutral,long low) {}
    public record Entry(long orderId,String productTitleSnapshot,int rating,String content,String completedAt,String reviewedAt) {}
    public record Page(long userId,String role,String rating,int page,int size,long total,long totalPages,
                       Summary summary,List<Entry> items,String sampledAt) {}
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    public ReceivedReviewService(JdbcTemplate jdbc,SessionService sessions){this.jdbc=jdbc;this.sessions=sessions;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(String authorization,Map<String,List<String>> query) {
        long user=sessions.currentUser(authorization).id();
        if(query==null||!Set.of("role","rating","page","size").containsAll(query.keySet()))throw invalid();
        String role=single(query,"role","BUYER"),rating=single(query,"rating","ALL");
        if(!Set.of("BUYER","SELLER").contains(role)||!Set.of("ALL","GOOD","NEUTRAL","LOW").contains(rating))throw invalid();
        int requested=number(query,"page",1,1000000),size=number(query,"size",10,20);
        Timestamp sampled=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
        // Only server-owned enum choices select SQL column names. Identity and limits stay bound values.
        String scope=" FROM market_orders o JOIN order_reviews r ON r.order_id=o.id AND r.reviewer_id=o."
                +(role.equals("BUYER")?"seller_id":"buyer_id")+" WHERE o."
                +(role.equals("BUYER")?"buyer_id":"seller_id")+"=? AND o.status='COMPLETED' AND o.buyer_id<>o.seller_id";
        Summary summary=jdbc.queryForObject("SELECT COUNT(*) total,COALESCE(SUM(r.rating IN (4,5)),0) good,"
                +"COALESCE(SUM(r.rating=3),0) neutral,COALESCE(SUM(r.rating IN (1,2)),0) low"+scope,
                (r,n)->new Summary(r.getLong("total"),r.getLong("good"),r.getLong("neutral"),r.getLong("low")),user);
        if(summary==null||summary.total()<0||summary.total()>9007199254740991L
                ||summary.good()<0||summary.neutral()<0||summary.low()<0
                ||summary.good()+summary.neutral()+summary.low()!=summary.total())throw unavailable();
        long total=switch(rating){case "GOOD"->summary.good();case "NEUTRAL"->summary.neutral();case "LOW"->summary.low();default->summary.total();};
        String filter=switch(rating){case "GOOD"->" AND r.rating IN (4,5)";case "NEUTRAL"->" AND r.rating=3";case "LOW"->" AND r.rating IN (1,2)";default->"";};
        long pages=Math.max(1,(total+size-1)/size);int page=(int)Math.min(requested,pages);
        var items=jdbc.query("SELECT o.id,o.product_title_snapshot,o.completed_at,r.rating,r.content,r.created_at reviewed_at"
                +scope+filter+" ORDER BY r.created_at DESC,o.id DESC LIMIT ? OFFSET ?",(r,n)->{
            long id=r.getLong("id");String title=r.getString("product_title_snapshot"),content=r.getString("content");
            int score=r.getInt("rating");Timestamp completed=r.getTimestamp("completed_at"),reviewed=r.getTimestamp("reviewed_at");
            if(id<1||id>9007199254740991L||title==null||title.length()<2||title.length()>50||score<1||score>5
                    ||content==null||completed==null||reviewed==null||reviewed.before(completed)||reviewed.after(sampled))throw unavailable();
            try{if(!OrderReviewService.normalize(content).equals(content))throw unavailable();}catch(OrderReviewException e){throw unavailable();}
            return new Entry(id,title,score,content,completed.toLocalDateTime().toString(),reviewed.toLocalDateTime().toString());
        },user,size,(page-1L)*size);
        if(items.size()!=Math.min(size,Math.max(0,total-(page-1L)*size)))throw unavailable();
        return new Page(user,role,rating,page,size,total,pages,summary,List.copyOf(items),sampled.toLocalDateTime().toString());
    }
    private static String single(Map<String,List<String>> q,String name,String fallback){
        if(!q.containsKey(name))return fallback;
        var values=q.get(name);if(values==null||values.size()!=1||values.get(0)==null)throw invalid();return values.get(0);
    }
    private static int number(Map<String,List<String>> q,String name,int fallback,int max){
        String value=single(q,name,""+fallback);if(!value.matches("[1-9][0-9]{0,6}"))throw invalid();
        int number=Integer.parseInt(value);if(number>max)throw invalid();return number;
    }
    private static OrderReviewException invalid(){return new OrderReviewException(400,"INVALID_RECEIVED_REVIEW_QUERY","收到评价的筛选参数不正确");}
    private static OrderReviewException unavailable(){return new OrderReviewException(503,"RECEIVED_REVIEW_UNAVAILABLE","收到评价的记录异常，请稍后重试");}
}
