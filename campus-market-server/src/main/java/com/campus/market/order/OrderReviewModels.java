package com.campus.market.order;

import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class OrderReviewModels {
    private OrderReviewModels() {}
    public record Create(@JsonDeserialize(using=ReviewRatingDeserializer.class) Long rating, String content) {}
    public record Review(String authorRole, int rating, String content, String createdAt, boolean mine) {}
    public record Summary(long orderId, long userId, String orderStatus, String viewerRole,
            boolean canReview, List<Review> reviews) {}
    public record Receipt(Summary summary, boolean replayed) {}
}
