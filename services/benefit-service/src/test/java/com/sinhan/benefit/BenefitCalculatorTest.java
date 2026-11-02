package com.sinhan.benefit;

import static org.junit.jupiter.api.Assertions.*;

import com.sinhan.benefit.domain.BenefitCalculator;
import com.sinhan.contracts.Models.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class BenefitCalculatorTest {
  Basket basket =
      new Basket(
          "m",
          "가게",
          List.of(new LineItem("a", "메뉴", 2, 8000, Map.of(), List.of())),
          2000,
          25,
          "1",
          true);

  @Test
  void separatesCashChargeFromDeferredRewards() {
    var r = new BenefitCalculator.Rule("CARD", "v1", 0, 500, 1000, 100, 1, 1, 5000, 10000);
    var q = BenefitCalculator.calculate(basket, r, 10000, Instant.now());
    assertEquals(18000, q.chargeKrw());
    assertEquals(900, q.statementDiscountKrw());
    assertEquals(1800, q.cashbackKrw());
    assertEquals(180, q.pointValueKrw());
    assertEquals(15120, q.effectiveCostKrw());
  }

  @Test
  void capsCombinedBenefitsAndRoundsDown() {
    var r = new BenefitCalculator.Rule("CARD", "v1", 1000, 500, 1000, 100, 1, 1, 5000, 10000);
    var q = BenefitCalculator.calculate(basket, r, 1900, Instant.now());
    assertEquals(16200, q.chargeKrw());
    assertEquals(1900, q.totalBenefitKrw());
    assertEquals(0, q.pointsEarned());
  }

  @Test
  void historicalLocalCurrencyRechargeIsNotDiscountedAgain() {
    var r = new BenefitCalculator.Rule("LOCAL", "v1", 0, 0, 500, 200, 1, 1, 2000, 4000);
    var q = BenefitCalculator.calculate(basket, r, 4000, Instant.now());
    assertEquals(18000, q.chargeKrw());
    assertEquals(900, q.cashbackKrw());
    assertEquals(360, q.pointValueKrw());
  }
}
