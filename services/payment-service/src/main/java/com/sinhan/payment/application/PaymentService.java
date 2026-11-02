package com.sinhan.payment.application;

import com.sinhan.contracts.*;
import com.sinhan.contracts.Models.*;
import com.sinhan.payment.infrastructure.PaymentRepository;
import com.sinhan.web.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {
  private final PaymentRepository repo;
  private final TransactionTemplate tx;
  private final ServiceHttp http;

  public PaymentService(PaymentRepository r, TransactionTemplate t, ServiceHttp h) {
    repo = r;
    tx = t;
    http = h;
  }

  public PaymentResult charge(ExecutionCommand c) {
    if (!c.executionId().equals(c.proposal().executionId()))
      throw new Problem(409, "EXECUTION_MISMATCH", "실행 ID가 다릅니다.");
    var existing = repo.transaction(c.executionId());
    if (existing != null) return checked(existing, c);
    http.post(
        ServiceHttp.env("CONTROL_URL", "http://control-service:8080")
            + "/internal/v1/authorizations/check",
        c,
        Grant.class);
    var result =
        tx.execute(
            s -> {
              repo.lock(c.executionId());
              var previous = repo.transaction(c.executionId());
              if (previous != null) return checked(previous, c);
              var p = c.proposal();
              int amount = p.quote().chargeKrw();
              if (amount < 0) throw new Problem(400, "INVALID_AMOUNT", "잘못된 금액입니다.");
              if (!repo.debit(p.userId(), p.quote().paymentMethod(), amount))
                throw new Problem(409, "INSUFFICIENT_BALANCE", "결제 가능 잔액이 부족합니다.");
              repo.record(
                  c.executionId(),
                  p.userId(),
                  p.quote().paymentMethod(),
                  amount,
                  Canonical.hash(p));
              return result(repo.transaction(c.executionId()));
            });
    // Simulate a committed transaction whose response is lost. Reconciliation must query it.
    if (repo.takeFault("charge_response_lost"))
      throw new Problem(503, "SIMULATED_RESPONSE_LOSS", "결제 응답을 확인할 수 없습니다.");
    return result;
  }

  public PaymentResult refund(String id) {
    if (repo.takeFault("refund_unavailable"))
      throw new Problem(503, "REFUND_UNAVAILABLE", "환불 결과를 확인하고 있습니다.");
    var result =
        tx.execute(
            s -> {
              repo.lock(id);
              var row = required(id);
              if (row.get("status").equals("PAID")) repo.refund(row);
              return result(required(id));
            });
    if (repo.takeFault("refund_response_lost"))
      throw new Problem(503, "SIMULATED_RESPONSE_LOSS", "환불 응답을 확인할 수 없습니다.");
    return result;
  }

  public PaymentResult lookup(String id) {
    return result(required(id));
  }

  public Map<String, Integer> balances(String user) {
    return repo.balances(user);
  }

  public void fault(String name, int count) {
    if (!"true".equals(System.getenv("ENABLE_FAULTS")))
      throw new Problem(404, "NOT_FOUND", "찾을 수 없습니다.");
    if (!Set.of("charge_response_lost", "refund_unavailable", "refund_response_lost").contains(name)
        || count < 0
        || count > 100) throw new Problem(400, "INVALID_FAULT", "잘못된 시나리오입니다.");
    repo.fault(name, count);
  }

  public List<Map<String, Object>> ledger() {
    return repo.ledger();
  }

  private Map<String, Object> required(String id) {
    var row = repo.transaction(id);
    if (row == null) throw new Problem(404, "PAYMENT_NOT_FOUND", "거래 내역이 없습니다.");
    return row;
  }

  private PaymentResult checked(Map<String, Object> row, ExecutionCommand c) {
    if (!row.get("digest").equals(Canonical.hash(c.proposal())))
      throw new Problem(409, "IDEMPOTENCY_CONFLICT", "같은 실행 ID에 다른 결제 요청입니다.");
    return result(row);
  }

  private PaymentResult result(Map<String, Object> r) {
    return new PaymentResult(
        r.get("execution_id").toString(),
        r.get("status").toString(),
        ((Number) r.get("amount_krw")).intValue(),
        r.get("method").toString());
  }
}
