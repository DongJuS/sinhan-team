package com.sinhan.order.application;

import com.sinhan.contracts.Models.*;
import com.sinhan.order.domain.OrderState;
import com.sinhan.order.infrastructure.OrderRepository;
import com.sinhan.web.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
  private final OrderRepository repo;
  private final ServiceHttp http;

  public OrderService(OrderRepository r, ServiceHttp h) {
    repo = r;
    http = h;
  }

  public OrderState create(String user, String key, PurchaseInput input) {
    if (key == null || !key.matches("[A-Za-z0-9-]{8,100}"))
      throw new Problem(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key가 필요합니다.");
    return repo.create(user, key, input);
  }

  public OrderState get(String id, String user) {
    var r = repo.get(id);
    if (!r.userId().equals(user)) throw new Problem(404, "REQUEST_NOT_FOUND", "구매 요청을 찾을 수 없습니다.");
    return r;
  }

  public OrderState approve(String id, int version, String user, String token) {
    var p = repo.proposal(id);
    var r = get(p.requestId(), user);
    if (!p.userId().equals(user)) throw new Problem(403, "NOT_OWNER", "주문 소유자만 승인할 수 있습니다.");
    if (r.proposal() == null || !r.proposal().id().equals(id) || p.version() != version)
      throw new Problem(409, "STALE_PROPOSAL", "최신 주문안을 승인해주세요.");
    if (Set.of("EXECUTING", "RECOVERY_PENDING", "COMPLETED", "FAILED_REFUNDED")
        .contains(r.status())) return r;
    try {
      http.postAs(
          ServiceHttp.env("CONTROL_URL", "http://control-service:8080")
              + "/internal/v1/approvals/"
              + id,
          new ApprovalInput(version),
          Grant.class,
          token);
      repo.approved(p);
    } catch (Problem e) {
      if (Set.of("PROPOSAL_EXPIRED", "PRICE_CHANGED", "OUT_OF_STOCK", "QUOTE_CHANGED")
          .contains(e.code)) {
        http.post(
            ServiceHttp.env("CONTROL_URL", "http://control-service:8080")
                + "/internal/v1/approvals/"
                + id
                + "/revoke",
            Map.of(),
            Map.class);
        repo.refresh(p, e.code);
      }
      throw e;
    }
    return get(p.requestId(), user);
  }
}
