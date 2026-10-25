package com.campus.market.auth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/users")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class EmailBindingController {
    private final EmailBindingService service;
    public EmailBindingController(EmailBindingService service){this.service=service;}
    @GetMapping("/me/email") public ResponseEntity<EmailBindingService.Status> status(@RequestHeader(name="Authorization",required=false)String auth){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.status(auth));}
    @PostMapping("/email/challenges") public ResponseEntity<EmailBindingService.Challenge> challenge(@RequestBody EmailBindingService.Start body,@RequestHeader(name="Authorization",required=false)String auth,HttpServletRequest request){return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.start(body,auth,request.getRemoteAddr()));}
    @PostMapping("/me/email/confirm") public ResponseEntity<EmailBindingService.Status> confirm(@RequestBody EmailBindingService.Confirm body,@RequestHeader(name="Authorization",required=false)String auth,HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.confirm(body,auth,request.getRemoteAddr()));}
    @PostMapping("/recover-by-email") public ResponseEntity<AccountRecoveryService.ResetResult> recover(@RequestBody EmailBindingService.Reset body,HttpServletRequest request){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.recover(body,request.getRemoteAddr()));}
    @GetMapping("/email/capabilities") public ResponseEntity<java.util.Map<String,Boolean>> capability(){return ResponseEntity.ok().header("Cache-Control","no-store").body(java.util.Map.of("emailDeliveryAvailable",service.deliveryAvailable()));}
}
