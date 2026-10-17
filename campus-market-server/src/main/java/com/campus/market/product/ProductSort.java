package com.campus.market.product;

import java.util.Arrays;
import java.util.List;

public enum ProductSort {
    NEWEST("最新发布", "p.created_at DESC, p.id DESC"),
    PRICE_ASC("价格从低到高", "p.price_in_cents ASC, p.id DESC"),
    PRICE_DESC("价格从高到低", "p.price_in_cents DESC, p.id DESC"),
    MOST_FAVORITED("收藏最多", "favorite_count DESC, p.created_at DESC, p.id DESC");

    private final String displayName;
    private final String orderBy;
    ProductSort(String displayName, String orderBy) {
        this.displayName = displayName;
        this.orderBy = orderBy;
    }
    public String orderBy() { return orderBy; }
    public record Option(String code, String name) {}
    public static List<Option> catalog() {
        return Arrays.stream(values())
                .map(item -> new Option(item.name(), item.displayName)).toList();
    }
    public static ProductSort parse(String code) {
        if (code == null || code.isEmpty()) return NEWEST;
        try { return valueOf(code); }
        catch (IllegalArgumentException e) {
            throw new InvalidProductException("排序方式无效，请从排序列表中选择");
        }
    }
}
