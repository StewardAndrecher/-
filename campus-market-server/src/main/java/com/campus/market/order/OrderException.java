package com.campus.market.order;
public class OrderException extends RuntimeException {
 private final int status; private final String code;
 public OrderException(int status,String code,String message) {super(message);this.status=status;this.code=code;}
 public int status(){return status;} public String code(){return code;}
 public static OrderException invalid(String message){return new OrderException(400,"INVALID_ORDER",message);}
 public static OrderException conflict(String message){return new OrderException(409,"ORDER_CONFLICT",message);}
}
