package com.campus.market.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
@CrossOrigin(originPatterns={"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class PermissionController {
    private final AdminAccessService access;
    public PermissionController(AdminAccessService access) {this.access = access;}
    @GetMapping(value="/permissions", produces="application/json;charset=UTF-8")
    public ResponseEntity<AdminAccessService.Permissions> myPermissions(
            @RequestHeader(name="Authorization", required=false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(access.permissions(authorization));
    }
}
