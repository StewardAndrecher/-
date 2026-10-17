package com.campus.market.intent;
import com.campus.market.auth.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;
import static com.campus.market.intent.PurchaseIntentUnreadModels.*;
@Service
public class PurchaseIntentUnreadService {
 private final PurchaseIntentUnreadRepository repository;
 private final SessionService sessions;
 public PurchaseIntentUnreadService(PurchaseIntentUnreadRepository repository,SessionService sessions) {this.repository=repository;this.sessions=sessions;}
 private List<Long> parseIds(String value) {
  if(value==null||value.isEmpty())return List.of();
  if(value.length()>339)throw PurchaseIntentException.invalid("一次最多查询20个意向编号");
  var parts=value.split(",",-1);if(parts.length>20)throw PurchaseIntentException.invalid("一次最多查询20个意向编号");
  var ids=new ArrayList<Long>();var seen=new HashSet<Long>();
  for(String part:parts) {
   if(!part.matches("[1-9][0-9]{0,15}"))throw PurchaseIntentException.invalid("意向编号须为不重复的正安全整数");
   long id=Long.parseLong(part);
   if(id>9007199254740991L||!seen.add(id))throw PurchaseIntentException.invalid("意向编号须为不重复的正安全整数");
   ids.add(id);
  }
  return ids;
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Batch batch(String roleValue,String intentIds,String auth) {
  long userId=sessions.currentUser(auth).id();
  var role=PurchaseIntentRole.parse(roleValue);var ids=parseIds(intentIds);
  var found=repository.counts(userId,role,ids);
  if(found.size()!=ids.size())throw new PurchaseIntentException(404,"PURCHASE_INTENT_NOT_FOUND","购买意向不存在或不属于当前查看身份");
  var byId=new HashMap<Long,Item>();for(var item:found)byId.put(item.intentId(),item);
  var ordered=new ArrayList<Item>();long messages=0,intents=0;
  for(long id:ids) {var item=byId.get(id);ordered.add(item);messages+=item.unreadCount();if(item.unreadCount()>0)intents++;}
  return new Batch(role.name(),ordered,messages,intents);
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Summary summary(String roleValue,String auth) {
  long userId=sessions.currentUser(auth).id();
  return repository.summary(userId,PurchaseIntentRole.parse(roleValue));
 }

}
