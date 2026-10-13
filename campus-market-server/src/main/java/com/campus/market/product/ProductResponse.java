package com.campus.market.product;

public record ProductResponse(
        long id,
        String title,
        String description,
        long priceInCents,
        SellerResponse seller,
        ProductStatus status,
        String coverImageId,
        String coverImageUrl,
        long version
) {
}
