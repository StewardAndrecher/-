package com.campus.market.user;

public final class NicknameValidator {
    private NicknameValidator() {}
    public static String normalize(String raw) {
        String nickname = raw == null ? "" : raw.strip();
        int length = nickname.codePointCount(0, nickname.length());
        if (length < 1 || length > 30) throw new InvalidUserException("昵称需要1～30个字符");
        if (nickname.codePoints().anyMatch(Character::isISOControl))
            throw new InvalidUserException("昵称不能包含换行或控制字符");
        return nickname;
    }
}
