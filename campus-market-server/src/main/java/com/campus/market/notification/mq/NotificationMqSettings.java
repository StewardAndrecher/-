package com.campus.market.notification.mq;
public record NotificationMqSettings(String prefix,long confirmTimeoutMs){
 public NotificationMqSettings{if(prefix==null||!prefix.matches("campus\\.notification\\.[a-zA-Z0-9._-]{1,50}")||confirmTimeoutMs<100||confirmTimeoutMs>10000)throw new IllegalArgumentException("通知MQ配置无效");}
 public String exchange(){return prefix+".exchange";}public String queue(){return prefix+".queue";}public String parkingExchange(){return prefix+".parking.exchange";}public String parkingQueue(){return prefix+".parking.queue";}
}
