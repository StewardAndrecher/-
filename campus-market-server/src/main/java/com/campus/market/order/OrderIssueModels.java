package com.campus.market.order;

public final class OrderIssueModels {
    private OrderIssueModels() {}
    public record Create(@tools.jackson.databind.annotation.JsonDeserialize(using=IssueTextDeserializer.class) String reason,@tools.jackson.databind.annotation.JsonDeserialize(using=IssueTextDeserializer.class) String description) {}
    public record Resolution(String decision,String explanation,String createdAt) {}
    public record Issue(String reason,String description,String createdAt,Resolution resolution) {}
    public record Summary(long orderId,long userId,String orderStatus,String viewerRole,boolean canSubmit,Issue issue) {}
    public record Receipt(Summary summary,boolean replayed) {}
}
