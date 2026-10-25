package com.campus.market.notification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
@Component
public class NotificationEvents {
 private final NotificationOutbox outbox;
 public NotificationEvents(NotificationOutbox outbox){this.outbox=outbox;}
 @Transactional(propagation=Propagation.MANDATORY)
 public void order(long id,int sequence,long buyer,long seller,String action,String title){
  String text=switch(action){case "CREATED"->"订单已创建，等待买家付款";case "SIMULATED_PAYMENT"->"订单已模拟付款（教学演示，不扣款）";case "DELIVERED"->"卖家已确认交接，等待买家确认收货";case "COMPLETED"->"买家已确认收货，订单完成";case "CANCELLED"->"买家已取消订单";case "TIMEOUT_CLOSED"->"待付款订单已超时关闭";default->throw new IllegalArgumentException("通知事件无效");};
  String source="ORDER:"+id+":"+sequence,body="订单 #"+id+"《"+title+"》："+text;
  outbox.enqueue(source,buyer,"ORDER",id,"订单进度更新",body);outbox.enqueue(source,seller,"ORDER",id,"订单进度更新",body);
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public void moderation(long event,long product,Long seller,String action,String title,String reason){
  if(seller==null)return;
  String heading=switch(action){case "BLOCK"->"商品已被平台下架";case "APPEAL"->"商品复核申请已提交";case "RELEASE"->"商品平台限制已解除";default->throw new IllegalArgumentException("通知事件无效");};
  outbox.enqueue("MODERATION:"+event,seller,"MODERATION",product,heading,"《"+title+"》："+reason+(action.equals("RELEASE")?"；商品仍下架，请自行确认后上架。":""));
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public void report(long id,long reporter,String result,String title,String resolution){outbox.enqueue("REPORT:"+id+":RESOLVED",reporter,"REPORT",id,"举报处理结果","《"+title+"》"+(result.equals("BLOCK")?"已下架处理：":"举报已驳回：")+resolution);}

 @Transactional(propagation=Propagation.MANDATORY)
 public void orderReview(long orderId,long reviewer,long buyer,long seller){
  if(orderId<1||reviewer<1||buyer<1||seller<1||buyer==seller||(reviewer!=buyer&&reviewer!=seller))throw new IllegalArgumentException("评价通知参与者无效");
  long recipient=reviewer==buyer?seller:buyer;
  outbox.enqueue("ORDER_REVIEW:"+orderId+":"+reviewer,recipient,"ORDER",orderId,
    "收到交易评价","订单 #"+orderId+"收到一条新的交易评价，请进入订单详情查看。通知不展示评分或评价文字。");
 }
 @Transactional(propagation=Propagation.MANDATORY)
 public void orderIssueResolved(long order,long applicant){
  outbox.enqueue("ORDER_ISSUE:"+order+":"+applicant+":RESOLVED",applicant,"ORDER",order,
    "交易问题反馈已有处理结果","订单 #"+order+"的本人反馈已有处理结果，请进入订单详情查看。通知不展示反馈或处理说明。");
 }

}
