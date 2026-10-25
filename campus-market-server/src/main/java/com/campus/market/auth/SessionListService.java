package com.campus.market.auth;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionListService {
    public record Item(boolean current, String createdAt, String expiresAt) {}
    public record Page(long userId, List<Item> items, long total, int page, int size,
                       long totalPages, String sampledAt) {}
    private record Owner(long id, Timestamp sampled) {}
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    public SessionListService(JdbcTemplate jdbc, SessionService sessions) {
        this.jdbc=jdbc; this.sessions=sessions;
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Page list(String authorization, Map<String,List<String>> parameters) {
        String hash=sessions.tokenHash(authorization);
        var owners=jdbc.query("""
                SELECT user_id,CURRENT_TIMESTAMP AS sampled_at FROM login_sessions
                WHERE token_hash=? AND revoked_at IS NULL AND expires_at>CURRENT_TIMESTAMP
                """,(rs,n)->new Owner(rs.getLong("user_id"),rs.getTimestamp("sampled_at")),hash);
        var owner=owners.stream().findFirst().orElseThrow(LoginRequiredException::new);
        if(owner.id()<1||owner.id()>9007199254740991L)throw new IllegalStateException("账号编号超出展示范围");
        if(parameters==null||!Set.of("page","size").containsAll(parameters.keySet()))throw new SessionListException();
        int requested=number(parameters,"page",1,1000000),size=number(parameters,"size",10,20);
        Long count=jdbc.queryForObject("""
                SELECT COUNT(*) FROM login_sessions
                WHERE user_id=? AND revoked_at IS NULL AND expires_at>?
                """,Long.class,owner.id(),owner.sampled());
        if(count==null||count<1||count>9007199254740991L)throw new IllegalStateException("有效登录数量无效");
        long pages=Math.max(1,(count-1)/size+1);
        int page=(int)Math.min(requested,pages);
        long offset=(page-1L)*size;
        var items=jdbc.query("""
                SELECT token_hash=? AS current_session,created_at,expires_at FROM login_sessions
                WHERE user_id=? AND revoked_at IS NULL AND expires_at>?
                ORDER BY created_at DESC,token_hash DESC LIMIT ? OFFSET ?
                """,(rs,n)->{
                    Timestamp created=rs.getTimestamp("created_at"),expires=rs.getTimestamp("expires_at");
                    if(created==null||expires==null||created.after(owner.sampled())||!expires.after(owner.sampled()))
                        throw new IllegalStateException("登录明细时间无效");
                    return new Item(rs.getBoolean("current_session"),created.toLocalDateTime().toString(),expires.toLocalDateTime().toString());
                },hash,owner.id(),owner.sampled(),size,offset);
        if(items.size()!=Math.min(size,count-offset))throw new IllegalStateException("登录明细分页不完整");
        return new Page(owner.id(),List.copyOf(items),count,page,size,pages,owner.sampled().toLocalDateTime().toString());
    }
    private static int number(Map<String,List<String>> query,String name,int fallback,int max) {
        if(!query.containsKey(name))return fallback;
        var values=query.get(name);
        if(values==null||values.size()!=1||values.get(0)==null||!values.get(0).matches("[1-9][0-9]{0,6}"))throw new SessionListException();
        int value=Integer.parseInt(values.get(0));
        if(value>max)throw new SessionListException();
        return value;
    }
}
