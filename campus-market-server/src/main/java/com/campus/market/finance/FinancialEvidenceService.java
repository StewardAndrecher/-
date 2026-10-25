package com.campus.market.finance;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal evidence only. No HTTP callback, payout, real-payment or refund execution endpoint. */
@Service
public class FinancialEvidenceService {
    private final JdbcTemplate jdbc;
    public FinancialEvidenceService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void simulatedPayment(long orderId) {
        var order=jdbc.queryForMap("SELECT id,request_id,price_in_cents_snapshot,payment_kind,paid_at FROM market_orders WHERE id=? FOR UPDATE",orderId);
        if (!"SIMULATED".equals(order.get("payment_kind")) || order.get("paid_at")==null)
            throw new IllegalStateException("Only an already committed-in-this-transaction simulated payment can be recorded");
        var saved=jdbc.queryForList("SELECT id,amount_in_cents,book FROM payment_attempts WHERE order_id=? AND status='SUCCEEDED' FOR UPDATE",orderId);
        String payment;
        if (saved.isEmpty()) {
            payment=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO payment_attempts(id,order_id,request_id,merchant_order_no,channel,merchant_scope,book,amount_in_cents,status,confirmation_source,succeeded_at) VALUES(?,?,?,?,'SIMULATED','DEMO','DEMO',?,'SUCCEEDED','LOCAL_SIMULATION',?)",payment,orderId,order.get("request_id"),"DEMO"+orderId,order.get("price_in_cents_snapshot"),order.get("paid_at"));
        } else {
            var row=saved.get(0);
            if (!"DEMO".equals(row.get("book")) || ((Number)row.get("amount_in_cents")).longValue()!=((Number)order.get("price_in_cents_snapshot")).longValue())
                throw new IllegalStateException("Payment evidence conflicts with order snapshot");
            payment=(String)row.get("id");
        }
        var journal=jdbc.queryForList("SELECT id FROM finance_journals WHERE payment_posting_id=?",payment);
        if (journal.isEmpty()) jdbc.update("INSERT INTO finance_journals(id,payment_id,book,kind,amount_in_cents,debit_account,credit_account) VALUES(?,?,'DEMO','PAYMENT',?,'PROVIDER_CLEARING','SELLER_PENDING')",UUID.randomUUID().toString(),payment,order.get("price_in_cents_snapshot"));
    }

    /** Approval opens an execution attempt, never claims provider processing or money returned. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void approvedRefund(long applicationId) {
        var application=jdbc.queryForMap("SELECT id,order_id,amount_in_cents,status FROM order_refunds WHERE id=? FOR UPDATE",applicationId);
        if (!"APPROVED".equals(application.get("status"))) throw new IllegalStateException("Refund application has not been approved");
        if (!jdbc.queryForList("SELECT id FROM payment_refund_attempts WHERE application_id=?",applicationId).isEmpty()) return;
        var payment=jdbc.queryForMap("SELECT id,channel,merchant_scope,amount_in_cents FROM payment_attempts WHERE order_id=? AND status='SUCCEEDED' FOR UPDATE",application.get("order_id"));
        if (!"SIMULATED".equals(payment.get("channel")) || ((Number)application.get("amount_in_cents")).longValue()>((Number)payment.get("amount_in_cents")).longValue()) throw new IllegalStateException("Refund evidence does not match demo payment");
        jdbc.update("INSERT INTO payment_refund_attempts(id,application_id,order_id,payment_id,merchant_refund_no,channel,merchant_scope,amount_in_cents) VALUES(?,?,?,?,?,'SIMULATED','DEMO',?)",UUID.randomUUID().toString(),applicationId,application.get("order_id"),payment.get("id"),"DEMOREFUND"+applicationId,application.get("amount_in_cents"));
    }
}
