package com.campus.market.favorite;

import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class FavoriteBatchModels {
    private FavoriteBatchModels() {}
    @JsonDeserialize(using=FavoriteBatchRequestDeserializer.class)
    public record Request(List<Long> productIds) {}
    public record Receipt(long userId,List<Long> productIds,int removedCount) {
        public Receipt {productIds=List.copyOf(productIds);}
    }
}
