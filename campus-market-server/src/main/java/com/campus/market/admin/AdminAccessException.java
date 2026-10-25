package com.campus.market.admin;

public class AdminAccessException extends RuntimeException {
    public AdminAccessException() {super("当前账号没有管理权限");}
}
