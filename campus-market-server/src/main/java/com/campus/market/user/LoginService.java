package com.campus.market.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class LoginService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public LoginService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        // 用户不存在时，也进行一次密码校验。
        this.dummyPasswordHash = passwordEncoder.encode("dummy-login-password");
    }

    public UserResponse login(LoginUserRequest request) {
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

        var result = userRepository.findCredentialsByUsername(username);
        String hash = result.map(UserCredentials::passwordHash)
                .orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(password, hash);

        if (result.isEmpty() || !matches) {
            throw new InvalidCredentialsException();
        }
        UserCredentials user = result.get();
        return new UserResponse(user.id(), user.username(), user.nickname());
    }
}