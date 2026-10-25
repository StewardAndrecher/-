package com.campus.market.user;

import com.campus.market.auth.SessionService;
import com.campus.market.auth.LoginRequiredException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
    private final com.campus.market.governance.WriteGovernance governance;
    private final com.campus.market.governance.ContentRules rules;
    private final SessionService sessions;
    private final UserRepository users;
    public UserProfileService(SessionService sessions, UserRepository users,com.campus.market.governance.WriteGovernance governance,com.campus.market.governance.ContentRules rules) {this.governance=governance;this.rules=rules;
        this.sessions = sessions;
        this.users = users;
    }

    @Transactional
    public UserResponse updateNickname(UpdateNicknameRequest request, String authorization) {
        var actor = sessions.currentUser(authorization);
        if (request == null) throw new InvalidUserException("昵称信息不能为空");
        String nickname = NicknameValidator.normalize(request.nickname());
        String expected = request.expectedNickname();
        if (expected == null || expected.isEmpty() || expected.codePointCount(0, expected.length()) > 30)
            throw new InvalidUserException("请重新加载账号资料后再保存");
        rules.requireAllowed(nickname);governance.requireWritable(actor.id(),"PROFILE");
        var current = users.findProfileForUpdate(actor.id()).orElseThrow(LoginRequiredException::new);
        if (!current.nickname().equals(expected)) throw new UserProfileConflictException();
        if (current.nickname().equals(nickname)) return current;
        if (users.updateNickname(current.id(), nickname, expected) != 1)
            throw new UserProfileConflictException();
        return new UserResponse(current.id(), current.username(), nickname);
    }
}
