package com.campus.market.favorite;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/favorites/remove-batch")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class FavoriteBatchController {
    private final FavoriteBatchService service;
    public FavoriteBatchController(FavoriteBatchService service){this.service=service;}
    @PostMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<FavoriteBatchModels.Receipt> removeFavoriteBatch(
            @RequestBody FavoriteBatchModels.Request request,
            @RequestHeader(name="Authorization",required=false)String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache")
                .body(service.remove(request,authorization));
    }
}
