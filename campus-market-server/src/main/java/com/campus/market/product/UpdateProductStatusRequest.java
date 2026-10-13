package com.campus.market.product;

import tools.jackson.databind.annotation.JsonDeserialize;

public record UpdateProductStatusRequest(
        ProductStatus status,
        @JsonDeserialize(using = ProductVersionDeserializer.class) Long version
) {
}
