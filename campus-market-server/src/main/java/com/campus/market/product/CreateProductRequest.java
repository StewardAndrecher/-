package com.campus.market.product;

public record CreateProductRequest(
        String title,
        String description,
        Long priceInCents,
        String coverImageId
) {
    public CreateProductRequest(String title, String description, Long priceInCents) {
        this(title, description, priceInCents, null);
    }
}