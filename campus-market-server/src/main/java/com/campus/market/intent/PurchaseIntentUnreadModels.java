package com.campus.market.intent;
import java.util.List;
public final class PurchaseIntentUnreadModels {
 private PurchaseIntentUnreadModels() {}
 public record Summary(String role,long totalUnreadMessages,long unreadIntentCount) {}
 public record Item(long intentId,long unreadCount) {}
 public record Batch(String role,List<Item> items,long pageUnreadMessages,long pageUnreadIntents) {}
}
