package com.campus.market.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationOverviewService {
    public record Overview(boolean deliveryEnabled, long pending, long unconfirmed,
            long awaitingDelivery, long retrying, long leased, long delivered,
            long oldestPendingSeconds, long maxPendingAttempts, String sampledAt) {}
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    public NotificationOverviewService(JdbcTemplate jdbc,
            @Value("${market.notifications.mq.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }
    @Transactional(readOnly=true, timeout=5)
    public Overview overview() {
        // A single statement gives mutually consistent counts at the database sampling time.
        return jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(delivered_at IS NULL),0) AS pending,
                       COALESCE(SUM(delivered_at IS NULL AND published_at IS NULL),0) AS unconfirmed,
                       COALESCE(SUM(delivered_at IS NULL AND published_at IS NOT NULL),0) AS awaiting_delivery,
                       COALESCE(SUM(delivered_at IS NULL AND attempts>1),0) AS retrying,
                       COALESCE(SUM(delivered_at IS NULL AND lease_until>CURRENT_TIMESTAMP(6)),0) AS leased,
                       COALESCE(SUM(delivered_at IS NOT NULL),0) AS delivered,
                       GREATEST(0,COALESCE(TIMESTAMPDIFF(SECOND,
                           MIN(CASE WHEN delivered_at IS NULL THEN created_at END),CURRENT_TIMESTAMP(6)),0)) AS oldest_seconds,
                       COALESCE(MAX(CASE WHEN delivered_at IS NULL THEN attempts END),0) AS max_attempts,
                       CURRENT_TIMESTAMP(6) AS sampled_at
                FROM notification_outbox
                """);
            statement.setQueryTimeout(5);
            return statement;
        }, rs -> {
            if (!rs.next()) throw new IllegalStateException("通知概览采样失败");
            return new Overview(enabled, rs.getLong("pending"), rs.getLong("unconfirmed"),
                    rs.getLong("awaiting_delivery"), rs.getLong("retrying"), rs.getLong("leased"),
                    rs.getLong("delivered"), rs.getLong("oldest_seconds"), rs.getLong("max_attempts"),
                    rs.getTimestamp("sampled_at").toLocalDateTime().toString());
        });
    }
}
