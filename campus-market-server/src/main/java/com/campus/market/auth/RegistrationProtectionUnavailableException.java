package com.campus.market.auth;
public class RegistrationProtectionUnavailableException extends RuntimeException {
 public RegistrationProtectionUnavailableException(){super("注册保护暂不可用，请稍后重试；本次未创建账号");}
}
