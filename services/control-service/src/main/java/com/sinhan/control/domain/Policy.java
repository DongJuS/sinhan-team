package com.sinhan.control.domain;

import com.sinhan.contracts.Models.*;
import java.time.Instant;

public final class Policy {
  private Policy() {}

  public static String violation(Proposal p) {
    var q = p.quote();
    var b = q.basket();
    if (!p.expiresAt().isAfter(Instant.now()) || !q.expiresAt().isAfter(Instant.now()))
      return "PROPOSAL_EXPIRED";
    if (p.expiresAt().isAfter(q.expiresAt())) return "INVALID_EXPIRY";
    if (p.address() == null || p.address().isBlank() || p.constraints().budgetKrw() < 1000)
      return "INVALID_CONSTRAINTS";
    if (q.chargeKrw() < 0 || q.chargeKrw() > p.constraints().budgetKrw()) return "BUDGET_EXCEEDED";
    if (b.items().isEmpty()
        || b.items().stream()
            .anyMatch(i -> i.quantity() < 1 || i.quantity() > 10 || i.unitPriceKrw() < 0))
      return "INVALID_BASKET";
    if (b.items().stream().mapToInt(LineItem::quantity).sum() != p.constraints().people())
      return "PEOPLE_MISMATCH";
    if (b.items().stream()
        .flatMap(i -> i.ingredients().stream())
        .anyMatch(p.constraints().excludedIngredients()::contains)) return "EXCLUDED_INGREDIENT";
    if (q.instantDiscountKrw() < 0
        || q.statementDiscountKrw() < 0
        || q.cashbackKrw() < 0
        || q.pointValueKrw() < 0) return "INVALID_BENEFIT";
    if (b.grossKrw() - q.instantDiscountKrw() != q.chargeKrw()
        || q.chargeKrw() - q.statementDiscountKrw() - q.cashbackKrw() - q.pointValueKrw()
            != q.effectiveCostKrw()) return "AMOUNT_MISMATCH";
    return null;
  }
}
