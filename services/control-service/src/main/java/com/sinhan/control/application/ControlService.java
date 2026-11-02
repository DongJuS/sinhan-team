package com.sinhan.control.application;

import com.sinhan.contracts.*;
import com.sinhan.contracts.Models.*;
import com.sinhan.control.domain.Policy;
import com.sinhan.control.infrastructure.ApprovalRepository;
import com.sinhan.web.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ControlService {
  private final ApprovalRepository repo;
  private final TransactionTemplate tx;
  private final ServiceHttp http;

  public ControlService(ApprovalRepository r, TransactionTemplate t, ServiceHttp h) {
    repo = r;
    tx = t;
    http = h;
  }

  public void verify(Proposal p) {
    String violation = Policy.violation(p);
    if (violation != null) throw new Problem(409, violation, "주문 조건을 다시 확인해야 합니다.");
    Basket actual =
        http.post(
            ServiceHttp.env("MERCHANT_URL", "http://merchant-simulator:8000")
                + "/internal/v1/baskets/validate",
            p.quote().basket(),
            Basket.class);
    if (!Canonical.hash(actual).equals(Canonical.hash(p.quote().basket())))
      throw new Problem(409, "PRICE_CHANGED", "가격 또는 메뉴가 변경되었습니다.");
    var available =
        http.post(
            ServiceHttp.env("BENEFIT_URL", "http://benefit-service:8080")
                + "/internal/v1/benefit-quotes",
            new QuoteCommand(p.userId(), p.region(), List.of(actual), p.executionId()),
            QuoteResult.class);
    boolean match = available.quotes().stream().anyMatch(q -> sameQuote(q, p.quote()));
    if (!match) throw new Problem(409, "QUOTE_CHANGED", "결제 혜택 또는 잔액이 변경되었습니다.");
  }

  private boolean sameQuote(BenefitQuote a, BenefitQuote b) {
    return a.paymentMethod().equals(b.paymentMethod())
        && a.ruleVersion().equals(b.ruleVersion())
        && a.instantDiscountKrw() == b.instantDiscountKrw()
        && a.chargeKrw() == b.chargeKrw()
        && a.statementDiscountKrw() == b.statementDiscountKrw()
        && a.cashbackKrw() == b.cashbackKrw()
        && a.pointsEarned() == b.pointsEarned()
        && a.pointValueKrw() == b.pointValueKrw()
        && a.effectiveCostKrw() == b.effectiveCostKrw();
  }

  public Grant prepare(Proposal p) {
    verify(p);
    return tx.execute(
        s -> {
          repo.lock(p.id());
          var existing = repo.get(p.id());
          if (existing != null && !existing.get("digest").equals(Canonical.hash(p)))
            throw new Problem(409, "PROPOSAL_CONFLICT", "주문안은 변경할 수 없습니다.");
          repo.prepare(p.id(), p.userId(), p.executionId(), Canonical.json(p), Canonical.hash(p));
          return grant(repo.get(p.id()));
        });
  }

  public Grant approve(String id, int version, String user) {
    var row = required(id);
    var p = Canonical.read(row.get("payload").toString(), Proposal.class);
    if (!p.userId().equals(user)) throw new Problem(403, "NOT_OWNER", "주문 소유자만 승인할 수 있습니다.");
    if (version != p.version()) throw new Problem(409, "STALE_VERSION", "최신 주문안을 확인해주세요.");
    if ("REVOKED".equals(row.get("status")))
      throw new Problem(409, "APPROVAL_REVOKED", "이 주문안은 취소되었습니다.");
    if (Set.of("APPROVED", "CLAIMED").contains(row.get("status"))) return grant(row);
    verify(p);
    return tx.execute(
        s -> {
          repo.lock(id);
          var current = required(id);
          String status = current.get("status").toString();
          if (status.equals("REVOKED"))
            throw new Problem(409, "APPROVAL_REVOKED", "이 주문안은 취소되었습니다.");
          if (status.equals("PREPARED")) {
            repo.status(id, "APPROVED");
            repo.audit(id, "USER_APPROVED");
          }
          return grant(required(id));
        });
  }

  public Grant check(ExecutionCommand c, boolean claim) {
    return tx.execute(
        s -> {
          var p = c.proposal();
          repo.lock(p.id());
          var r = required(p.id());
          if (!r.get("digest").equals(Canonical.hash(p))
              || !r.get("execution_id").equals(c.executionId()))
            throw new Problem(409, "APPROVAL_MISMATCH", "승인한 주문안과 다릅니다.");
          if (!p.expiresAt().isAfter(Instant.now()))
            throw new Problem(409, "PROPOSAL_EXPIRED", "승인이 만료되었습니다.");
          String state = r.get("status").toString();
          if (state.equals("REVOKED"))
            throw new Problem(409, "APPROVAL_REVOKED", "사용자 승인이 철회되었습니다.");
          if (claim && state.equals("APPROVED")) {
            repo.status(p.id(), "CLAIMED");
            repo.audit(p.id(), "EXECUTION_CLAIMED");
            state = "CLAIMED";
          }
          if (!state.equals("CLAIMED"))
            throw new Problem(403, "APPROVAL_REQUIRED", "유효한 사용자 승인이 필요합니다.");
          return grant(required(p.id()));
        });
  }

  public void revoke(String id) {
    tx.executeWithoutResult(
        s -> {
          repo.lock(id);
          if (repo.get(id) != null) {
            repo.status(id, "REVOKED");
            repo.audit(id, "REVOKED");
          }
        });
  }

  private Map<String, Object> required(String id) {
    var r = repo.get(id);
    if (r == null) throw new Problem(404, "APPROVAL_NOT_FOUND", "승인 정보를 찾을 수 없습니다.");
    return r;
  }

  private Grant grant(Map<String, Object> r) {
    var p = Canonical.read(r.get("payload").toString(), Proposal.class);
    return new Grant(
        p.id(),
        p.executionId(),
        r.get("digest").toString(),
        r.get("status").toString(),
        p.expiresAt());
  }
}
