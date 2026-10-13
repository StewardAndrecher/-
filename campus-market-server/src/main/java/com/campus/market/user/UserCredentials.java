package com.campus.market.user;

// 仅供后端查询和校验，控制器不会把它作为响应返回。
public record UserCredentials(
        long id,
        String username,
        String passwordHash,
        String nickname
) {
}