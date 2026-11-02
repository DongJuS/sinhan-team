package com.sinhan.control;

import static org.junit.jupiter.api.Assertions.*;

import com.sinhan.contracts.Canonical;
import com.sinhan.contracts.Models.*;
import com.sinhan.control.application.ControlService;
import com.sinhan.control.infrastructure.ApprovalRepository;
import com.sinhan.web.Problem;
import com.sinhan.web.ServiceHttp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

class RevokedApprovalTest {
  @Test
  void revokedApprovalIsRejectedBeforeAnyRemoteLookup() {
    var basket =
        new Basket(
            "m",
            "가게",
            List.of(new LineItem("i", "메뉴", 1, 10000, Map.of(), List.of())),
            0,
            20,
            "1",
            true);
    var expiry = Instant.now().plusSeconds(60);
    var quote = new BenefitQuote(basket, "CARD_A", "1", 0, 10000, 0, 0, 0, 0, 10000, expiry);
    var proposal =
        new Proposal(
            "p",
            "r",
            "e",
            "u",
            1,
            "주소",
            "SEOUL",
            new Constraints(20000, 1, List.of(), null),
            quote,
            "설명",
            expiry);
    var repository =
        new ApprovalRepository(null) {
          @Override
          public Map<String, Object> get(String id) {
            return Map.of("payload", Canonical.json(proposal), "status", "REVOKED");
          }
        };
    var unavailableRemote =
        new ServiceHttp(RestClient.builder()) {
          @Override
          public <T> T post(String url, Object body, Class<T> type) {
            throw new AssertionError("Revoked approvals must not consult remote services");
          }
        };
    var service = new ControlService(repository, new TransactionTemplate(), unavailableRemote);
    var error = assertThrows(Problem.class, () -> service.approve("p", 1, "u"));
    assertEquals(409, error.status);
    assertEquals("APPROVAL_REVOKED", error.code);
  }
}
