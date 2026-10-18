package com.campus.market.order.mq;

public record OrderMqSettings(String prefix,int delayMs,long confirmTimeoutMs) {
 public OrderMqSettings {
  if(prefix==null||!prefix.matches("campus\\.order\\.[a-zA-Z0-9._-]{1,50}")||delayMs<1||delayMs>900000||confirmTimeoutMs<100||confirmTimeoutMs>10000)throw new IllegalArgumentException("订单MQ配置无效");
 }
 public String delayExchange(){return prefix+".delay.exchange";}
 public String delayQueue(){return prefix+".delay.queue";}
 public String workExchange(){return prefix+".timeout.exchange";}
 public String workQueue(){return prefix+".timeout.queue";}
 public String parkingExchange(){return prefix+".parking.exchange";}
 public String parkingQueue(){return prefix+".parking.queue";}
}
