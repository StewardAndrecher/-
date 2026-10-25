package com.campus.market.draft;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import static com.campus.market.draft.DraftBackupModels.*;

@Repository
public class DraftBackupRepository {
 private final JdbcTemplate jdbc;
 private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
 public DraftBackupRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
 private static final RowMapper<Backup> MAPPER=(rs,n)->new Backup(rs.getString("draft_id"),rs.getLong("source_revision"),rs.getLong("version"),rs.getTimestamp("updated_at").toLocalDateTime().toString(),new Content(rs.getString("title"),rs.getString("description"),rs.getString("price_text"),rs.getString("cover_image_id")==null?"":rs.getString("cover_image_id"),rs.getString("category_code"),rs.getString("details_json")==null?null:JSON.readValue(rs.getString("details_json"),com.campus.market.product.ProductDetails.class)));
 // 锁定账号行，使同一账号的容量检查和写入处于同一个串行事务中。
 public void lockOwner(long owner) { jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,owner); }
 public List<Backup> list(long owner) { return jdbc.query("SELECT * FROM product_draft_backups WHERE user_id=? ORDER BY updated_at DESC,draft_id",MAPPER,owner); }
 // 列表总数与分页查询必须使用完全相同的账号、标题、分类条件。
 private static String pattern(String keyword) { return "%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
 private static final String MATCH=" WHERE user_id=? AND title LIKE ? ESCAPE '!' AND (?='' OR category_code=?)";
 public long matchingCount(long owner,String keyword,String category) {
  return jdbc.queryForObject("SELECT COUNT(*) FROM product_draft_backups"+MATCH,Long.class,owner,pattern(keyword),category,category);
 }
 public List<Backup> page(long owner,String keyword,String category,DraftBackupSort sort,int size,int offset) {
  return jdbc.query("SELECT * FROM product_draft_backups"+MATCH+" ORDER BY "+sort.orderBy()+" LIMIT ? OFFSET ?",MAPPER,owner,pattern(keyword),category,category,size,offset);
 }
 public Optional<Backup> read(long owner,String id) {
  return jdbc.query("SELECT * FROM product_draft_backups WHERE user_id=? AND draft_id=?",MAPPER,owner,id).stream().findFirst();
 }
 public Optional<Backup> find(long owner,String id) { return jdbc.query("SELECT * FROM product_draft_backups WHERE user_id=? AND draft_id=? FOR UPDATE",MAPPER,owner,id).stream().findFirst(); }
 public int count(long owner) { return jdbc.queryForList("SELECT draft_id FROM product_draft_backups WHERE user_id=? FOR UPDATE",String.class,owner).size(); }
 public void insert(long owner,String id,SaveRequest r) {
  var c=r.content();jdbc.update("INSERT INTO product_draft_backups(user_id,draft_id,source_revision,title,description,price_text,cover_image_id,category_code,details_json) VALUES(?,?,?,?,?,?,?,?,?)",owner,id,r.sourceRevision(),c.title(),c.description(),c.priceText(),c.coverImageId().isEmpty()?null:c.coverImageId(),c.categoryCode(),c.details()==null?null:JSON.writeValueAsString(c.details()));
 }
 public void update(long owner,String id,SaveRequest r) {
  var c=r.content();jdbc.update("UPDATE product_draft_backups SET source_revision=?,version=version+1,title=?,description=?,price_text=?,cover_image_id=?,category_code=?,details_json=?,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND draft_id=?",r.sourceRevision(),c.title(),c.description(),c.priceText(),c.coverImageId().isEmpty()?null:c.coverImageId(),c.categoryCode(),c.details()==null?null:JSON.writeValueAsString(c.details()),owner,id);
 }
 public int delete(long owner,String id,long version) { return jdbc.update("DELETE FROM product_draft_backups WHERE user_id=? AND draft_id=? AND version=?",owner,id,version); }
}
