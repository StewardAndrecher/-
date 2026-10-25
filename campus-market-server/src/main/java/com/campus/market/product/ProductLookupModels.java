package com.campus.market.product;

import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class ProductLookupModels {
    private ProductLookupModels() {}
    @JsonDeserialize(using=ProductLookupRequestDeserializer.class)
    public record Request(List<Long> productIds) {}
    public record Entry(long productId, ProductResponse product) {}
    public record Response(List<Entry> records) {
        public Response { records = List.copyOf(records); }
    }
}
