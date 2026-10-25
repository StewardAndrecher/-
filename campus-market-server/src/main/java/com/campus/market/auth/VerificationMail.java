package com.campus.market.auth;
public interface VerificationMail {
    boolean available();
    void send(String recipient,String code,String purpose);
}
