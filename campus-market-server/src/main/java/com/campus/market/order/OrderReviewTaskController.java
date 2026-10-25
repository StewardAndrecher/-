package com.campus.market.order;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/review-tasks")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class OrderReviewTaskController {
    private final OrderReviewTaskService service;
    public OrderReviewTaskController(OrderReviewTaskService service){this.service=service;}
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<OrderReviewTaskModels.Page> reviewTasks(
            @RequestParam(defaultValue="BUYER")String role,@RequestParam(defaultValue="PENDING")String status,
            @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size,
            @RequestHeader(value="Authorization",required=false)String auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(role,status,page,size,auth));
    }
}
