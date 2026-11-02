package com.sinhan.order.presentation;

import com.sinhan.contracts.Models.*;
import com.sinhan.order.application.OrderService;
import com.sinhan.order.domain.OrderState;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasAuthority('customer')")
public class OrderController {
  private final OrderService s;

  public OrderController(OrderService s) {
    this.s = s;
  }

  @PostMapping("/purchase-requests")
  public ResponseEntity<OrderState> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody PurchaseInput input,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.accepted().body(s.create(jwt.getSubject(), key, input));
  }

  @GetMapping("/purchase-requests/{id}")
  public OrderState get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
    return s.get(id, jwt.getSubject());
  }

  @PostMapping("/proposals/{id}/approvals")
  public ResponseEntity<OrderState> approve(
      @PathVariable String id,
      @Valid @RequestBody ApprovalInput input,
      @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.accepted()
        .body(s.approve(id, input.version(), jwt.getSubject(), jwt.getTokenValue()));
  }
}
