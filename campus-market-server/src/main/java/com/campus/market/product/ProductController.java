package com.campus.market.product;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(originPatterns = {
        "http://localhost:[*]",
        "http://127.0.0.1:[*]"
})
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping(produces = "application/json;charset=UTF-8")
    public ProductPageResponse listProducts(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestParam(name = "keyword", defaultValue = "") String keyword
    ) {
        return productService.listProducts(page, size, keyword);
    }

    @GetMapping(value = "/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<ProductResponse> getProduct(
            @PathVariable("id") long id,
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(productService.getProduct(id, authorization));
    }

    @GetMapping(value = "/mine", produces = "application/json;charset=UTF-8")
    public ResponseEntity<ProductPageResponse> listMyProducts(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(productService.listMyProducts(page, size, authorization));
    }

    @PutMapping(value = "/{id}/status", consumes = "application/json",
            produces = "application/json;charset=UTF-8")
    public ResponseEntity<ProductResponse> updateProductStatus(
            @PathVariable("id") long id,
            @RequestBody UpdateProductStatusRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(productService.updateStatus(id, request, authorization));
    }

    @PostMapping(
            consumes = "application/json",
            produces = "application/json;charset=UTF-8"
    )
    public ResponseEntity<ProductResponse> createProduct(
            @RequestBody CreateProductRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        ProductResponse product = productService.createProduct(request, authorization);
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    @PutMapping(value = "/{id}", consumes = "application/json",
            produces = "application/json;charset=UTF-8")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable("id") long id,
            @RequestBody UpdateProductRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(productService.updateProduct(id, request, authorization));
    }
}
