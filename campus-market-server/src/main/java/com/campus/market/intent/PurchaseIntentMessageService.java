package com.campus.market.intent;
import com.campus.market.auth.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import static com.campus.market.intent.PurchaseIntentMessageModels.*;
@Service
public class PurchaseIntentMessageService {
 private final PurchaseIntentMessageRepository messages;
 private final SessionService sessions;
 public PurchaseIntentMessageService(PurchaseIntentMessageRepository messages,SessionService sessions) {this.messages=messages;this.sessions=sessions;}
 private void validateId(long id) {if(id<1||id>9007199254740991L)throw invalid("购买意向编号不合法");}
 private PurchaseIntentException invalid(String text) {return new PurchaseIntentException(400,"INVALID_INTENT_MESSAGE",text);}
 private String requireParticipant(long id,long userId,boolean lock) {
  var status=messages.participantStatus(id,userId,lock);
  if(status==null)throw new PurchaseIntentException(404,"PURCHASE_INTENT_NOT_FOUND","购买意向不存在或无权查看");
  return status;
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Page page(long intentId,int page,int size,String auth) {
  long userId=sessions.currentUser(auth).id();validateId(intentId);
  if(page<1||page>1000000||size<1||size>20)throw invalid("页码须为1～1000000，每页须为1～20条");
  var status=requireParticipant(intentId,userId,false);long total=messages.count(intentId);
  int totalPages=(int)Math.max(1,Math.min(Integer.MAX_VALUE,(total+size-1)/size)),actual=Math.min(page,totalPages);
  return new Page(messages.page(intentId,size,(long)(actual-1)*size),intentId,status,total,actual,size,totalPages);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Sent send(long intentId,SendRequest request,String auth) {
  long senderId=sessions.currentUser(auth).id();validateId(intentId);
  String content=request==null||request.content()==null?"":request.content().strip();
  if(content.codePointCount(0,content.length())<1||content.codePointCount(0,content.length())>500||content.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw invalid("留言须为1～500个字符，不能全为空白或包含非法控制字符");
  String requestId=request.requestId();
  if(requestId==null||!requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("请提供小写标准UUID格式的requestId");
  // Lock only this intention. Existing accept takes product then intention; this path never locks a product.
  var status=requireParticipant(intentId,senderId,true);
  var existing=messages.existing(intentId,senderId,requestId);
  if(existing!=null) {
   if(!existing.originalContent().equals(content))throw new PurchaseIntentException(409,"INTENT_MESSAGE_CONFLICT","同一请求编号不能用于不同留言内容");
   return new Sent(existing.message(),true);
  }
  if(!"ACCEPTED".equals(status))throw new PurchaseIntentException(409,"INTENT_CHAT_NOT_OPEN","卖家接受意向后才能发送私密留言");
  long id=messages.insert(intentId,senderId,content,requestId);return new Sent(messages.read(id),false);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public Withdrawn withdraw(long intentId,long messageId,String auth) {
  long senderId=sessions.currentUser(auth).id();validateId(intentId);validateId(messageId);
  // All writes use intention -> message order. No reverse lock on products.
  requireParticipant(intentId,senderId,true);
  var state=messages.lockMessage(intentId,messageId);
  if(state==null)throw new PurchaseIntentException(404,"INTENT_MESSAGE_NOT_FOUND","私密留言不存在");
  if(state.senderId()!=senderId)throw new PurchaseIntentException(403,"INTENT_MESSAGE_FORBIDDEN","只能撤回自己发送的私密留言");
  if(state.withdrawn())return new Withdrawn(messages.read(messageId),true);
  if(messages.withdraw(intentId,messageId,senderId)!=1)throw new PurchaseIntentException(409,"INTENT_MESSAGE_CHANGED","留言状态已变化，请刷新后确认");
  return new Withdrawn(messages.read(messageId),false);
 }

 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public ReadState readState(long intentId,String auth) {
  long userId=sessions.currentUser(auth).id();validateId(intentId);
  requireParticipant(intentId,userId,false);
  return messages.readState(intentId,userId);
 }
 @Transactional(isolation=Isolation.READ_COMMITTED)
 public ReadState markRead(long intentId,ReadRequest request,String auth) {
  long userId=sessions.currentUser(auth).id();validateId(intentId);
  if(request==null||request.lastMessageId()==null||request.lastMessageId()<0||request.lastMessageId()>9007199254740991L)
   throw invalid("已读留言编号须为非负安全整数");
  requireParticipant(intentId,userId,true);
  long through=request.lastMessageId();
  if(through>0&&!messages.containsMessage(intentId,through))
   throw new PurchaseIntentException(404,"INTENT_MESSAGE_NOT_FOUND","私密留言不存在");
  messages.advanceReadCursor(intentId,userId,through);
  return messages.readState(intentId,userId);
 }

}
