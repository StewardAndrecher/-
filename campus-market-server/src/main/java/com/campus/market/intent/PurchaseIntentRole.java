package com.campus.market.intent;

public enum PurchaseIntentRole {
 BUYER("i.buyer_id"),SELLER("i.seller_id");
 private final String column;
 PurchaseIntentRole(String column) { this.column=column; }
 String column() { return column; }
 public static PurchaseIntentRole parse(String value) {
  if(value==null||value.isEmpty())return BUYER;
  try { return valueOf(value); }
  catch(IllegalArgumentException ex) { throw PurchaseIntentException.invalid("查看身份只能是 BUYER 或 SELLER"); }
 }
}
