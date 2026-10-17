package com.campus.market.seller;

public class SellerNotFoundException extends RuntimeException {
    public SellerNotFoundException() { super("卖家不存在"); }
}
