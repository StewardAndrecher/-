package com.campus.market.product;

public record CreateProductRequest(String title, String description, Long priceInCents,
                                   String coverImageId, String categoryCode) {
    public CreateProductRequest(String title, String description, Long priceInCents) {
        this(title, description, priceInCents, null, null);
    }
    public CreateProductRequest(String title, String description, Long priceInCents, String coverImageId) {
        this(title, description, priceInCents, coverImageId, null);
    }
}
