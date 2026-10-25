package com.campus.market.order;

import com.campus.market.auth.SessionService;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.campus.market.order.OrderTaskModels.*;

@Service
public class OrderTaskService {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    public OrderTaskService(JdbcTemplate jdbc,SessionService sessions){this.jdbc=jdbc;this.sessions=sessions;}

    @Transactional(readOnly=true,timeout=5)
    public Overview overview(String authorization) {
        long user=sessions.currentUser(authorization).id();
        // A single statement gives both roles the same database clock and statement snapshot.
        // UNION ALL scopes each branch by the existing buyer/seller index; a review joins only its author.
        return jdbc.query("""
            WITH scoped AS (
                SELECT id,status,payment_due_at,'BUYER' AS role FROM market_orders
                WHERE buyer_id=? AND status IN('PENDING_PAYMENT','DELIVERED','COMPLETED')
                UNION ALL
                SELECT id,status,payment_due_at,'SELLER' AS role FROM market_orders
                WHERE seller_id=? AND status IN('PAID','COMPLETED')
            )
            SELECT CURRENT_TIMESTAMP(6) AS sampled_at,
                COUNT(CASE WHEN o.role='BUYER' AND o.status='PENDING_PAYMENT' THEN 1 END) AS pending_payment,
                COUNT(CASE WHEN o.role='BUYER' AND o.status='PENDING_PAYMENT' AND o.payment_due_at>CURRENT_TIMESTAMP(6) THEN 1 END) AS payable,
                COUNT(CASE WHEN o.role='BUYER' AND o.status='PENDING_PAYMENT' AND o.payment_due_at<=CURRENT_TIMESTAMP(6) THEN 1 END) AS overdue,
                COUNT(CASE WHEN o.role='BUYER' AND o.status='DELIVERED' THEN 1 END) AS to_confirm,
                COUNT(CASE WHEN o.role='SELLER' AND o.status='PAID' THEN 1 END) AS to_deliver,
                COUNT(CASE WHEN o.role='BUYER' AND o.status='COMPLETED' AND r.order_id IS NULL THEN 1 END) AS buyer_review,
                COUNT(CASE WHEN o.role='SELLER' AND o.status='COMPLETED' AND r.order_id IS NULL THEN 1 END) AS seller_review
            FROM scoped o LEFT JOIN order_reviews r ON r.order_id=o.id AND r.reviewer_id=?
            """,rs->{
                if(!rs.next()||rs.getTimestamp("sampled_at")==null)throw unavailable();
                long pending=number(rs.getBigDecimal("pending_payment")),payable=number(rs.getBigDecimal("payable")),overdue=number(rs.getBigDecimal("overdue"));
                if(payable>pending||overdue!=pending-payable)throw unavailable();
                return new Overview(user,rs.getTimestamp("sampled_at").toLocalDateTime().toString(),
                    new Buyer(pending,payable,overdue,number(rs.getBigDecimal("to_confirm")),number(rs.getBigDecimal("buyer_review"))),
                    new Seller(number(rs.getBigDecimal("to_deliver")),number(rs.getBigDecimal("seller_review"))));
            },user,user,user);
    }
    static long number(BigDecimal value) {
        try {long n=value.longValueExact();if(n<0||n>9007199254740991L)throw unavailable();return n;}
        catch(ArithmeticException|NullPointerException invalid){throw unavailable();}
    }
    private static OrderException unavailable(){return new OrderException(503,"ORDER_TASKS_UNAVAILABLE","交易待办数据异常，请稍后重试");}
}
