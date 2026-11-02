package com.sinhan.order.application;

import com.sinhan.contracts.*;
import com.sinhan.contracts.Models.*;
import com.sinhan.order.domain.*;
import com.sinhan.order.infrastructure.OrderRepository;
import com.sinhan.order.infrastructure.OrderRepository.Job;
import com.sinhan.web.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Workflow {
  private final OrderRepository repo;
  private final ServiceHttp http;
  private final String agent = ServiceHttp.env("AGENT_URL", "http://agent-service:8000"),
      benefit = ServiceHttp.env("BENEFIT_URL", "http://benefit-service:8080"),
      control = ServiceHttp.env("CONTROL_URL", "http://control-service:8080"),
      payment = ServiceHttp.env("PAYMENT_URL", "http://payment-service:8080"),
      merchant = ServiceHttp.env("MERCHANT_URL", "http://merchant-simulator:8000");

  public Workflow(OrderRepository r, ServiceHttp h) {
    repo = r;
    http = h;
  }

  public void run(Job job) {
    try {
      if (job.kind().equals("PLAN")) plan(job);
      else execute(job);
    } catch (IllegalStateException e) {
      if (!"LEASE_LOST".equals(e.getMessage())) throw e;
    } catch (Exception e) {
      String code = e instanceof Problem p ? p.code : "DEPENDENCY_UNAVAILABLE";
      var state = repo.get(job.requestId());
      if (job.kind().equals("PLAN") && job.attempts() >= 5)
        repo.finish(
            job, state.progress("FAILED", "SEARCH", "추천 서비스를 사용할 수 없습니다. 다시 요청해주세요.", code));
      else if (job.kind().equals("EXECUTE")
          && Set.of("START", "PAYMENT_PENDING").contains(state.stage())
          && Set.of(
                  "PRICE_CHANGED",
                  "OUT_OF_STOCK",
                  "QUOTE_CHANGED",
                  "PROPOSAL_EXPIRED",
                  "INSUFFICIENT_BALANCE",
                  "APPROVAL_REVOKED")
              .contains(code)) {
        repo.change(
            job, r -> r.progress("RECOVERY_PENDING", "REPLAN_CLEANUP", "변경된 주문을 정리하고 있습니다.", code));
        repo.retry(job, "RECOVERY_PENDING", "변경된 조건을 확인하고 있습니다.", code);
      } else
        repo.retry(
            job,
            job.kind().equals("PLAN") ? "RETRYING" : "RECOVERY_PENDING",
            "처리 결과를 확인하고 안전하게 이어서 진행합니다.",
            code);
    }
  }

  private void plan(Job j) {
    var r = repo.get(j.requestId());
    if (r.revision() >= 5) {
      repo.finish(
          j, r.progress("NEEDS_INPUT", "INPUT", "조건이 반복해서 변경되었습니다. 새 요청을 입력해주세요.", "REPLAN_LIMIT"));
      return;
    }
    var result =
        http.postAgent(
            agent + "/internal/v1/plans",
            new SearchCommand(r.id(), r.input(), List.of()),
            SearchResult.class);
    if (!result.status().equals("OK")) {
      repo.finish(j, r.progress("NEEDS_INPUT", "INPUT", result.message(), result.status()));
      return;
    }
    repo.change(j, s -> s.progress("COMPARING", "BENEFITS", "카드·지역화폐 혜택을 비교하고 있습니다.", null));
    List<Basket> actual = new ArrayList<>();
    for (var candidate : result.candidates()) {
      try {
        actual.add(http.post(merchant + "/internal/v1/baskets/validate", candidate, Basket.class));
      } catch (Problem e) {
        if (!Set.of("OUT_OF_STOCK", "PRICE_CHANGED", "INVALID_BASKET").contains(e.code)) throw e;
      }
    }
    if (actual.isEmpty()) {
      repo.finish(
          j, r.progress("NEEDS_INPUT", "INPUT", "주문 가능한 메뉴가 없습니다. 조건을 바꿔주세요.", "NO_CANDIDATES"));
      return;
    }
    var quotes =
        http.post(
            benefit + "/internal/v1/benefit-quotes",
            new QuoteCommand(r.userId(), r.input().region(), actual, null),
            QuoteResult.class);
    var eligible = Selection.eligible(quotes.quotes(), result.constraints());
    if (eligible.isEmpty()) {
      repo.finish(
          j, r.progress("NEEDS_INPUT", "INPUT", "예산과 결제 조건을 만족하는 후보가 없습니다.", "NO_CANDIDATES"));
      return;
    }
    var best = eligible.getFirst();
    var explained =
        http.postAgent(
            agent + "/internal/v1/explanations",
            Map.of("quote", best, "evaluatedCandidates", eligible.size()),
            Map.class);
    String explanation = explained.get("text").toString();
    var p =
        new Proposal(
            UUID.randomUUID().toString(),
            r.id(),
            UUID.randomUUID().toString(),
            r.userId(),
            r.revision() + 1,
            r.input().address(),
            r.input().region(),
            result.constraints(),
            best,
            explanation,
            best.expiresAt());
    http.post(control + "/internal/v1/validations", p, Grant.class);
    repo.planned(j, r.planned(result.constraints(), eligible, p));
  }

  private void execute(Job j) {
    var r = repo.get(j.requestId());
    var p = r.proposal();
    var command = new ExecutionCommand(p.executionId(), p);
    if (r.stage().equals("REPLAN_CLEANUP")) {
      // A previous charge may have committed before the response was lost. Never replan until
      // reconciled.
      var paid = findPayment(p.executionId());
      if (paid != null && paid.status().equals("PAID")) {
        repo.change(
            j,
            s ->
                s.progress(
                    "RECOVERY_PENDING", "REFUND_PENDING", "기존 결제를 환불하고 있습니다.", s.errorCode()));
        refund(j, p);
        return;
      }
      cleanup(p);
      http.post(control + "/internal/v1/approvals/" + p.id() + "/revoke", Map.of(), Map.class);
      repo.replan(j, repo.get(r.id()));
      return;
    }
    if (r.stage().equals("REFUND_PENDING")) {
      refund(j, p);
      return;
    }
    if (r.stage().equals("START")) {
      http.post(control + "/internal/v1/authorizations/claim", command, Grant.class);
      http.post(benefit + "/internal/v1/reservations", command, Map.class);
      http.post(merchant + "/internal/v1/reservations", command, MerchantResult.class);
      r =
          repo.change(
              j, s -> s.progress("EXECUTING", "PAYMENT_PENDING", "승인한 금액으로 결제하고 있습니다.", null));
    }
    if (r.stage().equals("PAYMENT_PENDING")) {
      var transaction = findPayment(p.executionId());
      if (transaction == null)
        transaction = http.post(payment + "/internal/v1/payments", command, PaymentResult.class);
      if (transaction.status().equals("REFUNDED")) {
        repo.change(
            j, s -> s.progress("RECOVERY_PENDING", "REFUND_PENDING", "환불 결과를 정리하고 있습니다.", null));
        refund(j, p);
        return;
      }
      if (!transaction.status().equals("PAID"))
        throw new Problem(503, "PAYMENT_UNKNOWN", "결제 결과 확인 중입니다.");
      r = repo.change(j, s -> s.progress("EXECUTING", "CONFIRMING", "가맹점 주문을 확정하고 있습니다.", null));
    }
    if (r.stage().equals("CONFIRMING")) {
      try {
        var confirmed =
            http.post(
                merchant + "/internal/v1/reservations/" + p.executionId() + "/confirm",
                Map.of(),
                MerchantResult.class);
        if (!confirmed.status().equals("CONFIRMED"))
          throw new Problem(503, "MERCHANT_UNKNOWN", "주문 확정 확인 중입니다.");
      } catch (Problem e) {
        if (e.code.equals("MERCHANT_REJECTED")) {
          repo.change(
              j,
              s -> s.progress("RECOVERY_PENDING", "REFUND_PENDING", "가맹점 주문이 거절되어 환불합니다.", e.code));
          refund(j, p);
          return;
        }
        throw e;
      }
      http.post(
          benefit + "/internal/v1/reservations/" + p.executionId() + "/commit",
          Map.of(),
          Map.class);
      repo.finish(j, repo.get(r.id()).progress("COMPLETED", "DONE", "주문과 모의 결제가 완료되었습니다.", null));
      return;
    }
    if (Set.of("DONE", "REFUNDED").contains(r.stage())) repo.complete(j);
  }

  private void refund(Job j, Proposal p) {
    var result = findPayment(p.executionId());
    if (result == null) throw new Problem(503, "PAYMENT_UNKNOWN", "환불할 거래를 확인하고 있습니다.");
    if (!result.status().equals("REFUNDED"))
      http.post(
          payment + "/internal/v1/payments/" + p.executionId() + "/refund",
          Map.of(),
          PaymentResult.class);
    cleanup(p);
    http.post(control + "/internal/v1/approvals/" + p.id() + "/revoke", Map.of(), Map.class);
    repo.finish(
        j,
        repo.get(j.requestId())
            .progress(
                "FAILED_REFUNDED", "REFUNDED", "주문을 완료하지 못해 결제 금액을 환불했습니다.", "ORDER_CANCELLED"));
  }

  private void cleanup(Proposal p) {
    http.post(
        merchant + "/internal/v1/reservations/" + p.executionId() + "/release",
        Map.of(),
        MerchantResult.class);
    http.post(
        benefit + "/internal/v1/reservations/" + p.executionId() + "/release", Map.of(), Map.class);
  }

  private PaymentResult findPayment(String id) {
    try {
      return http.get(payment + "/internal/v1/payments/" + id, PaymentResult.class);
    } catch (Problem e) {
      if (e.status == 404 && e.code.equals("PAYMENT_NOT_FOUND")) return null;
      throw e;
    }
  }
}
