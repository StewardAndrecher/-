package com.campus.market.order;

public class OrderReviewException extends RuntimeException {
    private final int status;
    private final String code;
    public OrderReviewException(int status, String code, String message) {
        super(message);this.status=status;this.code=code;
    }
    public int status(){return status;}
    public String code(){return code;}
}
