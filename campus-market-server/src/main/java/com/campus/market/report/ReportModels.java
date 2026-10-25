package com.campus.market.report;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.campus.market.product.ProductVersionDeserializer;
import com.campus.market.product.ProductModerationModels;
public final class ReportModels {
    private ReportModels(){}
    public record Create(String category,String reason,String requestId){}
    public record Resolve(String action,String reason,@JsonDeserialize(using=ProductVersionDeserializer.class) Long version,
        @JsonDeserialize(using=ProductVersionDeserializer.class) Long productVersion,String requestId){}
    public record Ticket(long id,long productId,String category,String reason,String status,long version,String resolution,
        String createdAt,String resolvedAt,Long moderationEventId,ProductModerationModels.Evidence snapshot){}
    public record Page(List<Ticket> records,long total,int page,int size){public Page{records=List.copyOf(records);}}
    public record Receipt(Ticket ticket,boolean replayed){}
    public record Actor(long id,String nickname){}
    public record AdminDetail(Ticket ticket,Actor reporter,ProductModerationModels.Item currentProduct){}
    record Stored(Ticket ticket,long reporterId,String requestId,Long resolverId,String resolutionRequestId,Long expectedProductVersion){}
}
