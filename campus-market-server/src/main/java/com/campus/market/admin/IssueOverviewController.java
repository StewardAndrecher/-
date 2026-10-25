package com.campus.market.admin;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/order-issues/overview")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class IssueOverviewController {
    private final IssueOverviewService service;
    public IssueOverviewController(IssueOverviewService service){this.service=service;}
    @GetMapping public ResponseEntity<IssueOverviewService.IssueOverview> issueOverview(@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sample(auth));}
}
