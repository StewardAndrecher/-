package com.campus.market.product;

import java.util.List;

public record ProductPageResponse(
        List<ProductResponse> records,
        long total,
        int page,
        int size
) {
}