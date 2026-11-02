package com.sinhan.gateway;

import java.util.UUID;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.WebFilter;

@Configuration
@EnableWebFluxSecurity
public class GatewayConfiguration {
  @Bean
  RouteLocator routes(RouteLocatorBuilder b) {
    return b.routes()
        .route(
            "orders",
            r ->
                r.path(
                        "/v1/purchase-requests",
                        "/v1/purchase-requests/**",
                        "/v1/proposals/*/approvals")
                    .uri(System.getenv().getOrDefault("ORDER_URL", "http://order-service:8080")))
        .build();
  }

  @Bean
  ReactiveJwtDecoder decoder() {
    var d =
        NimbusReactiveJwtDecoder.withJwkSetUri(
                System.getenv()
                    .getOrDefault(
                        "OIDC_JWKS",
                        "http://keycloak:8080/realms/food/protocol/openid-connect/certs"))
            .build();
    d.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(
                System.getenv().getOrDefault("OIDC_ISSUER", "http://localhost:18081/realms/food")),
            j ->
                j.getSubject() != null
                        && !j.getSubject().isBlank()
                        && j.getAudience().contains("sinhan-api")
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return d;
  }

  @Bean
  SecurityWebFilterChain security(ServerHttpSecurity h) {
    return h.csrf(c -> c.disable())
        .authorizeExchange(
            a ->
                a.pathMatchers(
                        "/",
                        "/index.html",
                        "/app.js",
                        "/style.css",
                        "/config.json",
                        "/openapi.yaml",
                        "/favicon.ico",
                        "/actuator/health")
                    .permitAll()
                    .anyExchange()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> {}))
        .build();
  }

  @Bean
  WebFilter requestId() {
    return (exchange, chain) -> {
      String supplied = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
      String id =
          supplied != null && supplied.matches("[A-Za-z0-9-]{1,64}")
              ? supplied
              : UUID.randomUUID().toString();
      exchange.getResponse().getHeaders().set("X-Request-ID", id);
      return chain
          .filter(
              exchange
                  .mutate()
                  .request(
                      exchange
                          .getRequest()
                          .mutate()
                          .headers(
                              h -> {
                                h.set("X-Request-ID", id);
                                h.remove("X-User-ID");
                              })
                          .build())
                  .build())
          .doFinally(
              signal ->
                  org.slf4j.LoggerFactory.getLogger(GatewayConfiguration.class)
                      .info(
                          "gateway requestId={} method={} status={}",
                          id,
                          exchange.getRequest().getMethod(),
                          exchange.getResponse().getStatusCode()));
    };
  }
}
