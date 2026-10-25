package com.campus.market.admin;

import com.campus.market.user.UserResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(originPatterns={"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class AdminController {
    private final AdminOverviewService overview;
    public AdminController(AdminOverviewService overview) {this.overview = overview;}
    public record AdminIdentity(UserResponse user, String role) {}
    @GetMapping(value="/overview", produces="application/json;charset=UTF-8")
    public ResponseEntity<AdminOverviewService.Overview> adminOverview() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(overview.overview());
    }
    @GetMapping(value="/me", produces="application/json;charset=UTF-8")
    public ResponseEntity<AdminIdentity> adminMe(
            @RequestAttribute(AdminAccessInterceptor.ADMIN_USER) UserResponse user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AdminIdentity(user, "ADMIN"));
    }
}
