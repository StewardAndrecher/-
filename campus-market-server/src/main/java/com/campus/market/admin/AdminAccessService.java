package com.campus.market.admin;

import com.campus.market.auth.SessionService;
import com.campus.market.user.UserResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AdminAccessService {
    private final SessionService sessions;
    private final AdminRoleRepository roles;
    public AdminAccessService(SessionService sessions, AdminRoleRepository roles) {this.sessions = sessions;this.roles = roles;}
    public record Permissions(long userId, List<String> roles, List<String> permissions) {}
    public Permissions permissions(String authorization) {
        UserResponse user = sessions.currentUser(authorization);
        return roles.hasAdminRole(user.id())
                ? new Permissions(user.id(), List.of("USER", "ADMIN"), List.of("ADMIN_ACCESS"))
                : new Permissions(user.id(), List.of("USER"), List.of());
    }
    // Read live grants on every request. A role copied into a token or browser state is never trusted.
    public UserResponse requireAdmin(String authorization) {
        UserResponse user = sessions.currentUser(authorization);
        if (!roles.hasAdminRole(user.id())) throw new AdminAccessException();
        return user;
    }
}
