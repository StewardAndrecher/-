package com.campus.market.auth;

import com.campus.market.user.UserResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class SessionService {
    private final SessionRepository sessionRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionService(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public LoginResponse issueToken(UserResponse user) {
        sessionRepository.lockUser(user.id());
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bytes);
        sessionRepository.insert(hashToken(token), user.id());
        return new LoginResponse(
                token, "Bearer", SessionRepository.TOKEN_TTL_SECONDS, user
        );
    }

    public UserResponse currentUser(String authorization) {
        String token = extractToken(authorization);
        return sessionRepository.findCurrentUser(hashToken(token))
                .orElseThrow(LoginRequiredException::new);
    }

    String tokenHash(String authorization) {
        return hashToken(extractToken(authorization));
    }

    public void logout(String authorization) {
        String token = extractToken(authorization);
        sessionRepository.revoke(hashToken(token));
    }

    private String extractToken(String authorization) {
        String header = authorization == null ? "" : authorization.trim();
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new LoginRequiredException();
        }
        String token = header.substring(7);
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            throw new LoginRequiredException();
        }
        return token;
    }

    private String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持SHA-256", exception);
        }
    }
}
