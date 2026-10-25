package com.campus.market.auth;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/sessions")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class SessionManagementController {
    private final SessionManagementService management;
    public SessionManagementController(SessionManagementService management){this.management=management;}
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<SessionManagementService.Summary> mySessionSummary(
            @RequestHeader(value="Authorization",required=false)String auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(management.summary(auth));
    }
    @PostMapping(value="/revoke-others",produces="application/json;charset=UTF-8")
    public ResponseEntity<SessionManagementService.Revocation> revokeOtherSessions(
            @RequestHeader(value="Authorization",required=false)String auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(management.revokeOthers(auth));
    }
}
