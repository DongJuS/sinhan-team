package com.sinhan.contracts;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class Models {
  private Models() {}

  public record PurchaseInput(
      @NotBlank @Size(max = 2000) String message,
      @NotBlank @Size(max = 300) String address,
      @NotBlank String region,
      @Min(1000) @Max(1000000) Integer budgetKrw,
      @Min(1) @Max(10) Integer people,
      List<String> excludedIngredients,
      String cuisine) {}

  public record Constraints(
      int budgetKrw, int people, List<String> excludedIngredients, String cuisine) {}

  public record LineItem(
      String menuId,
      String name,
      int quantity,
      int unitPriceKrw,
      Map<String, String> options,
      List<String> ingredients) {}

  public record Basket(
      String merchantId,
      String merchantName,
      List<LineItem> items,
      int deliveryFeeKrw,
      int deliveryMinutes,
      String catalogVersion,
      boolean localCurrencyAccepted) {
    public int subtotalKrw() {
      return items.stream()
          .mapToInt(i -> Math.multiplyExact(i.quantity(), i.unitPriceKrw()))
          .reduce(0, Math::addExact);
    }

    public int grossKrw() {
      return Math.addExact(subtotalKrw(), deliveryFeeKrw);
    }
  }

  public record SearchCommand(
      String requestId, PurchaseInput input, List<String> excludedMerchants) {}

  public record SearchResult(
      String status, String message, Constraints constraints, List<Basket> candidates) {}

  public record QuoteCommand(
      String userId, String region, List<Basket> baskets, String excludeExecutionId) {}

  public record BenefitQuote(
      Basket basket,
      String paymentMethod,
      String ruleVersion,
      int instantDiscountKrw,
      int chargeKrw,
      int statementDiscountKrw,
      int cashbackKrw,
      int pointsEarned,
      int pointValueKrw,
      int effectiveCostKrw,
      Instant expiresAt) {
    public int totalBenefitKrw() {
      return instantDiscountKrw + statementDiscountKrw + cashbackKrw + pointValueKrw;
    }
  }

  public record QuoteResult(List<BenefitQuote> quotes) {}

  public record Proposal(
      String id,
      String requestId,
      String executionId,
      String userId,
      int version,
      String address,
      String region,
      Constraints constraints,
      BenefitQuote quote,
      String explanation,
      Instant expiresAt) {}

  public record ApprovalInput(@NotNull @Min(1) Integer version) {}

  public record ExecutionCommand(String executionId, Proposal proposal) {}

  public record Grant(
      String proposalId, String executionId, String digest, String status, Instant expiresAt) {}

  public record PaymentResult(
      String executionId, String status, int amountKrw, String paymentMethod) {}

  public record MerchantResult(String executionId, String status) {}

  public record ApiError(String code, String message, String traceId) {}
}
