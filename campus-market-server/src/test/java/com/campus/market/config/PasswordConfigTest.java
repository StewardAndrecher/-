package com.campus.market.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordConfigTest {

    @Test
    void shouldHashAndVerifyPassword() {
        // 只加载密码配置，这个测试不需要连接MySQL。
        try (var context = new AnnotationConfigApplicationContext(PasswordConfig.class)) {
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
            String password = "Campus2026!"; // 演示用密码

            String firstHash = encoder.encode(password);
            String secondHash = encoder.encode(password);

            assertNotEquals(password, firstHash);
            assertNotEquals(firstHash, secondHash);
            assertTrue(encoder.matches(password, firstHash));
            assertTrue(encoder.matches(password, secondHash));
            assertFalse(encoder.matches("Wrong2026!", firstHash));
        }
    }

    @Test
    void shouldRejectPasswordOver72Bytes() {
        try (var context = new AnnotationConfigApplicationContext(PasswordConfig.class)) {
            PasswordEncoder encoder = context.getBean(PasswordEncoder.class);

            // 25个这个汉字占75个UTF-8字节，超过BCrypt的限制。
            assertThrows(
                    IllegalArgumentException.class,
                    () -> encoder.encode("密".repeat(25))
            );
        }
    }
}