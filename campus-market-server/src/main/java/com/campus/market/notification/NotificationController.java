package com.campus.market.notification;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.notification.NotificationModels.*;
@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class NotificationController {
 private final NotificationService notifications;
 public NotificationController(NotificationService notifications){this.notifications=notifications;}
 @GetMapping(produces="application/json;charset=UTF-8")
 public ResponseEntity<Page> notifications(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(defaultValue="ALL") String status,@RequestParam(defaultValue="ALL") String category,@RequestHeader(name="Authorization",required=false)String auth){return ok(notifications.page(page,size,status,category,auth));}
 @GetMapping(value="/unread-count",produces="application/json;charset=UTF-8")
 public ResponseEntity<Unread> unreadNotifications(@RequestHeader(name="Authorization",required=false)String auth){return ok(notifications.unread(auth));}
 @PostMapping(value="/{id}/read",produces="application/json;charset=UTF-8")
 public ResponseEntity<Notice> readNotification(@PathVariable long id,@RequestHeader(name="Authorization",required=false)String auth){return ok(notifications.read(id,auth));}
 @PostMapping(value="/read-batch",produces="application/json;charset=UTF-8")
 public ResponseEntity<ReadBatchResult> readNotificationBatch(@RequestBody ReadBatch request,@RequestHeader(name="Authorization",required=false)String auth){return ok(notifications.readBatch(request,auth));}
 private <T> ResponseEntity<T> ok(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
