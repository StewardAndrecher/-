package com.campus.market.product;

public class ProductForbiddenException extends RuntimeException {
    public ProductForbiddenException(String message) {
        super(message);
    }
}
