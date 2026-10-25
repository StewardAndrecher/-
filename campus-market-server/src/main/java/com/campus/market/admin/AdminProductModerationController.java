package com.campus.market.admin;

import com.campus.market.product.ProductModerationService;
import com.campus.market.product.ProductModerationModels.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/products")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class AdminProductModerationController {
    private final ProductModerationService moderation;
    public AdminProductModerationController(ProductModerationService moderation){this.moderation=moderation;}
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<Page> moderationProducts(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,
            @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String moderationState,
            @RequestHeader(name="Authorization",required=false) String authorization) {
        return ok(moderation.adminProducts(page,size,keyword,status,moderationState,authorization));
    }
    @GetMapping(value="/{id}/moderation",produces="application/json;charset=UTF-8")
    public ResponseEntity<Detail> moderationDetail(@PathVariable long id,@RequestHeader(name="Authorization",required=false) String authorization){return ok(moderation.adminDetail(id,authorization));}
    @PostMapping(value="/{id}/moderation/actions",consumes="application/json",produces="application/json;charset=UTF-8")
    public ResponseEntity<Receipt> moderateProduct(@PathVariable long id,@RequestBody Action request,@RequestHeader(name="Authorization",required=false) String authorization){return ok(moderation.adminAction(id,request,authorization));}
    private <T> ResponseEntity<T> ok(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
