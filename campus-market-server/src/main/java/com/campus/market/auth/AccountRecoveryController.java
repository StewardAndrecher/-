package com.campus.market.auth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/users")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"},exposedHeaders="Retry-After")
public class AccountRecoveryController {
 private final AccountRecoveryService service;public AccountRecoveryController(AccountRecoveryService service){this.service=service;}
 @GetMapping("/me/recovery") public ResponseEntity<AccountRecoveryService.Status> recoveryStatus(@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(auth));}
 @PostMapping(value="/me/recovery",consumes="application/json") public ResponseEntity<AccountRecoveryService.Generated> generateRecoveryCode(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody AccountRecoveryService.Generate request,HttpServletRequest http){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.generate(auth,request,http.getRemoteAddr()));}
 @PostMapping(value="/recover",consumes="application/json") public ResponseEntity<AccountRecoveryService.ResetResult> recoverAccount(@RequestBody AccountRecoveryService.Reset request,HttpServletRequest http){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reset(request,http.getRemoteAddr()));}
}
