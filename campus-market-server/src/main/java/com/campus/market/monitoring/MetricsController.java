package com.campus.market.monitoring;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="market.monitoring.enabled",havingValue="true")
public class MetricsController {
 private final PrometheusMeterRegistry registry;private final byte[] token;
 public MetricsController(PrometheusMeterRegistry registry,@Value("${market.monitoring.token:}")String token){
  if(!token.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("启用监控需要独立的64位十六进制MONITORING_TOKEN");
  this.registry=registry;this.token=token.getBytes(StandardCharsets.UTF_8);
 }
 @GetMapping("/api/ops/metrics")
 public ResponseEntity<String> scrape(HttpServletRequest request){
  var authorization=Collections.list(request.getHeaders("Authorization"));
  String header=authorization.size()==1?authorization.get(0):"";
  boolean valid=header.startsWith("Bearer ")&&header.length()==71&&MessageDigest.isEqual(token,header.substring(7).getBytes(StandardCharsets.UTF_8));
  if(!valid)return response(401).header("WWW-Authenticate","Bearer").body("Unauthorized");
  if(request.getHeader("Origin")!=null)return response(403).body("Forbidden");
  if(request.getQueryString()!=null)return response(400).body("Query parameters are not supported");
  return response(200).contentType(MediaType.parseMediaType("text/plain;version=0.0.4;charset=utf-8")).body(registry.scrape());
 }
 private ResponseEntity.BodyBuilder response(int status){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma","no-cache").header("Vary","Authorization");}
}
