package com.campus.market.product;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/product-sorts")
@CrossOrigin(originPatterns={"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class ProductSortController {
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<List<ProductSort.Option>> list() {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ProductSort.catalog());
    }
}
