package com.campus.market.intent;

import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.campus.market.product.ProductVersionDeserializer;

public final class PurchaseIntentModels {
 private PurchaseIntentModels() {}
 public record CreateRequest(@JsonDeserialize(using=ProductVersionDeserializer.class) Long productVersion) {}
 public record Participant(long id,String nickname) {}
 public record Intent(long id,long productId,String productTitleSnapshot,long priceInCentsSnapshot,
         long productVersionSnapshot,Participant buyer,Participant seller,String status,String createdAt,String cancelledAt,String acceptedAt) {}
 public record Created(Intent intent,boolean replayed) {}
 public record Cancelled(Intent intent,boolean replayed) {}
 public record Accepted(Intent intent,boolean replayed) {}
 record OwnedState(long id,String status) {}
 public record Summary(long total,long pending,long accepted,long cancelled) {
  long count(PurchaseIntentStatusFilter status) {
   return switch(status) {
    case ALL -> total;
    case PENDING -> pending;
    case ACCEPTED -> accepted;
    case CANCELLED -> cancelled;
   };
  }
 }
 public record ReceivedMessageTime(long intentId,String latestReceivedAt) {}
 public record Page(List<Intent> items,long total,int page,int size,int totalPages,String role,String status,Summary summary,boolean unreadOnly,String sort,List<ReceivedMessageTime> receivedMessageTimes) {}
 record ProductSnapshot(long id,String title,long priceInCents,long version,Long sellerId,String status) {}
}
