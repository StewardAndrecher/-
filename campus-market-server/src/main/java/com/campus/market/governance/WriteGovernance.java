package com.campus.market.governance;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class WriteGovernance {
    private final JdbcTemplate jdbc;
    private final boolean rateEnabled;
    public WriteGovernance(JdbcTemplate jdbc,@Value("${market.governance.rate-enabled:true}") boolean rateEnabled){this.jdbc=jdbc;this.rateEnabled=rateEnabled;}
    // Caller owns a business transaction. Lock the user before product/image rows.
    public void lockUser(long user) {jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);}
    public void requireWritable(long user,String action) {
        lockUser(user);
        if(jdbc.queryForList("SELECT restricted_until>CURRENT_TIMESTAMP(6) FROM account_write_restrictions WHERE user_id=? FOR UPDATE",Boolean.class,user).stream().anyMatch(Boolean.TRUE::equals))
            throw new PlatformException(403,"ACCOUNT_WRITE_RESTRICTED","账号暂时不能发布或修改公开内容，请到内容与账号状态查看原因；登录、安全设置及交易售后仍可使用");
        if(!rateEnabled)return;
        int limit=switch(action){case "PRODUCT"->30;case "COMMENT"->60;case "IMAGE"->60;case "PROFILE"->30;default->throw new IllegalArgumentException("Unknown write action");};
        long bucket=jdbc.queryForObject("SELECT FLOOR(UNIX_TIMESTAMP(CURRENT_TIMESTAMP)/600)",Long.class);
        jdbc.update("INSERT IGNORE INTO public_write_windows(user_id,action,bucket) VALUES(?,?,?)",user,action,bucket);
        int count=jdbc.queryForObject("SELECT uses FROM public_write_windows WHERE user_id=? AND action=? AND bucket=? FOR UPDATE",Integer.class,user,action,bucket);
        if(count>=limit)throw new PlatformException(429,"PUBLIC_WRITE_LIMITED","操作过于频繁，请在10分钟后重试");
        jdbc.update("UPDATE public_write_windows SET uses=uses+1 WHERE user_id=? AND action=? AND bucket=?",user,action,bucket);
        jdbc.update("DELETE FROM public_write_windows WHERE user_id=? AND bucket<?",user,bucket-144);
    }
}
