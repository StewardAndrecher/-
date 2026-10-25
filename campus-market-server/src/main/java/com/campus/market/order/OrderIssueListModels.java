package com.campus.market.order;

import java.util.List;

public final class OrderIssueListModels {
    private OrderIssueListModels() {}
    public record Summary(long total,long open,long resolved) {}
    public record Entry(long orderId,String productTitleSnapshot,String reason,String createdAt,
                        String resolutionDecision,String resolvedAt) {}
    public record Page(long userId,String role,String status,int page,int size,long total,long totalPages,
                       Summary summary,List<Entry> items) {}
}
