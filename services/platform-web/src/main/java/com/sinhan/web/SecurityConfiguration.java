package com.sinhan.web;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  JwtDecoder jwtDecoder() {
    String issuer =
        System.getenv().getOrDefault("OIDC_ISSUER", "http://localhost:18081/realms/food");
    String jwks =
        System.getenv()
            .getOrDefault(
                "OIDC_JWKS", "http://keycloak:8080/realms/food/protocol/openid-connect/certs");
    var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            jwt ->
                jwt.getSubject() != null
                        && !jwt.getSubject().isBlank()
                        && jwt.getAudience().contains("sinhan-api")
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return decoder;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt ->
            Arrays.stream(
                    Optional.ofNullable(jwt.getClaimAsString("permissions")).orElse("").split(" "))
                .filter(s -> !s.isBlank())
                .map(
                    s ->
                        (org.springframework.security.core.GrantedAuthority)
                            new SimpleGrantedAuthority(s))
                .toList());
    return http.csrf(c -> c.disable())
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
        .build();
  }
}
