package com.campus.market.auth;

import com.campus.market.user.UserResponse;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UserResponse user
) {
}