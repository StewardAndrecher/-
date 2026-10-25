package com.campus.market.media;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class ImageLibraryController {
    private final ImageLibraryService service;
    public ImageLibraryController(ImageLibraryService service){this.service=service;}
    @GetMapping("/users/me/images") public ResponseEntity<ImageLibraryService.Library> list(@RequestParam(defaultValue="ACTIVE")String scope,@RequestParam(defaultValue="1")int page,@RequestHeader(name="Authorization",required=false)String auth){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.list(scope,page,auth));}
    @PutMapping("/users/me/images/{id}/trash") public ResponseEntity<Void> trash(@PathVariable String id,@RequestBody ImageLibraryService.Change body,@RequestHeader(name="Authorization",required=false)String auth){service.change(id,body,false,auth);return ResponseEntity.noContent().header("Cache-Control","no-store").build();}
    @PutMapping("/users/me/images/{id}/restore") public ResponseEntity<Void> restore(@PathVariable String id,@RequestBody ImageLibraryService.Change body,@RequestHeader(name="Authorization",required=false)String auth){service.change(id,body,true,auth);return ResponseEntity.noContent().header("Cache-Control","no-store").build();}
    @GetMapping("/admin/media/cleanup") public ResponseEntity<ImageLibraryService.Cleanup> preview(@RequestHeader(name="Authorization",required=false)String auth){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.cleanup(auth));}
    @PostMapping("/admin/media/cleanup") public ResponseEntity<java.util.Map<String,Integer>> purge(@RequestBody ImageLibraryService.Purge body,@RequestHeader(name="Authorization",required=false)String auth){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.purge(body,auth));}
}
