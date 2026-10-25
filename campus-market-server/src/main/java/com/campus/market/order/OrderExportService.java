package com.campus.market.order;
import com.campus.market.auth.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
@Service
public class OrderExportService {
 public static final int LIMIT=200;
 private final SessionService sessions;private final OrderRepository orders;
 public OrderExportService(SessionService sessions,OrderRepository orders){this.sessions=sessions;this.orders=orders;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public OrderExportModels.Snapshot export(String role,String status,String rawOrder,String auth){
  long userId=sessions.currentUser(auth).id();
  Long exact=OrderService.filterOrder(rawOrder);boolean seller=OrderService.sellerScope(role,status);
  // A single bounded SELECT provides one consistent statement snapshot; never silently truncate.
  var records=orders.exportRows(userId,seller,status,exact,LIMIT+1);
  if(records.size()>LIMIT)throw new OrderException(413,"ORDER_EXPORT_LIMIT","当前条件超过200笔，请按状态或完整订单编号缩小范围后再导出");
  return new OrderExportModels.Snapshot(role,status,exact,records.size(),LIMIT,records);
 }
}
