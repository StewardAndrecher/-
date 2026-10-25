package com.campus.market.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/orders")
@CrossOrigin(originPatterns={"http://localhost:[*]", "http://127.0.0.1:[*]"})
public class AdminOrderController {
    private final AdminOrderOverviewService orders;
    private final AdminOrderQueryService query;
    private final AdminOrderDetailService details;
    public AdminOrderController(AdminOrderOverviewService orders, AdminOrderQueryService query, AdminOrderDetailService details) {
        this.orders = orders; this.query = query; this.details = details;
    }
    @GetMapping(value="/{id}", produces="application/json;charset=UTF-8")
    public ResponseEntity<AdminOrderDetailService.Detail> adminOrderDetail(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(details.detail(id));
    }
    @GetMapping(produces="application/json;charset=UTF-8")
    public ResponseEntity<AdminOrderQueryService.Page> adminOrders(
            @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="") String orderId,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.page(status,orderId,page,size));
    }
    @GetMapping(value="/overview", produces="application/json;charset=UTF-8")
    public ResponseEntity<AdminOrderOverviewService.Overview> orderOverview() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.overview());
    }
}
