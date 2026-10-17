package com.campus.market.intent;

public class PurchaseIntentException extends RuntimeException {
 private final int status;
 private final String code;
 public PurchaseIntentException(int status,String code,String message) { super(message);this.status=status;this.code=code; }
 public int status() { return status; }
 public String code() { return code; }
 static PurchaseIntentException invalid(String message) { return new PurchaseIntentException(400,"INVALID_PURCHASE_INTENT",message); }
}
