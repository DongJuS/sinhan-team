package com.sinhan.payment.presentation;

import com.sinhan.contracts.Models.*;
import com.sinhan.payment.application.PaymentService;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
public class PaymentController {
  private final PaymentService s;

  public PaymentController(PaymentService s) {
    this.s = s;
  }

  @PostMapping("/payments")
  @PreAuthorize("hasAuthority('payment:charge')")
  public PaymentResult charge(@RequestBody ExecutionCommand c) {
    return s.charge(c);
  }

  @GetMapping("/payments/{id}")
  @PreAuthorize("hasAuthority('payment:read')")
  public PaymentResult get(@PathVariable String id) {
    return s.lookup(id);
  }

  @PostMapping("/payments/{id}/refund")
  @PreAuthorize("hasAuthority('payment:refund')")
  public PaymentResult refund(@PathVariable String id) {
    return s.refund(id);
  }

  @GetMapping("/wallets/{user}")
  @PreAuthorize("hasAuthority('wallet:read')")
  public Map<String, Integer> wallets(@PathVariable String user) {
    return s.balances(user);
  }

  @PostMapping("/test/faults/{name}")
  @PreAuthorize("hasAuthority('test:admin')")
  public Map<String, String> fault(
      @PathVariable String name, @RequestBody Map<String, Integer> body) {
    s.fault(name, body.getOrDefault("count", 1));
    return Map.of("status", "OK");
  }

  @GetMapping("/test/ledger")
  @PreAuthorize("hasAuthority('test:admin')")
  public List<Map<String, Object>> ledger() {
    return s.ledger();
  }
}
