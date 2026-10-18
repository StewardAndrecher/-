package com.campus.market.auth;

public class LoginRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;
    public LoginRateLimitedException(long retryAfterSeconds) {
        super("登录尝试过于频繁，请在" + retryAfterSeconds + "秒后重试");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
