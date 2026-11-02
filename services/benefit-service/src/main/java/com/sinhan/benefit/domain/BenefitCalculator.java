package com.sinhan.benefit.domain;

import com.sinhan.contracts.Models.*;
import java.time.Instant;

public final class BenefitCalculator {
  public record Rule(
      String method,
      String version,
      int instantBps,
      int statementBps,
      int cashbackBps,
      int pointsBps,
      int pointNumerator,
      int pointDenominator,
      int perOrderCap,
      int monthlyCap) {}

  public static BenefitQuote calculate(Basket basket, Rule rule, int remaining, Instant expiresAt) {
    int allowance = Math.max(0, Math.min(rule.perOrderCap(), remaining));
    int instant = Math.min(allowance, percent(basket.grossKrw(), rule.instantBps()));
    allowance -= instant;
    int charge = basket.grossKrw() - instant;
    int statement = Math.min(allowance, percent(charge, rule.statementBps()));
    allowance -= statement;
    int cashback = Math.min(allowance, percent(charge, rule.cashbackBps()));
    allowance -= cashback;
    int earned =
        Math.min(
            percent(charge, rule.pointsBps()),
            (int) ((long) allowance * rule.pointDenominator() / rule.pointNumerator()));
    int pointsValue = (int) ((long) earned * rule.pointNumerator() / rule.pointDenominator());
    return new BenefitQuote(
        basket,
        rule.method(),
        rule.version(),
        instant,
        charge,
        statement,
        cashback,
        earned,
        pointsValue,
        charge - statement - cashback - pointsValue,
        expiresAt);
  }

  private static int percent(int amount, int bps) {
    return (int) ((long) amount * bps / 10000);
  }
}
