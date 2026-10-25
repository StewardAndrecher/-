package com.campus.market.user;

import org.springframework.http.HttpStatus;
import jakarta.servlet.http.HttpServletRequest;
import com.campus.market.auth.RegistrationRateLimiter;
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
}, exposedHeaders="Retry-After")
public class UserController {
    private final UserService userService;
    private final RegistrationRateLimiter protection;

    public UserController(UserService userService,RegistrationRateLimiter protection) {
        this.protection=protection;
        this.userService = userService;
    }

    @PostMapping(
            value = "/register",
            consumes = "application/json",
            produces = "application/json;charset=UTF-8"
    )
    public ResponseEntity<UserResponse> register(
            @RequestBody RegisterUserRequest request,HttpServletRequest servletRequest
    ) {
        protection.check(servletRequest.getRemoteAddr());
        UserResponse user = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(org.springframework.http.CacheControl.noStore()).body(user);
    }
}