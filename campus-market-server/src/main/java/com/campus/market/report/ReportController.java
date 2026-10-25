package com.campus.market.report;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.campus.market.report.ReportModels.*;
@RestController
@RequestMapping("/api")
@CrossOrigin(originPatterns={"http://localhost:[*]","http://127.0.0.1:[*]"})
public class ReportController {
    private final ReportService reports;
    public ReportController(ReportService reports){this.reports=reports;}
    @PostMapping(value="/products/{id}/reports",consumes="application/json",produces="application/json;charset=UTF-8")
    public ResponseEntity<Receipt> createReport(@PathVariable long id,@RequestBody Create request,@RequestHeader(name="Authorization",required=false) String auth){var r=reports.create(id,request,auth);return ResponseEntity.status(r.replayed()?200:201).cacheControl(CacheControl.noStore()).body(r);}
    @GetMapping(value="/reports",produces="application/json;charset=UTF-8")
    public ResponseEntity<Page> ownReports(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(defaultValue="") String status,@RequestHeader(name="Authorization",required=false) String auth){return ok(reports.mine(page,size,status,auth));}
    @GetMapping(value="/reports/{id}",produces="application/json;charset=UTF-8")
    public ResponseEntity<Ticket> ownReportDetail(@PathVariable long id,@RequestHeader(name="Authorization",required=false) String auth){return ok(reports.mineDetail(id,auth));}
    @GetMapping(value="/admin/reports",produces="application/json;charset=UTF-8")
    public ResponseEntity<Page> adminReports(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(defaultValue="") String status,@RequestHeader(name="Authorization",required=false) String auth){return ok(reports.adminPage(page,size,status,auth));}
    @GetMapping(value="/admin/reports/{id}",produces="application/json;charset=UTF-8")
    public ResponseEntity<AdminDetail> adminReportDetail(@PathVariable long id,@RequestHeader(name="Authorization",required=false) String auth){return ok(reports.adminDetail(id,auth));}
    @PostMapping(value="/admin/reports/{id}/resolve",consumes="application/json",produces="application/json;charset=UTF-8")
    public ResponseEntity<Receipt> resolveReport(@PathVariable long id,@RequestBody Resolve request,@RequestHeader(name="Authorization",required=false) String auth){return ok(reports.resolve(id,request,auth));}
    private <T> ResponseEntity<T> ok(T data){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);}
}
