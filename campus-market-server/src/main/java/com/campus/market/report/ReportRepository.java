package com.campus.market.report;
import java.util.*;
import java.sql.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import com.campus.market.product.ProductModerationModels.Evidence;
import com.campus.market.product.ProductResponse;
import static com.campus.market.report.ReportModels.*;
@Repository
public class ReportRepository {
    private final JdbcTemplate jdbc;private final JsonMapper json=JsonMapper.builder().build();
    public ReportRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private Long nullable(ResultSet r,String name)throws SQLException {long value=r.getLong(name);return r.wasNull()?null:value;}
    private Stored map(ResultSet r,int row)throws SQLException {
        var ticket=new Ticket(r.getLong("id"),r.getLong("product_id"),r.getString("category"),r.getString("reason"),r.getString("status"),r.getLong("version"),r.getString("resolution"),r.getString("created_at"),r.getString("resolved_at"),nullable(r,"moderation_event_id"),json.readValue(r.getString("product_snapshot"),Evidence.class));
        return new Stored(ticket,r.getLong("reporter_id"),r.getString("request_id"),nullable(r,"resolved_by"),r.getString("resolution_request_id"),nullable(r,"expected_product_version"));
    }
    public Optional<Stored> find(long id,boolean lock){return jdbc.query("SELECT * FROM product_reports WHERE id=?"+(lock?" FOR UPDATE":""),this::map,id).stream().findFirst();}
    public Optional<Stored> request(long actor,String key){return jdbc.query("SELECT * FROM product_reports WHERE reporter_id=? AND request_id=?",this::map,actor,key).stream().findFirst();}
    public boolean pending(long product,long actor){return jdbc.queryForObject("SELECT COUNT(*) FROM product_reports WHERE product_id=? AND reporter_id=? AND status='OPEN'",Long.class,product,actor)>0;}
    public long insert(long actor,ProductResponse product,String category,String reason,String requestId){
        var keys=new GeneratedKeyHolder();String snapshot=json.writeValueAsString(new Evidence(product.title(),product.description(),product.priceInCents(),product.category().code(),product.coverImageId()));
        jdbc.update(connection->{var s=connection.prepareStatement("INSERT INTO product_reports(product_id,reporter_id,request_id,category,reason,product_snapshot) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            s.setLong(1,product.id());s.setLong(2,actor);s.setString(3,requestId);s.setString(4,category);s.setString(5,reason);s.setString(6,snapshot);return s;},keys);return Objects.requireNonNull(keys.getKey()).longValue();
    }
    public int resolve(long id,long actor,String state,String reason,String key,Long productVersion,Long event){return jdbc.update("UPDATE product_reports SET status=?,version=1,resolution=?,resolved_by=?,resolved_at=CURRENT_TIMESTAMP(6),resolution_request_id=?,expected_product_version=?,moderation_event_id=? WHERE id=? AND status='OPEN' AND version=0",state,reason,actor,key,productVersion,event,id);}
    public Actor reporter(long id){return jdbc.queryForObject("SELECT id,nickname FROM users WHERE id=?",(r,row)->new Actor(r.getLong(1),r.getString(2)),id);}
    private String where(Long actor,String state){return " WHERE 1=1"+(actor==null?"":" AND reporter_id=?")+(state.isEmpty()?"":" AND status=?");}
    private ArrayList<Object> args(Long actor,String state){var a=new ArrayList<Object>();if(actor!=null)a.add(actor);if(!state.isEmpty())a.add(state);return a;}
    public long count(Long actor,String state){return jdbc.queryForObject("SELECT COUNT(*) FROM product_reports"+where(actor,state),Long.class,args(actor,state).toArray());}
    public List<Ticket> page(Long actor,String state,int size,long offset){var a=args(actor,state);a.add(size);a.add(offset);return jdbc.query("SELECT * FROM product_reports"+where(actor,state)+" ORDER BY id DESC LIMIT ? OFFSET ?",(r,row)->map(r,row).ticket(),a.toArray());}
}
