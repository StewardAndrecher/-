package com.campus.market.admin;

import com.campus.market.order.OrderIssueModels;
import com.campus.market.order.IssueTextDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.util.List;

public final class AdminOrderIssueModels {
    private AdminOrderIssueModels() {}
    public record Resolve(@JsonDeserialize(using=IssueTextDeserializer.class) String decision,
                          @JsonDeserialize(using=IssueTextDeserializer.class) String explanation,
                          @JsonDeserialize(using=IssueTextDeserializer.class) String requestId) {}
    public record Item(long orderId,long applicantId,String applicantRole,String reason,String createdAt,String status) {}
    public record Page(String status,String age,Long orderId,String sampledAt,int page,int size,long total,long totalPages,List<Item> items) {}
    public record Detail(long orderId,long applicantId,String applicantRole,String orderStatus,String productTitleSnapshot,
                         long priceInCentsSnapshot,String reason,String description,String createdAt,String status,
                         OrderIssueModels.Resolution resolution) {}
    public record Receipt(Detail detail,boolean replayed) {}
}
