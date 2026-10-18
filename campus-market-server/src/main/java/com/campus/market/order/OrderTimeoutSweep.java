package com.campus.market.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 读取最多100个候选；每笔调用独立事务，一个失败不阻止其他订单。 */
@Component
public class OrderTimeoutSweep {
 private static final Logger log=LoggerFactory.getLogger(OrderTimeoutSweep.class);
 private final OrderRepository orders;
 private final OrderService service;
 public OrderTimeoutSweep(OrderRepository orders,OrderService service){this.orders=orders;this.service=service;}
 public int runOnce(){
  int closed=0;
  for(long id:orders.dueIds()){
   try{if(service.closeExpired(id))closed++;}
   catch(RuntimeException e){log.warn("订单超时关闭失败，订单ID={}，将在后续批次重试",id,e);}
  }
  return closed;
 }
}
