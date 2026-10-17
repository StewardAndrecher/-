package com.campus.market.intent;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.intent.PurchaseIntentModels.*;

@RestController
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class PurchaseIntentController {
 private final PurchaseIntentService service;
 public PurchaseIntentController(PurchaseIntentService service) { this.service=service; }
 @PostMapping("/api/products/{productId}/purchase-intent")
 public ResponseEntity<Created> create(@PathVariable long productId,@RequestBody CreateRequest request,@RequestHeader(value="Authorization",required=false) String auth) {
  var result=service.create(productId,request,auth);
  return ResponseEntity.status(result.replayed()?200:201).cacheControl(CacheControl.noStore()).body(result);
 }
 @PostMapping("/api/purchase-intents/{intentId}/cancel")
 public ResponseEntity<Cancelled> cancel(@PathVariable long intentId,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.cancel(intentId,auth));
 }
 @PostMapping("/api/purchase-intents/{intentId}/accept")
 public ResponseEntity<Accepted> accept(@PathVariable long intentId,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.accept(intentId,auth));
 }
 @GetMapping("/api/purchase-intents/{intentId}")
 public ResponseEntity<Intent> detail(@PathVariable long intentId,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(intentId,auth));
 }
 @GetMapping("/api/purchase-intents/mine")
 public ResponseEntity<Page> page(@RequestParam(defaultValue="BUYER") String role,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="5") int size,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="false") String unreadOnly,@RequestParam(defaultValue="NEWEST") String sort,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(role,page,size,status,unreadOnly,sort,auth));
 }
}
