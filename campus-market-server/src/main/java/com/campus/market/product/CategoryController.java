package com.campus.market.product;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
@CrossOrigin(originPatterns={"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class CategoryController {
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<List<CategoryResponse>> list() {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ProductCategory.catalog());
    }
}
