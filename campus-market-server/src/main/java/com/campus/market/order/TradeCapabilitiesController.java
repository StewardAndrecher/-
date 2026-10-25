package com.campus.market.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public operational capability, not a payment credential or a successful-payment signal. */
@RestController
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class TradeCapabilitiesController {
    private final boolean simulation;
    public TradeCapabilitiesController(@Value("${market.orders.simulated-payment-enabled:false}") boolean simulation) {
        this.simulation = simulation;
    }
    public record Capabilities(String paymentMode, boolean orderCreationEnabled,
                               boolean simulatedPaymentEnabled, String message) {}
    @GetMapping("/api/trade-capabilities")
    public ResponseEntity<Capabilities> capabilities() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(simulation
            ? new Capabilities("SIMULATED", true, true, "教学演示交易，不发生真实扣款")
            : new Capabilities("UNAVAILABLE", false, false, "在线支付尚未开通，暂不接受新订单；你仍可浏览商品并查看已有订单"));
    }
}
