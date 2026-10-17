package com.campus.market.user;

public class UserProfileConflictException extends RuntimeException {
    public UserProfileConflictException() {
        super("昵称已在另一处更新，请重新加载账号资料后再保存");
    }
}
