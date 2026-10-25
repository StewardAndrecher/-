package com.campus.market.notification;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonDeserialize;
public final class NotificationModels {
 private NotificationModels(){}
 public record Notice(long id,String category,long resourceId,@Schema(allowableValues={"ORDER_DETAIL","ORDER_REVIEWS","OWN_ORDER_ISSUE","REPORT_DETAIL","PRODUCT_MODERATION"}) String target,String title,String body,String occurredAt,String createdAt,String readAt){}
 public record Page(List<Notice> records,long total,int page,int size){public Page{records=List.copyOf(records);}}
 public record ReadBatch(@JsonDeserialize(contentUsing=NotificationReadIdDeserializer.class) List<Long> ids){}
 public record ReadBatchResult(List<Notice> records){public ReadBatchResult{records=List.copyOf(records);}}
 public record Unread(long unreadCount){}
}
