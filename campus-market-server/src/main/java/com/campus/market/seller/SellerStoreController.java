package com.campus.market.seller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sellers")
@CrossOrigin(originPatterns={"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class SellerStoreController {
    private final SellerStoreService stores;
    public SellerStoreController(SellerStoreService stores) { this.stores = stores; }

    @GetMapping(value="/{id}/products", produces="application/json;charset=UTF-8")
    public ResponseEntity<SellerStoreResponse> getStore(
            @PathVariable("id") long id,
            @RequestParam(name="page", defaultValue="1") int page,
            @RequestParam(name="size", defaultValue="5") int size,
            @RequestParam(name="sort", defaultValue="NEWEST") String sort) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(stores.getStore(id, page, size, sort));
    }
}
