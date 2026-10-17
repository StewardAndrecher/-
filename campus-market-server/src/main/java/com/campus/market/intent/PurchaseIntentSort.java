package com.campus.market.intent;

// Only these server-owned clauses may enter ORDER BY; user input never becomes SQL.
enum PurchaseIntentSort {
 NEWEST("i.created_at DESC,i.id DESC"),
 RECENT_MESSAGE("(SELECT MAX(m.created_at) FROM purchase_intent_messages m "
   +"WHERE m.intent_id=i.id AND m.sender_id<>? AND m.is_withdrawn=FALSE) DESC,"
   +"i.created_at DESC,i.id DESC");
 private final String orderBy;
 PurchaseIntentSort(String orderBy) { this.orderBy=orderBy; }
 String orderBy() { return orderBy; }
 static PurchaseIntentSort parse(String value) {
  if(value==null||value.isEmpty())return NEWEST;
  for(var sort:values())if(sort.name().equals(value))return sort;
  throw PurchaseIntentException.invalid("意向排序只能是NEWEST或RECENT_MESSAGE");
 }
}
