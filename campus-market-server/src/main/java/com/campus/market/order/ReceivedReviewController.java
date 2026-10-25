package com.campus.market.order;

import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/received-reviews")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class ReceivedReviewController {
    private final ReceivedReviewService service;
    public ReceivedReviewController(ReceivedReviewService service){this.service=service;}
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<ReceivedReviewService.Page> receivedReviews(
            @RequestHeader(name="Authorization",required=false)String authorization,
            @RequestParam MultiValueMap<String,String> query){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache")
                .body(service.page(authorization,query));
    }
}
