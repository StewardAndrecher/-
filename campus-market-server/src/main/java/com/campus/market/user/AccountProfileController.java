package com.campus.market.user;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/users/me/profile")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class AccountProfileController {
 private final AccountProfileService service;public AccountProfileController(AccountProfileService service){this.service=service;}
 @GetMapping public ResponseEntity<AccountProfileService.Profile> accountProfile(@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(auth));}
 @PutMapping(consumes="application/json") public ResponseEntity<AccountProfileService.Profile> updateAccountProfile(@RequestBody AccountProfileService.Update request,@RequestHeader(value="Authorization",required=false)String auth){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(request,auth));}
}
