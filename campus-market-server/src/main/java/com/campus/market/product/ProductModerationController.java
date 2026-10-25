package com.campus.market.product;

import com.campus.market.product.ProductModerationModels.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products/{id}/moderation")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class ProductModerationController {
    private final ProductModerationService moderation;
    public ProductModerationController(ProductModerationService moderation){this.moderation=moderation;}
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<Detail> sellerModeration(@PathVariable long id,@RequestHeader(name="Authorization",required=false) String authorization){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.sellerDetail(id,authorization));}
    @PostMapping(value="/appeals",consumes="application/json",produces="application/json;charset=UTF-8")
    public ResponseEntity<Receipt> appealModeration(@PathVariable long id,@RequestBody Appeal request,@RequestHeader(name="Authorization",required=false) String authorization){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(moderation.appeal(id,request,authorization));}
}
