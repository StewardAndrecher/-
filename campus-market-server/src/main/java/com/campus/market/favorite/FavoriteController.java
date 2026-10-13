package com.campus.market.favorite;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/favorites")
@CrossOrigin(originPatterns = {"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class FavoriteController {
    private final FavoriteService service;

    public FavoriteController(FavoriteService service) {
        this.service = service;
    }

    @PutMapping(value = "/{productId}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<FavoriteStateResponse> add(
            @PathVariable("productId") long id,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.add(id, authorization));
    }

    @DeleteMapping(value = "/{productId}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<FavoriteStateResponse> remove(
            @PathVariable("productId") long id,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.remove(id, authorization));
    }

    @GetMapping(value = "/ids", produces = "application/json;charset=UTF-8")
    public ResponseEntity<List<Long>> selectedIds(
            @RequestParam(name = "productIds", required = false) List<Long> ids,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.selectedIds(ids, authorization));
    }

    @GetMapping(produces = "application/json;charset=UTF-8")
    public ResponseEntity<FavoritePageResponse> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "5") int size,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(page, size, authorization));
    }
}
