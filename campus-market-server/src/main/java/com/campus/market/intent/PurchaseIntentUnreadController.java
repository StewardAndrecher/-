package com.campus.market.intent;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.intent.PurchaseIntentUnreadModels.*;
@RestController
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class PurchaseIntentUnreadController {
 private final PurchaseIntentUnreadService service;
 public PurchaseIntentUnreadController(PurchaseIntentUnreadService service) {this.service=service;}
 @GetMapping("/api/purchase-intents/message-unread")
 public ResponseEntity<Batch> batch(@RequestParam(defaultValue="BUYER") String role,@RequestParam(defaultValue="") String intentIds,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.batch(role,intentIds,auth));
 }
 @GetMapping("/api/purchase-intents/message-unread-summary")
 public ResponseEntity<Summary> summary(@RequestParam(defaultValue="BUYER") String role,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary(role,auth));
 }

}
