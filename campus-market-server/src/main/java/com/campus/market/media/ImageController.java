package com.campus.market.media;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/images")
@CrossOrigin(originPatterns = {"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class ImageController {
    private final ImageService service;
    public ImageController(ImageService service) { this.service = service; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageResponse> upload(@RequestPart("file") MultipartFile file,
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        return ResponseEntity.status(201).header("Cache-Control", "no-store")
                .body(service.upload(file, authorization));
    }
    @GetMapping(value = "/{id}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<Resource> read(@PathVariable("id") String id) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(service.read(id));
    }
}
