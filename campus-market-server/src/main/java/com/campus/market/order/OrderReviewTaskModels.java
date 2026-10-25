package com.campus.market.order;

import java.util.List;

public final class OrderReviewTaskModels {
    private OrderReviewTaskModels() {}
    public record Summary(long total,long pending,long reviewed) {}
    public record Entry(long orderId,String productTitleSnapshot,long priceInCentsSnapshot,
                        String completedAt,String reviewedAt) {}
    public record Page(long userId,String role,String status,int page,int size,long total,long totalPages,
                       Summary summary,List<Entry> items) {}
}
