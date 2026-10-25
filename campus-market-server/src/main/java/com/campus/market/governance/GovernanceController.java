package com.campus.market.governance;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class GovernanceController {
    private final GovernanceService service;
    public GovernanceController(GovernanceService service){this.service=service;}
    @GetMapping("/users/me/content-status") public ResponseEntity<GovernanceService.State> mine(@RequestHeader(name="Authorization",required=false)String auth){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.mine(auth));}
    @PostMapping("/users/me/content-status/appeal") public ResponseEntity<GovernanceService.State> appeal(@RequestBody GovernanceService.Change body,@RequestHeader(name="Authorization",required=false)String auth){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.appeal(body,auth));}
    @GetMapping("/admin/governance/account") public ResponseEntity<GovernanceService.State> lookup(@RequestParam String username,@RequestHeader(name="Authorization",required=false)String auth){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.lookup(username,auth));}
    @PutMapping("/admin/governance/accounts/{id}") public ResponseEntity<GovernanceService.State> change(@PathVariable long id,@RequestBody GovernanceService.Change request,@RequestHeader(name="Authorization",required=false)String auth){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.change(id,request,auth));}
}
