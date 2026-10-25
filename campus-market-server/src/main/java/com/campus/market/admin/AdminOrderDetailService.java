package com.campus.market.admin;

import java.sql.*;
import java.util.*;
import com.campus.market.order.OrderException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AdminOrderDetailService {
    public record Snapshot(long id, long productId, String productTitleSnapshot, long priceInCentsSnapshot,
            String status, String createdAt, String paymentDueAt, String paidAt, String deliveredAt,
            String completedAt, String cancelledAt, String closedAt, String paymentKind) {}
    public record ProgressEvent(int sequence, String action, String fromStatus, String toStatus,
            String actorRole, String occurredAt, String recordedAt) {}
    public record Detail(Snapshot order, List<ProgressEvent> events) {}
    private record StoredOrder(Snapshot snapshot, long buyerId, long sellerId) {}
    private record StoredEvent(ProgressEvent event, Long actorId) {}
    private final JdbcTemplate jdbc;
    public AdminOrderDetailService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private OrderException unavailable() {
        return new OrderException(503,"ADMIN_ORDER_HISTORY_UNAVAILABLE","订单进度记录缺失或不一致，请联系维护人员核查");
    }
    private long safe(long value) { if(value<1||value>9007199254740991L)throw unavailable();return value; }
    private String time(ResultSet rs,String column)throws SQLException {
        var value=rs.getTimestamp(column);return value==null?null:value.toLocalDateTime().toString();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=5)
    public Detail detail(String rawId) {
        if(rawId==null||!rawId.matches("[1-9][0-9]{0,15}"))throw OrderException.invalid("订单编号必须是规范正整数");
        long id=Long.parseLong(rawId);
        if(id>9007199254740991L)throw OrderException.invalid("订单编号超出安全范围");
        var rows=jdbc.query("""
                SELECT id,product_id,product_title_snapshot,price_in_cents_snapshot,status,created_at,payment_due_at,
                       paid_at,delivered_at,completed_at,cancelled_at,closed_at,payment_kind,buyer_id,seller_id
                FROM market_orders WHERE id=?
                """,(rs,n)->new StoredOrder(new Snapshot(safe(rs.getLong("id")),safe(rs.getLong("product_id")),
                rs.getString("product_title_snapshot"),safe(rs.getLong("price_in_cents_snapshot")),rs.getString("status"),
                time(rs,"created_at"),time(rs,"payment_due_at"),time(rs,"paid_at"),time(rs,"delivered_at"),
                time(rs,"completed_at"),time(rs,"cancelled_at"),time(rs,"closed_at"),rs.getString("payment_kind")),
                rs.getLong("buyer_id"),rs.getLong("seller_id")),id);
        if(rows.isEmpty())throw new OrderException(404,"ORDER_NOT_FOUND","订单不存在");
        var order=rows.get(0);
        var events=jdbc.query("""
                SELECT event_no,action,from_status,to_status,actor_role,actor_id,occurred_at,recorded_at
                FROM order_events WHERE order_id=? ORDER BY event_no LIMIT 5
                """,(rs,n)->new StoredEvent(new ProgressEvent(rs.getInt("event_no"),rs.getString("action"),
                rs.getString("from_status"),rs.getString("to_status"),rs.getString("actor_role"),
                time(rs,"occurred_at"),time(rs,"recorded_at")),rs.getObject("actor_id",Long.class)),id);
        verify(order,events);
        return new Detail(order.snapshot(),events.stream().map(StoredEvent::event).toList());
    }
    private void verify(StoredOrder stored,List<StoredEvent> rows) {
        if(rows.isEmpty()||rows.size()>4)throw unavailable();
        var order=stored.snapshot();String previous=null;
        java.time.LocalDateTime occurred=null,recorded=null;
        var actions=new HashSet<String>();
        for(int i=0;i<rows.size();i++) {
            var raw=rows.get(i);var e=raw.event();
            var now=java.time.LocalDateTime.parse(e.occurredAt());var saved=java.time.LocalDateTime.parse(e.recordedAt());
            if(e.sequence()!=i+1||!actions.add(e.action())||saved.isBefore(now)||
                    (occurred!=null&&now.isBefore(occurred))||(recorded!=null&&saved.isBefore(recorded)))throw unavailable();
            if(e.action().equals("HISTORY_SNAPSHOT")) {
                if(i!=0||e.fromStatus()!=null||!Set.of("PENDING_PAYMENT","PAID","DELIVERED","COMPLETED","CANCELLED","CLOSED_TIMEOUT").contains(e.toStatus())||
                        !e.actorRole().equals("SYSTEM")||raw.actorId()!=null)throw unavailable();
            } else {
                String from,to,role,expectedTime;
                switch(e.action()) {
                    case "CREATED" -> {from=null;to="PENDING_PAYMENT";role="BUYER";expectedTime=order.createdAt();}
                    case "SIMULATED_PAYMENT" -> {from="PENDING_PAYMENT";to="PAID";role="BUYER";expectedTime=order.paidAt();}
                    case "DELIVERED" -> {from="PAID";to="DELIVERED";role="SELLER";expectedTime=order.deliveredAt();}
                    case "COMPLETED" -> {from="DELIVERED";to="COMPLETED";role="BUYER";expectedTime=order.completedAt();}
                    case "CANCELLED" -> {from="PENDING_PAYMENT";to="CANCELLED";role="BUYER";expectedTime=order.cancelledAt();}
                    case "TIMEOUT_CLOSED" -> {from="PENDING_PAYMENT";to="CLOSED_TIMEOUT";role="SYSTEM";expectedTime=order.closedAt();}
                    default -> throw unavailable();
                }
                if(!Objects.equals(e.fromStatus(),from)||!e.toStatus().equals(to)||!e.actorRole().equals(role)||
                        !Objects.equals(e.occurredAt(),expectedTime)||(i==0&&!e.action().equals("CREATED"))||
                        (i>0&&!Objects.equals(from,previous)))throw unavailable();
                Long expectedActor=switch(role){case "BUYER" -> stored.buyerId();case "SELLER" -> stored.sellerId();default -> null;};
                if(!Objects.equals(raw.actorId(),expectedActor))throw unavailable();
            }
            previous=e.toStatus();occurred=now;recorded=saved;
        }
        if(!Objects.equals(previous,order.status()))throw unavailable();
    }
}
