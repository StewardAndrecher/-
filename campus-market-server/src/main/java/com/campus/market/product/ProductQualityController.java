package com.campus.market.product;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class ProductQualityController {
    private final ProductQualityService service;
    public ProductQualityController(ProductQualityService service){this.service=service;}
    @PostMapping("/api/products/quality-check") public ResponseEntity<ProductQualityService.Result> check(@RequestBody CreateProductRequest input,@RequestHeader(name="Authorization",required=false)String auth){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.check(input,auth));}
}
