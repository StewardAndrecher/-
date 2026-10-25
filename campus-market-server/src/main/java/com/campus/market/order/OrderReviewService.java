package com.campus.market.order;

import com.campus.market.auth.SessionService;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.campus.market.order.OrderReviewModels.*;

@Service
public class OrderReviewService {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final com.campus.market.notification.NotificationEvents notifications;
    private record Owned(long id,long buyer,long seller,String status,Timestamp completedAt) {}
    public OrderReviewService(JdbcTemplate jdbc,SessionService sessions,com.campus.market.notification.NotificationEvents notifications){this.jdbc=jdbc;this.sessions=sessions;this.notifications=notifications;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Summary summary(String rawId,String authorization) {
        long user=sessions.currentUser(authorization).id();
        return snapshot(owned(id(rawId),user,false),user);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Receipt create(String rawId,Create request,String authorization) {
        long user=sessions.currentUser(authorization).id();
        long id=id(rawId);
        if(request==null||request.rating()==null||request.rating()<1||request.rating()>5)throw invalid("请选择1至5分");
        String content=normalize(request.content());
        // Existing fulfillment locks product then order. Reviews only lock order and never acquire product.
        Owned order=owned(id,user,true);
        sessions.currentUser(authorization);
        if(!order.status().equals("COMPLETED"))throw new OrderReviewException(409,"REVIEW_NOT_COMPLETED","只有已完成订单可以评价");
        Summary before=snapshot(order,user);
        var previous=before.reviews().stream().filter(Review::mine).findFirst();
        if(previous.isPresent()) {
            var old=previous.get();
            if(old.rating()!=request.rating()||!old.content().equals(content))throw new OrderReviewException(409,"REVIEW_ALREADY_SUBMITTED","你已评价，本次内容不同；评价暂不支持修改");
            return new Receipt(before,true);
        }
        jdbc.update("INSERT INTO order_reviews(order_id,reviewer_id,rating,content) VALUES(?,?,?,?)",id,user,request.rating(),content);
        notifications.orderReview(order.id(),user,order.buyer(),order.seller());
        return new Receipt(snapshot(order,user),false);
    }
    private Owned owned(long id,long user,boolean lock) {
        var rows=jdbc.query("SELECT id,buyer_id,seller_id,status,completed_at FROM market_orders WHERE id=?"+(lock?" FOR UPDATE":""),
                (r,n)->new Owned(r.getLong("id"),r.getLong("buyer_id"),r.getLong("seller_id"),r.getString("status"),r.getTimestamp("completed_at")),id);
        var order=rows.stream().findFirst().orElseThrow(OrderReviewService::missing);
        if(order.buyer()!=user&&order.seller()!=user)throw missing();
        return order;
    }
    private Summary snapshot(Owned order,long user) {
        String role=order.buyer()==user?"BUYER":"SELLER";
        var values=jdbc.query("SELECT reviewer_id,rating,content,created_at FROM order_reviews WHERE order_id=? ORDER BY reviewer_id LIMIT 3",(r,n)->{
            long reviewer=r.getLong("reviewer_id");int rating=r.getInt("rating");String content=r.getString("content");Timestamp at=r.getTimestamp("created_at");
            try{normalize(content);}catch(OrderReviewException invalid){throw unavailable();}
            if((reviewer!=order.buyer()&&reviewer!=order.seller())||rating<1||rating>5||at==null||order.completedAt()==null||at.before(order.completedAt())||!normalize(content).equals(content))throw unavailable();
            return new Review(reviewer==order.buyer()?"BUYER":"SELLER",rating,content,at.toLocalDateTime().toString(),reviewer==user);
        },order.id());
        if(values.size()>2||(!order.status().equals("COMPLETED")&&!values.isEmpty()))throw unavailable();
        values.sort(Comparator.comparing(Review::authorRole));
        return new Summary(order.id(),user,order.status(),role,order.status().equals("COMPLETED")&&values.stream().noneMatch(Review::mine),List.copyOf(values));
    }
    static String normalize(String raw) {
        String source=raw==null?"":raw.replace("\r\n","\n").replace('\r','\n');
        int start=0,end=source.length();
        while(start<end&&edgeSpace(source.charAt(start)))start++;
        while(end>start&&edgeSpace(source.charAt(end-1)))end--;
        String value=source.substring(start,end);
        if(value.codePointCount(0,value.length())>500||value.codePoints().anyMatch(c->(Character.isISOControl(c)&&c!='\n'&&c!='\t')||(c>=0xd800&&c<=0xdfff)))throw invalid("评价最多500个字符，不能包含无效或控制字符");
        return value;
    }
    private static boolean edgeSpace(char c){return Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0xfeff;}
    private static long id(String raw) {
        if(raw==null||!raw.matches("[1-9][0-9]{0,15}"))throw invalid("订单编号格式不正确");
        try{long id=Long.parseLong(raw);if(id>9007199254740991L)throw invalid("订单编号超出范围");return id;}catch(NumberFormatException invalid){throw invalid("订单编号超出范围");}
    }
    private static OrderReviewException invalid(String message){return new OrderReviewException(400,"INVALID_ORDER_REVIEW",message);}
    private static OrderReviewException missing(){return new OrderReviewException(404,"ORDER_REVIEW_NOT_FOUND","订单不存在或当前账号无权查看");}
    private static OrderReviewException unavailable(){return new OrderReviewException(503,"ORDER_REVIEW_UNAVAILABLE","评价记录异常，请稍后重试");}
}
