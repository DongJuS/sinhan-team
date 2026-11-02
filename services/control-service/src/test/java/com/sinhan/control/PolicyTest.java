package com.sinhan.control;

import static org.junit.jupiter.api.Assertions.*;

import com.sinhan.contracts.Models.*;
import com.sinhan.control.domain.Policy;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class PolicyTest {
  Proposal proposal(int charge, int budget, List<String> excluded, Instant expiry) {
    var b =
        new Basket(
            "m",
            "가게",
            List.of(new LineItem("i", "메뉴", 1, 10000, Map.of(), List.of("땅콩"))),
            0,
            20,
            "1",
            true);
    var q = new BenefitQuote(b, "CARD", "1", 0, charge, 0, 0, 0, 0, charge, expiry);
    return new Proposal(
        "p",
        "r",
        "e",
        "u",
        1,
        "주소",
        "SEOUL",
        new Constraints(budget, 1, excluded, null),
        q,
        "설명",
        expiry);
  }

  @Test
  void rejectsBudgetExcessEvenIfOtherConditionsValid() {
    assertEquals(
        "BUDGET_EXCEEDED",
        Policy.violation(proposal(10000, 9000, List.of(), Instant.now().plusSeconds(60))));
  }

  @Test
  void rejectsFabricatedAmount() {
    assertEquals(
        "AMOUNT_MISMATCH",
        Policy.violation(proposal(9000, 20000, List.of(), Instant.now().plusSeconds(60))));
  }

  @Test
  void rejectsExcludedIngredient() {
    assertEquals(
        "EXCLUDED_INGREDIENT",
        Policy.violation(proposal(10000, 20000, List.of("땅콩"), Instant.now().plusSeconds(60))));
  }

  @Test
  void rejectsExpiredProposal() {
    assertEquals(
        "PROPOSAL_EXPIRED",
        Policy.violation(proposal(10000, 20000, List.of(), Instant.now().minusSeconds(1))));
  }
}
