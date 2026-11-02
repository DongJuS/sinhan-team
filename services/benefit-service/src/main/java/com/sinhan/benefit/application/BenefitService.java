package com.sinhan.benefit.application;

import com.sinhan.benefit.domain.BenefitCalculator;
import com.sinhan.benefit.infrastructure.BenefitRepository;
import com.sinhan.contracts.*;
import com.sinhan.contracts.Models.*;
import com.sinhan.web.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BenefitService {
  private final BenefitRepository repo;
  private final TransactionTemplate tx;
  private final ServiceHttp http;

  public BenefitService(BenefitRepository repo, TransactionTemplate tx, ServiceHttp http) {
    this.repo = repo;
    this.tx = tx;
    this.http = http;
  }

  public QuoteResult quote(QuoteCommand c) {
    Map<?, ?> balances =
        http.get(
            ServiceHttp.env("PAYMENT_URL", "http://payment-service:8080")
                + "/internal/v1/wallets/"
                + c.userId(),
            Map.class);
    List<BenefitQuote> quotes = new ArrayList<>();
    for (var basket : c.baskets())
      for (var rule : repo.rules()) {
        if (!repo.eligible(c.userId(), rule.method())) continue;
        if (rule.method().equals("LOCAL")
            && (!basket.localCurrencyAccepted() || !c.region().equals("SEOUL"))) continue;
        var q =
            BenefitCalculator.calculate(
                basket,
                rule,
                rule.monthlyCap() - repo.used(c.userId(), rule.method(), c.excludeExecutionId()),
                Instant.now().plusSeconds(300));
        Object balance = balances.get(rule.method());
        if (balance instanceof Number n && n.longValue() >= q.chargeKrw()) quotes.add(q);
      }
    return new QuoteResult(quotes);
  }

  public void reserve(ExecutionCommand c) {
    var p = c.proposal();
    if (!c.executionId().equals(p.executionId()))
      throw new Problem(409, "EXECUTION_MISMATCH", "실행 ID가 다릅니다.");
    // Remote balance lookup is deliberately outside the database transaction.
    var available =
        quote(
            new QuoteCommand(p.userId(), p.region(), List.of(p.quote().basket()), c.executionId()));
    if (available.quotes().stream().noneMatch(q -> equivalent(q, p.quote())))
      throw new Problem(409, "QUOTE_CHANGED", "혜택 또는 잔액이 변경되었습니다.");
    tx.executeWithoutResult(
        s -> {
          repo.lock(p.userId());
          var existing = repo.reservation(c.executionId());
          if (existing != null) {
            if (!existing.get("digest").equals(Canonical.hash(p))
                || existing.get("status").equals("RELEASED"))
              throw new Problem(409, "RESERVATION_CONFLICT", "혜택 예약을 사용할 수 없습니다.");
            return;
          }
          if (!p.expiresAt().isAfter(Instant.now()))
            throw new Problem(409, "PROPOSAL_EXPIRED", "주문안이 만료되었습니다.");
          var rule =
              repo.rules().stream()
                  .filter(r -> r.method().equals(p.quote().paymentMethod()))
                  .findFirst()
                  .orElseThrow();
          var current =
              BenefitCalculator.calculate(
                  p.quote().basket(),
                  rule,
                  rule.monthlyCap() - repo.used(p.userId(), rule.method(), c.executionId()),
                  p.quote().expiresAt());
          if (!equivalent(current, p.quote()))
            throw new Problem(409, "QUOTE_CHANGED", "혜택 한도가 변경되었습니다.");
          repo.reserve(
              c.executionId(),
              p.userId(),
              rule.method(),
              current.totalBenefitKrw(),
              Canonical.hash(p));
        });
  }

  public void status(String executionId, String status) {
    tx.executeWithoutResult(s -> repo.status(executionId, status));
  }

  public static boolean equivalent(BenefitQuote a, BenefitQuote b) {
    return a.paymentMethod().equals(b.paymentMethod())
        && a.ruleVersion().equals(b.ruleVersion())
        && a.chargeKrw() == b.chargeKrw()
        && a.instantDiscountKrw() == b.instantDiscountKrw()
        && a.statementDiscountKrw() == b.statementDiscountKrw()
        && a.cashbackKrw() == b.cashbackKrw()
        && a.pointsEarned() == b.pointsEarned()
        && a.pointValueKrw() == b.pointValueKrw()
        && a.effectiveCostKrw() == b.effectiveCostKrw()
        && Canonical.hash(a.basket()).equals(Canonical.hash(b.basket()));
  }
}
