package com.sinhan.order.domain;

import com.sinhan.contracts.Models.*;
import java.util.*;

public record OrderState(
    String id,
    String userId,
    PurchaseInput input,
    String status,
    String stage,
    String message,
    String errorCode,
    Constraints constraints,
    List<BenefitQuote> candidates,
    Proposal proposal,
    int revision) {
  public OrderState progress(String status, String stage, String message, String error) {
    return new OrderState(
        id,
        userId,
        input,
        status,
        stage,
        message,
        error,
        constraints,
        candidates,
        proposal,
        revision);
  }

  public OrderState planned(
      Constraints constraints, List<BenefitQuote> candidates, Proposal proposal) {
    return new OrderState(
        id,
        userId,
        input,
        "AWAITING_APPROVAL",
        "APPROVAL",
        "최적 주문안을 확인하고 승인해주세요.",
        null,
        constraints,
        candidates,
        proposal,
        proposal.version());
  }
}
