package com.campus.market.admin;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.admin.AdminOrderIssueModels.*;

@RestController
@RequestMapping("/api/admin/order-issues")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class AdminOrderIssueController {
    private final AdminOrderIssueService service;
    public AdminOrderIssueController(AdminOrderIssueService service){this.service=service;}
    @GetMapping public ResponseEntity<Page> adminOrderIssues(@RequestParam(required=false)String status,@RequestParam(required=false)String age,@RequestParam(required=false)String orderId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="10")int size,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(status==null?"OPEN":status,age==null?"ALL":age,orderId==null?"":orderId,page,size,auth));}
    @GetMapping("/{orderId}/{applicantId}") public ResponseEntity<Detail> adminOrderIssue(@PathVariable String orderId,@PathVariable String applicantId,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(orderId,applicantId,auth));}
    @PostMapping("/{orderId}/{applicantId}/resolve") public ResponseEntity<Receipt> resolveOrderIssue(@PathVariable String orderId,@PathVariable String applicantId,@RequestBody Resolve request,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resolve(orderId,applicantId,request,auth));}
}
