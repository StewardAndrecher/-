package com.campus.market.auth;

public class PasswordChangeException extends RuntimeException {
    private final int status;
    private final String code;
    public PasswordChangeException(int status, String code, String message) {
        super(message); this.status=status; this.code=code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
