package com.campus.market.report;
public class ReportException extends RuntimeException {
    private final int status;
    public ReportException(int status,String message){super(message);this.status=status;}
    public int status(){return status;}
    public String code(){return switch(status){case 400->"INVALID_REPORT";case 404->"REPORT_NOT_FOUND";case 409->"REPORT_CONFLICT";default->"REPORT_FORBIDDEN";};}
}
