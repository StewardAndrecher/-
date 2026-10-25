package com.campus.market.order;
import java.util.List;
public final class OrderExportModels {
 private OrderExportModels(){}
 public record Row(long id,String productTitleSnapshot,long priceInCentsSnapshot,String status,String createdAt){}
 public record Snapshot(String role,String status,Long orderId,int total,int limit,List<Row> records){}
}
