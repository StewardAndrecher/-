package com.campus.market.intent;

public enum PurchaseIntentStatusFilter {
 ALL(""),PENDING("PENDING"),ACCEPTED("ACCEPTED"),CANCELLED("CANCELLED");
 private final String value;
 PurchaseIntentStatusFilter(String value) { this.value=value; }
 public String value() { return value; }
 String clause() { return this==ALL?"":" AND i.status=?"; }
 public static PurchaseIntentStatusFilter parse(String value) {
  if(value==null||value.isEmpty())return ALL;
  for(var filter:values())if(filter!=ALL&&filter.value.equals(value))return filter;
  throw PurchaseIntentException.invalid("状态筛选只能为空、PENDING、ACCEPTED 或 CANCELLED");
 }
}
