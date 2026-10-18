package com.campus.market.order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.order.OrderModels.*;
@RestController
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class OrderController {
 private final OrderService service;public OrderController(OrderService service){this.service=service;}
 @PostMapping("/api/products/{id}/orders") public ResponseEntity<Receipt> create(@PathVariable long id,@RequestBody Create request,@RequestHeader(value="Authorization",required=false)String auth){var result=service.create(id,request,auth);return ResponseEntity.status(result.replayed()?200:201).cacheControl(CacheControl.noStore()).body(result);}
 @PostMapping("/api/orders/{id}/cancel") public ResponseEntity<Receipt> cancel(@PathVariable long id,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.cancel(id,auth));}
 @PostMapping("/api/orders/{id}/simulate-payment")
 public ResponseEntity<Receipt> simulatePayment(@PathVariable long id,@RequestHeader(value="Authorization",required=false)String auth){
  var result=service.simulatePayment(id,auth);
  // 服务事务先提交超时关闭，再返回业务错误，避免抛异常把关闭回滚。
  if("CLOSED_TIMEOUT".equals(result.order().status()))throw OrderException.conflict("订单已超时关闭，请重新加载；没有发生付款");
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
 }
 @PostMapping("/api/orders/{id}/deliver")
 public ResponseEntity<Receipt> deliver(@PathVariable long id,@RequestHeader(value="Authorization",required=false)String auth){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.deliver(id,auth));
 }
 @PostMapping("/api/orders/{id}/complete")
 public ResponseEntity<Receipt> complete(@PathVariable long id,@RequestHeader(value="Authorization",required=false)String auth){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.complete(id,auth));
 }
 @GetMapping("/api/orders/{id}/timeline")
 public ResponseEntity<Timeline> timeline(@PathVariable long id,@RequestHeader(value="Authorization",required=false)String auth){
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.timeline(id,auth));
 }
 @GetMapping("/api/orders/{id}") public ResponseEntity<Order> detail(@PathVariable long id,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(id,auth));}
 @GetMapping("/api/orders") public ResponseEntity<Page> page(@RequestParam(defaultValue="BUYER")String role,@RequestParam(defaultValue="ALL")String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(role,status,page,size,auth));}
 @GetMapping("/api/products/{id}/order-state") public ResponseEntity<State> state(@PathVariable long id,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.state(id,auth));}
}
