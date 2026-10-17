package com.campus.market.product;

/** 我的发布只开放三种固定排序，SQL片段复用已有商品排序规则。 */
public enum ProductOwnerSort {
    NEWEST(ProductSort.NEWEST),
    PRICE_ASC(ProductSort.PRICE_ASC),
    PRICE_DESC(ProductSort.PRICE_DESC);

    private final ProductSort sort;
    ProductOwnerSort(ProductSort sort) { this.sort = sort; }
    public String orderBy() { return sort.orderBy(); }

    public static ProductOwnerSort parse(String code) {
        if (code == null || code.isEmpty()) return NEWEST;
        try { return valueOf(code); }
        catch (IllegalArgumentException error) {
            throw new InvalidProductException("我的发布排序方式无效，请从排序列表中选择");
        }
    }
}
