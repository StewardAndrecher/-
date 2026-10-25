package com.campus.market.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me/password")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"}, exposedHeaders="Retry-After")
public class PasswordChangeController {
    private final PasswordChangeService passwords;
    public PasswordChangeController(PasswordChangeService passwords) { this.passwords=passwords; }
    @PostMapping(consumes="application/json", produces="application/json;charset=UTF-8")
    public ResponseEntity<PasswordChangeService.Result> changeMyPassword(
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestBody PasswordChangeRequest request, HttpServletRequest servletRequest) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(passwords.change(authorization,request,servletRequest.getRemoteAddr()));
    }
}
