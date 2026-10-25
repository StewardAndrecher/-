package com.campus.market.product;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(originPatterns={"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class ProductLookupController {
    private final ProductLookupService service;
    public ProductLookupController(ProductLookupService service) { this.service = service; }

    @PostMapping(value="/lookup", consumes="application/json", produces="application/json;charset=UTF-8")
    public ResponseEntity<ProductLookupModels.Response> lookupPublicProducts(@RequestBody ProductLookupModels.Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.lookup(request));
    }
}
