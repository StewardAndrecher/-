package com.campus.market.product;

import java.util.Arrays;
import java.util.List;

public enum ProductCategory {
    UNCLASSIFIED("未分类"), BOOK("教材书籍"), DIGITAL("数码电子"),
    DAILY("生活用品"), SPORT("运动户外"), OTHER("其他");

    private final String displayName;
    ProductCategory(String displayName) { this.displayName = displayName; }
    public CategoryResponse response() { return new CategoryResponse(name(), displayName); }
    public static List<CategoryResponse> catalog() {
        return Arrays.stream(values()).map(ProductCategory::response).toList();
    }
    public static ProductCategory parse(String code) {
        try { return valueOf(code); }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidProductException("商品分类无效，请从分类列表中选择");
        }
    }
}
