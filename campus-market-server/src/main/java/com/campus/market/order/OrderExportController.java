package com.campus.market.order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class OrderExportController {
 private final OrderExportService service;
 public OrderExportController(OrderExportService service){this.service=service;}
 @GetMapping("/api/orders/export")
 public ResponseEntity<OrderExportModels.Snapshot> ownOrderExport(
  @RequestParam(defaultValue="BUYER")String role,@RequestParam(defaultValue="ALL")String status,
  @RequestParam(required=false)String orderId,
  @RequestHeader(name="Authorization",required=false)String auth){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.export(role,status,orderId==null?"":orderId,auth));
 }
}
