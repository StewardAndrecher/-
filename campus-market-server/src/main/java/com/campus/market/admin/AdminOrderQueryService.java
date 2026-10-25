package com.campus.market.admin;

import java.sql.*;
import java.util.*;
import com.campus.market.order.OrderException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AdminOrderQueryService {
    public record Item(long id, long productId, String productTitleSnapshot,
            long priceInCentsSnapshot, String status, String createdAt, String paymentDueAt) {}
    public record Page(List<Item> records, long total, int page, int size, long totalPages,
            String status, Long orderId, String sampledAt) {}
    private static final Set<String> FILTERS = Set.of("ALL", "PENDING_PAYMENT", "PAID", "DELIVERED",
            "COMPLETED", "CANCELLED", "CLOSED_TIMEOUT", "OVERDUE_PENDING");
    private final JdbcTemplate jdbc;
    public AdminOrderQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private long safe(long value) {
        if (value < 0 || value > 9007199254740991L) {
            throw new OrderException(503, "ADMIN_ORDER_QUERY_UNAVAILABLE", "订单查询数字超出安全显示范围");
        }
        return value;
    }
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, timeout=5)
    public Page page(String status, String orderId, int page, int size) {
        if (status == null || !FILTERS.contains(status) || page < 1 || size < 1 || size > 20) {
            throw OrderException.invalid("订单筛选或分页条件不正确");
        }
        Long exactId = null;
        if (orderId != null && !orderId.isEmpty()) {
            if (!orderId.matches("[1-9][0-9]{0,15}")) throw OrderException.invalid("订单编号必须是规范正整数");
            try { exactId = Long.parseLong(orderId); } catch (NumberFormatException e) { throw OrderException.invalid("订单编号超出范围"); }
            if (exactId > 9007199254740991L) throw OrderException.invalid("订单编号超出安全范围");
        }
        // Reuse this cutoff for both SQL statements; a due time crossing mid-request cannot change the filter.
        Timestamp sampled = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", Timestamp.class);
        String where = " WHERE 1=1";
        var parameters = new ArrayList<Object>();
        if (status.equals("OVERDUE_PENDING")) {
            where += " AND status='PENDING_PAYMENT' AND payment_due_at<=?"; parameters.add(sampled);
        } else if (!status.equals("ALL")) {
            where += " AND status=?"; parameters.add(status);
        }
        if (exactId != null) { where += " AND id=?"; parameters.add(exactId); }
        long total = safe(jdbc.queryForObject("SELECT COUNT(*) FROM market_orders" + where, Long.class, parameters.toArray()));
        long pages = total == 0 ? 1 : (total - 1) / size + 1;
        int actualPage = (int)Math.min(page, pages);
        var rowParameters = new ArrayList<>(parameters);
        rowParameters.add(size); rowParameters.add(((long)actualPage - 1) * size);
        var records = jdbc.query("SELECT id,product_id,product_title_snapshot,price_in_cents_snapshot,status,created_at,payment_due_at FROM market_orders" + where +
                " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", (rs,n) -> new Item(safe(rs.getLong("id")),
                safe(rs.getLong("product_id")), rs.getString("product_title_snapshot"), safe(rs.getLong("price_in_cents_snapshot")),
                rs.getString("status"), rs.getTimestamp("created_at").toLocalDateTime().toString(),
                rs.getTimestamp("payment_due_at").toLocalDateTime().toString()), rowParameters.toArray());
        return new Page(records, total, actualPage, size, pages, status, exactId, sampled.toLocalDateTime().toString());
    }
}
