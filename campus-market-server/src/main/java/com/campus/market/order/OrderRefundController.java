package com.campus.market.order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/orders")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class OrderRefundController {
 private final OrderRefundService service;public OrderRefundController(OrderRefundService service){this.service=service;}
 @GetMapping("/{id}/refunds") public ResponseEntity<OrderRefundService.Summary> orderRefunds(@PathVariable long id,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary(id,auth));}
 @GetMapping("/refunds") public ResponseEntity<OrderRefundService.Page> ownRefunds(@RequestParam(defaultValue="BUYER")String role,@RequestParam(defaultValue="ALL")String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(role,status,page,size,auth));}
 @PostMapping(value="/{id}/refunds",consumes="application/json") public ResponseEntity<OrderRefundService.Receipt> requestRefund(@PathVariable long id,@RequestBody OrderRefundService.Create request,@RequestHeader(value="Authorization",required=false)String auth){var result=service.create(id,request,auth);return ResponseEntity.status(result.replayed()?200:201).cacheControl(CacheControl.noStore()).body(result);}
 @PutMapping(value="/{id}/refunds/{refundId}/decision",consumes="application/json") public ResponseEntity<OrderRefundService.Receipt> decideRefund(@PathVariable long id,@PathVariable long refundId,@RequestBody OrderRefundService.Decision request,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.decide(id,refundId,request,auth));}
 @PutMapping(value="/{id}/refunds/{refundId}/withdraw",consumes="application/json") public ResponseEntity<OrderRefundService.Receipt> withdrawRefund(@PathVariable long id,@PathVariable long refundId,@RequestBody OrderRefundService.Withdraw request,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.withdraw(id,refundId,request,auth));}
}
