package com.campus.market.publication;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/product-publications")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class PublicationController {
    private final PublicationService publications;
    public PublicationController(PublicationService publications) { this.publications=publications; }
    @PostMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<PublicationResponse> create(@RequestBody CreatePublicationRequest request,
            @RequestHeader(value="Authorization",required=false) String authorization) {
        var receipt=publications.create(request,authorization);
        return ResponseEntity.status(receipt.replayed()?200:201).cacheControl(CacheControl.noStore()).body(receipt);
    }
}
