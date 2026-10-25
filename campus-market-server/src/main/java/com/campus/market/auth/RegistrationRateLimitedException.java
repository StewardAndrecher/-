package com.campus.market.auth;
public class RegistrationRateLimitedException extends RuntimeException {
 private final long seconds;
 public RegistrationRateLimitedException(long seconds){super("注册请求过于频繁，请在"+seconds+"秒后重试");this.seconds=seconds;}
 public long retryAfterSeconds(){return seconds;}
}
