package com.campus.market.order.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;

/** 仅记录技术身份与有限阶段；不记录令牌、消息正文或异常原文。 */
public final class OrderMqLog {
    private static final Logger log = LoggerFactory.getLogger(OrderMqLog.class);
    private static final String EVENT = "eventId";
    private OrderMqLog() {}

    public enum OutboxResult { PUBLISHED, LEASE_LOST, RETRY_SCHEDULED, RETRY_LEASE_LOST, STATE_UPDATE_FAILED }
    public enum Reason { NONE, MALFORMED, SOURCE_MISMATCH, SOURCE_CHECK_FAILED, PROCESSING_FAILED }

    public static EventScope event(String eventId) { return new EventScope(eventId); }

    public static final class EventScope implements AutoCloseable {
        private final String previous = MDC.get(EVENT);
        private boolean closed;
        private EventScope(String eventId) { bind(eventId); }
        public void bind(String eventId) {
            if (closed) throw new IllegalStateException("Event scope already closed");
            if (validEvent(eventId)) MDC.put(EVENT, eventId);
            else MDC.remove(EVENT);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) MDC.remove(EVENT);
            else MDC.put(EVENT, previous);
        }
    }

    private static boolean validEvent(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static String name(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
    private static long millis(long nanos) { return Math.max(0, nanos) / 1_000_000; }

    /** 完成回调只在COMMITTED时写日志，不在事务中连接MQ，也不在回调里写数据库。 */
    public static void outboxInserted(String eventId, long orderId) {
        if (!validEvent(eventId) || orderId < 1 ||
                !TransactionSynchronizationManager.isActualTransactionActive() ||
                !TransactionSynchronizationManager.isSynchronizationActive()) return;
        String value = MDC.get("requestId");
        String origin = value != null && value.matches("[0-9a-f]{32}") ? value : "none";
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) return;
                // Spring对afterCompletion回调异常的处理不把已提交请求变成失败响应。
                try (var scope = event(eventId)) {
                    log.info("order_timeout stage=outbox_committed originRequestId={} orderId={}", origin, orderId);
                }
            }
        });
    }

    public static void published(OrderMqMetrics.Publish result, int attempt, long elapsedNanos) {
        if (result == OrderMqMetrics.Publish.CONFIRMED)
            log.info("order_timeout stage=publish result={} attempt={} durationMs={}", name(result), attempt, millis(elapsedNanos));
        else log.warn("order_timeout stage=publish result={} attempt={} durationMs={}", name(result), attempt, millis(elapsedNanos));
    }
    public static void outbox(OutboxResult result, int attempt) {
        if (result == OutboxResult.PUBLISHED)
            log.info("order_timeout stage=outbox_update result={} attempt={}", name(result), attempt);
        else log.warn("order_timeout stage=outbox_update result={} attempt={}", name(result), attempt);
    }
    public static void consumed(OrderMqMetrics.Consume result, Reason reason, long elapsedNanos) {
        if (result == OrderMqMetrics.Consume.CLOSED || result == OrderMqMetrics.Consume.NOOP)
            log.info("order_timeout stage=consume result={} reason={} durationMs={}", name(result), name(reason), millis(elapsedNanos));
        else log.warn("order_timeout stage=consume result={} reason={} durationMs={}", name(result), name(reason), millis(elapsedNanos));
    }
}
