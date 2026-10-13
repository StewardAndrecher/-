package com.campus.market.user;

import com.campus.market.auth.LoginResponse;
import com.campus.market.auth.SessionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(originPatterns = {
        "http://localhost:[*]",
        "http://127.0.0.1:[*]"
})
public class LoginController {
    private final LoginService loginService;
    private final SessionService sessionService;

    public LoginController(LoginService loginService, SessionService sessionService) {
        this.loginService = loginService;
        this.sessionService = sessionService;
    }

    @PostMapping(
            value = "/login",
            consumes = "application/json",
            produces = "application/json;charset=UTF-8"
    )
    public ResponseEntity<LoginResponse> login(@RequestBody LoginUserRequest request) {
        UserResponse user = loginService.login(request);
        LoginResponse response = sessionService.issueToken(user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(response);
    }
}