package com.campus.market.draft;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import static com.campus.market.draft.DraftBackupModels.*;

@RestController
@RequestMapping("/api/product-draft-backups")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class DraftBackupController {
 private final DraftBackupService service;
 public DraftBackupController(DraftBackupService service) { this.service=service; }
 @GetMapping
 public ResponseEntity<List<Backup>> list(@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(auth));
 }
 @GetMapping("/page")
 public ResponseEntity<Page> page(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="3") int size,
         @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="UPDATED_DESC") String sort,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(page,size,keyword,category,sort,auth));
 }
 @GetMapping("/{id}")
 public ResponseEntity<Backup> read(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String auth) {
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(id,auth));
 }
 @PutMapping("/{id}")
 public ResponseEntity<Backup> save(@PathVariable String id,@RequestBody SaveRequest request,@RequestHeader(value="Authorization",required=false) String auth) {
  var result=service.save(id,request,auth);
  return ResponseEntity.status(result.created()?201:200).cacheControl(CacheControl.noStore()).body(result.backup());
 }
 @DeleteMapping("/{id}")
 public ResponseEntity<Void> delete(@PathVariable String id,@RequestParam Long version,@RequestHeader(value="Authorization",required=false) String auth) {
  service.delete(id,version,auth);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
 }
}
