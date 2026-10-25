package com.campus.market.product;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ProductDetailCache {
    private static final Logger log = LoggerFactory.getLogger(ProductDetailCache.class);
    private static final long RETRY_NANOS = Duration.ofSeconds(5).toNanos();
    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();
    private final boolean enabled;
    private final String prefix;
    private final long ttlSeconds;
    private final AtomicLong retryAfter = new AtomicLong(System.nanoTime());

    public ProductDetailCache(StringRedisTemplate redis,
            @Value("${market.product-cache.enabled:true}") boolean enabled,
            @Value("${market.product-cache.prefix:campus:product-detail:v2:}") String prefix,
            @Value("${market.product-cache.ttl-seconds:60}") long ttlSeconds) {
        if (!prefix.matches("[a-zA-Z0-9:_-]{1,80}") || ttlSeconds < 1 || ttlSeconds > 3600) {
            throw new IllegalArgumentException("商品缓存前缀或过期秒数配置无效");
        }
        this.redis = redis;
        this.enabled = enabled;
        this.prefix = prefix;
        this.ttlSeconds = ttlSeconds;
    }

    public boolean canRead() {
        // 写事务可能包含尚未提交的商品，直接从数据库读取，避免污染共享缓存。
        return available() && !(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }

    private boolean available() {
        return enabled && System.nanoTime() - retryAfter.get() >= 0;
    }

    private String key(long id, long version) { return prefix + id + ":" + version; }

    public Optional<CachedProductDetail> read(long id, long version) {
        if (!canRead()) return Optional.empty();
        String value;
        try { value = redis.opsForValue().get(key(id, version)); }
        catch (RuntimeException failure) { unavailable(failure); return Optional.empty(); }
        if (value == null) return Optional.empty();
        try {
            var detail = json.readValue(value, CachedProductDetail.class);
            if (detail == null || detail.id() != id || detail.version() != version) return Optional.empty();
            return Optional.of(detail);
        } catch (RuntimeException malformed) {
            // 损坏的缓存视为未命中，接下来用数据库结果覆盖，不能变成HTTP 500。
            log.debug("商品缓存内容无效，使用数据库重新加载");
            return Optional.empty();
        }
    }

    public void putPublic(ProductResponse product) {
        if (!canRead() || product.status() != ProductStatus.ON_SALE) return;
        // 只读事务也等提交后再写入；回滚不会向Redis泄露中间结果。
        afterCommit(() -> {
            if (!available()) return;
            try {
                var detail = CachedProductDetail.from(product);
                long jitter = ThreadLocalRandom.current().nextLong(Math.min(15, ttlSeconds / 4) + 1);
                redis.opsForValue().set(key(product.id(), product.version()),
                        json.writeValueAsString(detail), Duration.ofSeconds(ttlSeconds + jitter));
            } catch (RuntimeException failure) { unavailable(failure); }
        });
    }

    public void evictAfterCommit(long id, long oldVersion) {
        if (!enabled) return;
        afterCommit(() -> {
            if (!available()) return;
            try { redis.delete(key(id, oldVersion)); }
            catch (RuntimeException failure) { unavailable(failure); }
        });
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private void unavailable(RuntimeException failure) {
        long now = System.nanoTime();
        long previous = retryAfter.get();
        if (now - previous >= 0 && retryAfter.compareAndSet(previous, now + RETRY_NANOS)) {
            log.warn("Redis商品缓存暂不可用，5秒内直接读取数据库（{}）", failure.getClass().getSimpleName());
        }
    }
}
