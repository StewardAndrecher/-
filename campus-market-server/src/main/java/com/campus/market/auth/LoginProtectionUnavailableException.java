package com.campus.market.auth;

public class LoginProtectionUnavailableException extends RuntimeException {
    public LoginProtectionUnavailableException() {
        super("登录保护服务暂不可用，请稍后重试");
    }
}
