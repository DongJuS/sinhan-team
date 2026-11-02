package com.sinhan.order;

import static org.junit.jupiter.api.Assertions.*;

import com.sinhan.contracts.Models.*;
import com.sinhan.order.domain.Selection;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class SelectionTest {
  BenefitQuote q(int charge, int effective, int minutes) {
    var b =
        new Basket(
            "m",
            "식당",
            List.of(new LineItem("i", "메뉴", 1, charge, Map.of(), List.of())),
            0,
            minutes,
            "v1",
            true);
    return new BenefitQuote(
        b,
        "CARD",
        "v1",
        0,
        charge,
        0,
        charge - effective,
        0,
        0,
        effective,
        Instant.now().plusSeconds(300));
  }

  @Test
  void excludesOverBudgetEvenWhenRewardsAreLarge() {
    var result =
        Selection.eligible(
            List.of(q(21000, 10000, 10), q(19000, 18000, 20)),
            new Constraints(20000, 1, List.of(), null));
    assertEquals(1, result.size());
    assertEquals(19000, result.getFirst().chargeKrw());
  }

  @Test
  void tiesUseChargeThenDeliveryTime() {
    var result =
        Selection.eligible(
            List.of(q(19000, 17000, 10), q(18000, 17000, 20), q(18000, 17000, 15)),
            new Constraints(20000, 1, List.of(), null));
    assertEquals(18000, result.getFirst().chargeKrw());
    assertEquals(15, result.getFirst().basket().deliveryMinutes());
  }
}
