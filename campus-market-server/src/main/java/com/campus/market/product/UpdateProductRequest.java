package com.campus.market.product;

import tools.jackson.databind.annotation.JsonDeserialize;

public record UpdateProductRequest(
        String title,
        String description,
        Long priceInCents,
        String coverImageId,
        @JsonDeserialize(using = ProductVersionDeserializer.class) Long version
) {
}
