package com.campus.market.governance;
import com.campus.market.admin.AdminAccessService;
import com.campus.market.auth.SessionService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.campus.market.product.ProductVersionDeserializer;
@Service
public class GovernanceService {
    public record Event(long id,String action,String reason,String until,String createdAt){}
    public record State(long userId,String username,boolean restricted,String until,String reason,long version,List<Event> events){}
    public record Change(String action,Integer hours,String reason,@JsonDeserialize(using=ProductVersionDeserializer.class) Long version){}
    private final JdbcTemplate jdbc;private final SessionService sessions;private final AdminAccessService admins;
    public GovernanceService(JdbcTemplate jdbc,SessionService sessions,AdminAccessService admins){this.jdbc=jdbc;this.sessions=sessions;this.admins=admins;}
    private State read(long id) {
        var rows=jdbc.query("SELECT u.id,u.username,r.restricted_until>CURRENT_TIMESTAMP(6),r.restricted_until,r.reason,r.version FROM users u LEFT JOIN account_write_restrictions r ON r.user_id=u.id WHERE u.id=?",(r,n)->new State(r.getLong(1),r.getString(2),r.getBoolean(3),r.getString(4),Objects.requireNonNullElse(r.getString(5),""),r.getLong(6),List.of()),id);
        if(rows.isEmpty())throw new PlatformException(404,"ACCOUNT_NOT_FOUND","账号不存在");
        var value=rows.get(0);var events=jdbc.query("SELECT id,action,reason,restricted_until,created_at FROM account_restriction_events WHERE user_id=? ORDER BY id DESC LIMIT 50",(r,n)->new Event(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),id);
        return new State(value.userId(),value.username(),value.restricted(),value.until(),value.reason(),value.version(),events);
    }
    @Transactional(readOnly=true) public State mine(String auth){return read(sessions.currentUser(auth).id());}
    @Transactional(readOnly=true) public State lookup(String username,String auth){admins.requireAdmin(auth);if(username==null||!username.matches("[a-z0-9_]{4,32}"))throw new PlatformException(400,"INVALID_ACCOUNT","请填写准确用户名");var ids=jdbc.queryForList("SELECT id FROM users WHERE username=?",Long.class,username);if(ids.isEmpty())throw new PlatformException(404,"ACCOUNT_NOT_FOUND","账号不存在");return read(ids.get(0));}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public State appeal(Change request,String auth){
        long user=sessions.currentUser(auth).id();
        if(request==null||request.version()==null||request.reason()==null||request.reason().strip().length()<5||request.reason().length()>200||request.reason().codePoints().anyMatch(Character::isISOControl))throw new PlatformException(400,"INVALID_APPEAL","请填写5～200字的复核说明");
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);var before=read(user);
        if(!before.restricted()||before.version()!=request.version())throw new PlatformException(409,"APPEAL_CONFLICT","账号状态已变化，请刷新后核对");
        try{jdbc.update("INSERT INTO account_restriction_events(user_id,actor_id,action,reason,appeal_version) VALUES(?,?,'APPEAL',?,?)",user,user,request.reason().strip(),before.version());}
        catch(org.springframework.dao.DuplicateKeyException ex){throw new PlatformException(409,"APPEAL_EXISTS","本次处理已有复核申请，请等待平台答复");}
        return read(user);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public State change(long target,Change request,String auth){
        var actor=admins.requireAdmin(auth);
        if(request==null||request.action()==null||!Set.of("RESTRICT","RELEASE").contains(request.action())||request.version()==null||request.version()<0||request.version()>=9007199254740991L||request.reason()==null||request.reason().strip().codePointCount(0,request.reason().strip().length())<5||request.reason().length()>200||request.reason().codePoints().anyMatch(Character::isISOControl))throw new PlatformException(400,"INVALID_RESTRICTION","请填写5～200字的处理原因与有效版本");
        // Admins cannot restrict other admins or themselves through a public-content tool.
        if(jdbc.queryForObject("SELECT COUNT(*) FROM user_role_grants WHERE user_id=? AND role_code='ADMIN'",Long.class,target)>0||target==actor.id())throw new PlatformException(409,"RESTRICTION_PROTECTED","此账号受保护，请使用独立的权限管理流程");
        var user=jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,target);if(user.isEmpty())throw new PlatformException(404,"ACCOUNT_NOT_FOUND","账号不存在");
        jdbc.update("INSERT IGNORE INTO account_write_restrictions(user_id) VALUES(?)",target);var before=read(target);
        if(before.version()!=request.version())throw new PlatformException(409,"RESTRICTION_CONFLICT","处理记录已变化，请刷新后核对");
        if("RESTRICT".equals(request.action())&&(request.hours()==null||request.hours()<1||request.hours()>720))throw new PlatformException(400,"INVALID_RESTRICTION","限制时间为1～720小时");
        String until="RESTRICT".equals(request.action())?jdbc.queryForObject("SELECT DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL ? HOUR)",String.class,request.hours()):null;
        jdbc.update("UPDATE account_write_restrictions SET restricted_until=?,reason=?,version=version+1 WHERE user_id=?",until,request.reason().strip(),target);
        jdbc.update("INSERT INTO account_restriction_events(user_id,actor_id,action,reason,restricted_until) VALUES(?,?,?,?,?)",target,actor.id(),request.action(),request.reason().strip(),until);
        return read(target);
    }
}
