package com.campus.market.product;

import tools.jackson.databind.annotation.JsonDeserialize;

public record UpdateProductRequest(String title, String description, Long priceInCents,
                                   String coverImageId,
                                   @JsonDeserialize(using=ProductVersionDeserializer.class) Long version,
                                   String categoryCode, ProductDetails details) {
    public UpdateProductRequest(String title,String description,Long priceInCents,String coverImageId,Long version,String categoryCode) { this(title,description,priceInCents,coverImageId,version,categoryCode,null); }
    public UpdateProductRequest(String title, String description, Long priceInCents,
                                String coverImageId, Long version) {
        this(title, description, priceInCents, coverImageId, version, null);
    }
}
