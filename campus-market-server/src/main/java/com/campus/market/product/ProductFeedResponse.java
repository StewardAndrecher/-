package com.campus.market.product;

import java.util.List;

public record ProductFeedResponse(List<ProductResponse> records, boolean hasMore, String nextCursor, int size) {
    public ProductFeedResponse {records = List.copyOf(records);}
}
