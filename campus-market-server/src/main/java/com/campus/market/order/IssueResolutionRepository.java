package com.campus.market.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.Set;
import java.util.UUID;

@Repository
public class IssueResolutionRepository {
    public record Stored(long resolverId,String requestId,OrderIssueModels.Resolution resolution) {}
    private final JdbcTemplate jdbc;
    public IssueResolutionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Stored find(long order,long applicant,Timestamp submitted) {
        var values=jdbc.query("SELECT resolver_id,request_id,decision,explanation,created_at FROM order_issue_resolutions WHERE order_id=? AND applicant_id=?",(r,n)->{
            long resolver=r.getLong("resolver_id");String key=r.getString("request_id"),decision=r.getString("decision"),text=r.getString("explanation");Timestamp at=r.getTimestamp("created_at");
            try {
                if(resolver<1||key==null||!UUID.fromString(key).toString().equals(key)||decision==null||!Set.of("ANSWERED","DISMISSED").contains(decision)
                        ||!OrderIssueText.normalize(text).equals(text)||submitted==null||at==null||at.before(submitted))throw unavailable();
            }catch(IllegalArgumentException|OrderException invalid){throw unavailable();}
            return new Stored(resolver,key,new OrderIssueModels.Resolution(decision,text,at.toLocalDateTime().toString()));
        },order,applicant);
        return values.isEmpty()?null:values.get(0);
    }
    private static OrderException unavailable(){return new OrderException(503,"ISSUE_RESOLUTION_UNAVAILABLE","反馈处理记录异常，请稍后重试");}
}
