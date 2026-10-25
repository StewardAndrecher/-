package com.campus.market.notification;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.campus.market.auth.SessionService;
import com.campus.market.product.InvalidProductException;
import com.campus.market.product.ProductNotFoundException;
import static com.campus.market.notification.NotificationModels.*;
@Service
public class NotificationService {
 private final JdbcTemplate jdbc;private final SessionService sessions;
 public NotificationService(JdbcTemplate jdbc,SessionService sessions){this.jdbc=jdbc;this.sessions=sessions;}
 // The scalar lookup uses the source primary key and does not add an Outbox lock to inbox FOR UPDATE reads.
 private static final String SELECT_NOTICE="SELECT user_notifications.*, (SELECT source_key FROM notification_outbox source WHERE source.event_id=user_notifications.event_id) AS source_key FROM user_notifications";
 private Notice map(ResultSet r,int row)throws SQLException{return new Notice(r.getLong("id"),r.getString("category"),r.getLong("resource_id"),NotificationTargets.target(r.getString("category"),r.getLong("resource_id"),r.getLong("recipient_id"),r.getString("source_key")),r.getString("title"),r.getString("body"),r.getString("occurred_at"),r.getString("created_at"),r.getString("read_at"));}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page page(int page,int size,String status,String auth){return page(page,size,status,"ALL",auth);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page page(int page,int size,String status,String category,String auth){
  long user=sessions.currentUser(auth).id();
  if(page<1||size<1||size>20||status==null||!Set.of("ALL","UNREAD","READ").contains(status)
    ||category==null||!Set.of("ALL","ORDER","REPORT","MODERATION").contains(category))throw new InvalidProductException("通知筛选条件不正确");
  String where=" WHERE recipient_id=?"+switch(status){case "UNREAD"->" AND read_at IS NULL";case "READ"->" AND read_at IS NOT NULL";default->"";};
  var parameters=new ArrayList<Object>();parameters.add(user);
  if(!category.equals("ALL")){where+=" AND category=?";parameters.add(category);}
  long total=jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications"+where,Long.class,parameters.toArray());
  parameters.add(size);parameters.add(((long)page-1)*size);
  return new Page(jdbc.query(SELECT_NOTICE+where+" ORDER BY id DESC LIMIT ? OFFSET ?",this::map,parameters.toArray()),total,page,size);
 }
 @Transactional(readOnly=true)
 public Unread unread(String auth){long user=sessions.currentUser(auth).id();return new Unread(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE recipient_id=? AND read_at IS NULL",Long.class,user));}
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Notice read(long id,String auth){
  long user=sessions.currentUser(auth).id();if(id<1||id>9007199254740991L)throw new InvalidProductException("通知编号不正确");
  var row=jdbc.query(SELECT_NOTICE+" WHERE id=? AND recipient_id=? FOR UPDATE",this::map,id,user);
  if(row.isEmpty())throw new ProductNotFoundException("通知不存在或不属于当前账号");
  jdbc.update("UPDATE user_notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP(6)) WHERE id=? AND recipient_id=?",id,user);
  return jdbc.queryForObject(SELECT_NOTICE+" WHERE id=? AND recipient_id=?",this::map,id,user);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public ReadBatchResult readBatch(ReadBatch request,String auth){
  long user=sessions.currentUser(auth).id();
  if(request==null||request.ids()==null||request.ids().isEmpty()||request.ids().size()>20)throw new InvalidProductException("一次请选择1至20条通知");
  var ids=new TreeSet<Long>();
  for(Long id:request.ids())if(id==null||id<1||id>9007199254740991L||!ids.add(id))throw new InvalidProductException("通知编号无效或重复");
  String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
  var params=new ArrayList<Object>();params.add(user);params.addAll(ids);
  String where=" WHERE recipient_id=? AND id IN("+placeholders+")";
  var rows=jdbc.query(SELECT_NOTICE+where+" ORDER BY id FOR UPDATE",this::map,params.toArray());
  if(rows.size()!=ids.size())throw new ProductNotFoundException("部分通知不存在或不属于当前账号，未执行本次批量操作");
  jdbc.update("UPDATE user_notifications SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP(6))"+where,params.toArray());
  return new ReadBatchResult(jdbc.query(SELECT_NOTICE+where+" ORDER BY id",this::map,params.toArray()));
 }

}
