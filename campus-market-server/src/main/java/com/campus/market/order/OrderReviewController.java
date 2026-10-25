package com.campus.market.order;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.order.OrderReviewModels.*;

@RestController
@RequestMapping("/api/orders/{id}/reviews")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class OrderReviewController {
    private final OrderReviewService reviews;
    public OrderReviewController(OrderReviewService reviews){this.reviews=reviews;}
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<Summary> orderReviews(@PathVariable String id,@RequestHeader(value="Authorization",required=false)String auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.summary(id,auth));
    }
    @PostMapping(consumes="application/json",produces="application/json;charset=UTF-8")
    public ResponseEntity<Receipt> createOrderReview(@PathVariable String id,@RequestBody Create request,@RequestHeader(value="Authorization",required=false)String auth) {
        var receipt=reviews.create(id,request,auth);
        return ResponseEntity.status(receipt.replayed()?200:201).cacheControl(CacheControl.noStore()).body(receipt);
    }
}
