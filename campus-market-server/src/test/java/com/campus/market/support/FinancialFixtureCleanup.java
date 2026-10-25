package com.campus.market.support;
import org.springframework.jdbc.core.JdbcTemplate;
/** Destructive fixture reset is restricted to the owned disposable MySQL runtime. */
public final class FinancialFixtureCleanup {
 public static void clear(JdbcTemplate jdbc){
  String directory=jdbc.queryForObject("SELECT @@datadir",String.class);
  if(directory==null||!directory.contains("campus-lesson24-"))throw new IllegalStateException("Refuse reset outside private test runtime");
  for(String table:java.util.List.of("seller_settlement_items","seller_settlements","finance_reconciliation_lines","finance_reconciliation_runs","finance_journals","payment_callback_receipts","payment_refund_attempts","payment_attempts"))jdbc.update("DELETE FROM "+table);
 }
}
