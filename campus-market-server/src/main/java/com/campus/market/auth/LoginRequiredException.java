package com.campus.market.auth;

public class LoginRequiredException extends RuntimeException {
    public LoginRequiredException() {
        super("请先登录，或重新登录");
    }
}
