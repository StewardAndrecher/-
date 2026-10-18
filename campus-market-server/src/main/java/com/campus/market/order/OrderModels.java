package com.campus.market.order;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.campus.market.product.ProductVersionDeserializer;
public final class OrderModels {
 private OrderModels(){}
 public record Create(@JsonDeserialize(using=ProductVersionDeserializer.class) Long productVersion,String requestId){}
 public record Participant(long id,String nickname){}
 public record Order(long id,long productId,String productTitleSnapshot,long priceInCentsSnapshot,
   long productVersionSnapshot,Participant buyer,Participant seller,String status,String createdAt,String cancelledAt,String paymentDueAt,String paidAt,String closedAt,String paymentKind,String deliveredAt,String completedAt){}
 public record Receipt(Order order,boolean replayed){}
 public record State(long productId,boolean available,Long myOrderId,String message){}
 public record Summary(long total,long pendingPayment,long paid,long delivered,long completed,
   long cancelled,long closedTimeout,long completedAmountInCents){}
 public record Page(List<Order> items,long total,int page,int size,int totalPages,String role,String status,Summary summary){}
 public record Event(int sequence,String action,String fromStatus,String toStatus,String actorRole,
   Long actorId,String actorNickname,String occurredAt,String recordedAt){}
 public record Timeline(Order order,List<Event> events){}
 record Stored(long id,long productId,long buyerId,long sellerId,long version,String status){}
}
