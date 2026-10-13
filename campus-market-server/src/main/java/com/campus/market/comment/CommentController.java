package com.campus.market.comment;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products/{productId}/comments")
@CrossOrigin(originPatterns = {"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class CommentController {
    private final CommentService service;
    public CommentController(CommentService service) { this.service = service; }

    @GetMapping(produces = "application/json;charset=UTF-8")
    public ResponseEntity<CommentPageResponse> page(
            @PathVariable("productId") long productId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "5") int size,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.page(productId, page, size, authorization));
    }

    @PostMapping(consumes = "application/json", produces = "application/json;charset=UTF-8")
    public ResponseEntity<CommentSubmissionResponse> create(
            @PathVariable("productId") long productId,
            @RequestBody CreateCommentRequest request,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        var result = service.create(productId, request, authorization);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(result);
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> withdraw(
            @PathVariable("productId") long productId,
            @PathVariable("commentId") long commentId,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        service.withdraw(productId, commentId, authorization);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
