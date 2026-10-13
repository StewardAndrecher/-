package com.campus.market.user;

public record RegisterUserRequest(
        String username,
        String password,
        String nickname
) {
}