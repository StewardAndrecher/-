package com.campus.market.user;

import com.campus.market.auth.LoginRateLimiter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class LoginService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;
    private final LoginRateLimiter limiter;

    public LoginService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            LoginRateLimiter limiter
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.limiter = limiter;
        // 用户不存在时，也进行一次密码校验。
        this.dummyPasswordHash = passwordEncoder.encode("dummy-login-password");
    }

    public UserResponse login(LoginUserRequest request, String remoteAddress) {
        if (request == null) {
            throw new InvalidUserException("登录信息不能为空");
        }
        String username = request.username() == null
                ? "" : request.username().strip();
        String password = request.password();

        if (!username.matches("[a-z0-9_]{4,32}")) {
            throw new InvalidUserException("请填写符合格式的用户名");
        }
        if (password == null || password.isEmpty()) {
            throw new InvalidUserException("请填写密码");
        }
        if (password.codePointCount(0, password.length()) > 64 ||
                password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidUserException("密码长度超过允许的上限");
        }

        var attempt = limiter.begin(username, remoteAddress);
        java.util.Optional<UserCredentials> result;
        boolean matches;
        try {
            result = userRepository.findCredentialsByUsername(username);
            String hash = result.map(UserCredentials::passwordHash).orElse(dummyPasswordHash);
            matches = passwordEncoder.matches(password, hash);
        } catch (RuntimeException infrastructureFailure) {
            // 数据库或密码组件故障不算密码错误；IP总尝试预算仍保留。
            limiter.aborted(attempt);
            throw infrastructureFailure;
        }
        if (result.isEmpty() || !matches) {
            limiter.failed(attempt);
            throw new InvalidCredentialsException();
        }
        limiter.succeeded(attempt);
        UserCredentials user = result.get();
        return new UserResponse(user.id(), user.username(), user.nickname());
    }
}
