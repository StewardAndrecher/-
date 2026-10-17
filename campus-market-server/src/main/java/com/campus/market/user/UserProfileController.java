package com.campus.market.user;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
@CrossOrigin(originPatterns={"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class UserProfileController {
    private final UserProfileService profiles;
    public UserProfileController(UserProfileService profiles) { this.profiles = profiles; }

    @PutMapping(value="/nickname", consumes="application/json", produces="application/json;charset=UTF-8")
    public ResponseEntity<UserResponse> updateNickname(
            @RequestBody UpdateNicknameRequest request,
            @RequestHeader(name="Authorization", required=false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(profiles.updateNickname(request, authorization));
    }
}
