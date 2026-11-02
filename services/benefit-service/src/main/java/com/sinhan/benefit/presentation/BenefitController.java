package com.sinhan.benefit.presentation;

import com.sinhan.benefit.application.BenefitService;
import com.sinhan.contracts.Models.*;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
public class BenefitController {
  private final BenefitService service;

  public BenefitController(BenefitService s) {
    service = s;
  }

  @PostMapping("/benefit-quotes")
  @PreAuthorize("hasAuthority('benefit:quote')")
  public QuoteResult quote(@RequestBody QuoteCommand c) {
    return service.quote(c);
  }

  @PostMapping("/reservations")
  @PreAuthorize("hasAuthority('benefit:reserve')")
  public Map<String, String> reserve(@RequestBody ExecutionCommand c) {
    service.reserve(c);
    return Map.of("status", "RESERVED");
  }

  @PostMapping("/reservations/{id}/commit")
  @PreAuthorize("hasAuthority('benefit:reserve')")
  public Map<String, String> commit(@PathVariable String id) {
    service.status(id, "COMMITTED");
    return Map.of("status", "COMMITTED");
  }

  @PostMapping("/reservations/{id}/release")
  @PreAuthorize("hasAuthority('benefit:reserve')")
  public Map<String, String> release(@PathVariable String id) {
    service.status(id, "RELEASED");
    return Map.of("status", "RELEASED");
  }
}
