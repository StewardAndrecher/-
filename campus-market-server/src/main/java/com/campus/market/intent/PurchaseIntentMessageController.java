package com.campus.market.intent;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.intent.PurchaseIntentMessageModels.*;
@RestController
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class PurchaseIntentMessageController {
 private final PurchaseIntentMessageService service;
 public PurchaseIntentMessageController(PurchaseIntentMessageService service) {this.service=service;}
 @GetMapping("/api/purchase-intents/{intentId}/messages")
 public ResponseEntity<Page> page(@PathVariable long intentId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(intentId,page,size,auth));
 }
 @PostMapping("/api/purchase-intents/{intentId}/messages")
 public ResponseEntity<Sent> send(@PathVariable long intentId,@RequestBody SendRequest request,@RequestHeader(value="Authorization",required=false) String auth) {
  var result=service.send(intentId,request,auth);return ResponseEntity.status(result.replayed()?200:201).cacheControl(CacheControl.noStore()).body(result);
 }
 @PostMapping("/api/purchase-intents/{intentId}/messages/{messageId}/withdraw")
 public ResponseEntity<Withdrawn> withdraw(@PathVariable long intentId,@PathVariable long messageId,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.withdraw(intentId,messageId,auth));
 }

 @GetMapping("/api/purchase-intents/{intentId}/messages/read-state")
 public ResponseEntity<ReadState> readState(@PathVariable long intentId,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.readState(intentId,auth));
 }
 @PostMapping("/api/purchase-intents/{intentId}/messages/read")
 public ResponseEntity<ReadState> markRead(@PathVariable long intentId,@RequestBody ReadRequest request,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.markRead(intentId,request,auth));
 }

}
