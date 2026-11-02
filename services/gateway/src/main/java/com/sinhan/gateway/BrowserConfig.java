package com.sinhan.gateway;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class BrowserConfig {
  @GetMapping("/config.json")
  public Map<String, String> config() {
    return Map.of(
        "issuer",
        System.getenv().getOrDefault("OIDC_ISSUER", "http://localhost:18081/realms/food"),
        "clientId",
        "food-web");
  }
}
