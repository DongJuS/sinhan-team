package com.sinhan.control.presentation;

import com.sinhan.contracts.Models.*;
import com.sinhan.control.application.ControlService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1")
public class ControlController {
  private final ControlService service;

  public ControlController(ControlService s) {
    service = s;
  }

  @PostMapping("/validations")
  @PreAuthorize("hasAuthority('control:prepare')")
  public Grant prepare(@RequestBody Proposal p) {
    return service.prepare(p);
  }

  @PostMapping("/approvals/{id}")
  @PreAuthorize("hasAuthority('customer')")
  public Grant approve(
      @PathVariable String id,
      @Valid @RequestBody ApprovalInput input,
      @AuthenticationPrincipal Jwt jwt) {
    return service.approve(id, input.version(), jwt.getSubject());
  }

  @PostMapping("/authorizations/claim")
  @PreAuthorize("hasAuthority('control:claim')")
  public Grant claim(@RequestBody ExecutionCommand c) {
    return service.check(c, true);
  }

  @PostMapping("/authorizations/check")
  @PreAuthorize("hasAuthority('control:check')")
  public Grant check(@RequestBody ExecutionCommand c) {
    return service.check(c, false);
  }

  @PostMapping("/approvals/{id}/revoke")
  @PreAuthorize("hasAuthority('control:prepare')")
  public Map<String, String> revoke(@PathVariable String id) {
    service.revoke(id);
    return Map.of("status", "REVOKED");
  }
}
