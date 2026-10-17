package com.campus.market.user;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse register(RegisterUserRequest request) {
        if (request == null) {
            throw new InvalidUserException("注册信息不能为空");
        }
        String username = request.username() == null
                ? "" : request.username().strip();
        String nickname = NicknameValidator.normalize(request.nickname());
        String password = request.password(); // 密码不去除首尾空格。

        if (!username.matches("[a-z0-9_]{4,32}")) {
            throw new InvalidUserException(
                    "用户名需要4～32位小写英文字母、数字或下划线"
            );
        }
        if (password == null || password.isBlank()) {
            throw new InvalidUserException("密码不能为空或全是空白字符");
        }
        int passwordLength = password.codePointCount(0, password.length());
        if (passwordLength < 8 || passwordLength > 64) {
            throw new InvalidUserException("密码需要8～64个字符");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidUserException("密码的UTF-8长度不能超过72字节");
        }

        String passwordHash = passwordEncoder.encode(password);
        try {
            long id = userRepository.insert(username, passwordHash, nickname);
            return new UserResponse(id, username, nickname);
        } catch (DuplicateKeyException exception) {
            // 数据库的唯一约束同时保护并发注册。
            throw new UsernameAlreadyExistsException();
        }
    }
}