package com.campus.market.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class SessionListController {
    private final SessionListService service;
    public SessionListController(SessionListService service){this.service=service;}
    @GetMapping(value="/api/users/me/sessions/active",produces="application/json;charset=UTF-8")
    public ResponseEntity<SessionListService.Page> list(
            @RequestHeader(value="Authorization",required=false)String authorization,
            @RequestParam MultiValueMap<String,String> parameters) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache")
                .body(service.list(authorization,parameters));
    }
}
