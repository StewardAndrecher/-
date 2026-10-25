package com.campus.market.order;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.order.OrderTaskModels.*;

@RestController
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class OrderTaskController {
    private final OrderTaskService service;
    public OrderTaskController(OrderTaskService service){this.service=service;}
    @GetMapping(value="/api/orders/tasks/overview",produces="application/json;charset=UTF-8")
    public ResponseEntity<Overview> ownTradeTasks(@RequestHeader(value="Authorization",required=false)String auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overview(auth));
    }
}
