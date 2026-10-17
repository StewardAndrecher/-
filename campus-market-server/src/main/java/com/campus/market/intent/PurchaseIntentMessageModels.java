package com.campus.market.intent;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;
import static com.campus.market.intent.PurchaseIntentModels.Participant;
public final class PurchaseIntentMessageModels {
 private PurchaseIntentMessageModels() {}
 public record ReadRequest(@JsonDeserialize(using=MessageReadIdDeserializer.class) Long lastMessageId) {}
 public record ReadState(long intentId,long lastReadMessageId,long latestMessageId,long unreadCount) {}
 public record SendRequest(String content,String requestId) {}
 public record Message(long id,long intentId,Participant sender,String content,String createdAt,boolean withdrawn,String withdrawnAt) {}
 record Submission(Message message,String originalContent) {}
 record WithdrawalState(long id,long senderId,boolean withdrawn) {}
 public record Withdrawn(Message message,boolean replayed) {}
 public record Sent(Message message,boolean replayed) {}
 public record Page(List<Message> items,long intentId,String intentStatus,long total,int page,int size,int totalPages) {}
}
