package com.campus.market.admin;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOrderOverviewService {
    public record Overview(long total, long pendingPayment, long paid, long delivered,
            long completed, long cancelled, long closedTimeout, long completedAmountInCents,
            long overduePendingPayment, long oldestPendingPaymentSeconds, String sampledAt) {}
    private final JdbcTemplate jdbc;
    public AdminOrderOverviewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static long safeInteger(BigDecimal value) {
        long result = value.longValueExact();
        if (result < 0 || result > 9007199254740991L) {
            throw new IllegalStateException("交易概览数字超出安全显示范围");
        }
        return result;
    }

    @Transactional(readOnly=true, timeout=5)
    public Overview overview() {
        // One database statement samples counts, amounts and age consistently.
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(status='PENDING_PAYMENT'),0) AS pending_payment,
                       COALESCE(SUM(status='PAID'),0) AS paid,
                       COALESCE(SUM(status='DELIVERED'),0) AS delivered,
                       COALESCE(SUM(status='COMPLETED'),0) AS completed,
                       COALESCE(SUM(status='CANCELLED'),0) AS cancelled,
                       COALESCE(SUM(status='CLOSED_TIMEOUT'),0) AS closed_timeout,
                       COALESCE(SUM(CASE WHEN status='COMPLETED' THEN price_in_cents_snapshot ELSE 0 END),0) AS completed_amount,
                       COALESCE(SUM(status='PENDING_PAYMENT' AND payment_due_at<=CURRENT_TIMESTAMP(6)),0) AS overdue_pending,
                       GREATEST(0,COALESCE(TIMESTAMPDIFF(SECOND,
                           MIN(CASE WHEN status='PENDING_PAYMENT' THEN created_at END),CURRENT_TIMESTAMP(6)),0)) AS oldest_pending_seconds,
                       CURRENT_TIMESTAMP(6) AS sampled_at
                FROM market_orders
                """);
            statement.setQueryTimeout(5);
            return statement;
        }, rs -> {
            if (!rs.next()) throw new IllegalStateException("交易概览采样失败");
            return new Overview(safeInteger(rs.getBigDecimal("total")),
                    safeInteger(rs.getBigDecimal("pending_payment")), safeInteger(rs.getBigDecimal("paid")),
                    safeInteger(rs.getBigDecimal("delivered")), safeInteger(rs.getBigDecimal("completed")),
                    safeInteger(rs.getBigDecimal("cancelled")), safeInteger(rs.getBigDecimal("closed_timeout")),
                    safeInteger(rs.getBigDecimal("completed_amount")), safeInteger(rs.getBigDecimal("overdue_pending")),
                    safeInteger(rs.getBigDecimal("oldest_pending_seconds")),
                    rs.getTimestamp("sampled_at").toLocalDateTime().toString());
        });
    }
}
