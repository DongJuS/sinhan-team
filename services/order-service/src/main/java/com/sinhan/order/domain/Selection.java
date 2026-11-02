package com.sinhan.order.domain;

import com.sinhan.contracts.Models.*;
import java.util.*;

public final class Selection {
  public static List<BenefitQuote> eligible(List<BenefitQuote> quotes, Constraints c) {
    return quotes.stream()
        .filter(
            q ->
                q.chargeKrw() <= c.budgetKrw()
                    && q.basket().items().stream().mapToInt(LineItem::quantity).sum() == c.people())
        .filter(
            q ->
                q.basket().items().stream()
                    .flatMap(i -> i.ingredients().stream())
                    .noneMatch(c.excludedIngredients()::contains))
        .sorted(
            Comparator.comparingInt(BenefitQuote::effectiveCostKrw)
                .thenComparingInt(BenefitQuote::chargeKrw)
                .thenComparingInt(q -> q.basket().deliveryMinutes())
                .thenComparing(q -> q.basket().merchantId())
                .thenComparing(BenefitQuote::paymentMethod))
        .toList();
  }
}
