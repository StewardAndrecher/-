package com.campus.market.admin;

import com.campus.market.notification.NotificationOverviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/notifications")
@CrossOrigin(originPatterns={"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class AdminNotificationController {
    private final NotificationOverviewService notifications;
    public AdminNotificationController(NotificationOverviewService notifications) {
        this.notifications = notifications;
    }
    @GetMapping(value="/overview", produces="application/json;charset=UTF-8")
    public ResponseEntity<NotificationOverviewService.Overview> notificationOverview() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.overview());
    }
}
