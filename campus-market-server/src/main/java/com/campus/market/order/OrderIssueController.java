package com.campus.market.order;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.order.OrderIssueModels.*;

@RestController
@RequestMapping("/api/orders/{id}/issue")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class OrderIssueController {
    private final OrderIssueService service;
    public OrderIssueController(OrderIssueService service){this.service=service;}
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<Summary> orderIssue(@PathVariable String id,@RequestHeader(value="Authorization",required=false)String auth){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summary(id,auth));
    }
    @PostMapping(consumes="application/json",produces="application/json;charset=UTF-8")
    public ResponseEntity<Receipt> createOrderIssue(@PathVariable String id,@RequestBody Create request,@RequestHeader(value="Authorization",required=false)String auth){
        var result=service.create(id,request,auth);return ResponseEntity.status(result.replayed()?200:201).cacheControl(CacheControl.noStore()).body(result);
    }
}
