package com.campus.market.auth;
public class SessionListException extends RuntimeException {
    public SessionListException(){super("登录明细仅支持page（1～1000000）和size（1～20）的单个规范正整数参数");}
}
