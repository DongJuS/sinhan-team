package com.sinhan.web;

import com.sinhan.contracts.Canonical;
import com.sinhan.contracts.Models.ApiError;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;

/** HTTP, credentials and trace adapter only. No business rules or shared persistence. */
public class ServiceHttp {
  private final RestClient client;
  private final RestClient agentClient;
  private String token;
  private long tokenUntil;

  public ServiceHttp(RestClient.Builder builder) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(12));
    client = builder.requestFactory(factory).build();
    var agentFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    agentFactory.setReadTimeout(
        Duration.ofSeconds(Long.parseLong(env("AI_CALL_TIMEOUT_SECONDS", "300"))));
    agentClient = builder.clone().requestFactory(agentFactory).build();
  }

  public synchronized String serviceToken() {
    if (token != null && System.currentTimeMillis() < tokenUntil) return token;
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", env("SERVICE_CLIENT_ID", "order-worker"));
    form.add("client_secret", env("SERVICE_CLIENT_SECRET", "order-worker-dev-only"));
    Map<?, ?> result =
        client
            .post()
            .uri(
                env(
                    "OIDC_TOKEN_URL",
                    "http://keycloak:8080/realms/food/protocol/openid-connect/token"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);
    token = (String) result.get("access_token");
    tokenUntil =
        System.currentTimeMillis()
            + Math.max(1, ((Number) result.get("expires_in")).longValue() - 30) * 1000;
    return token;
  }

  public <T> T post(String url, Object body, Class<T> type) {
    return postAs(url, body, type, serviceToken());
  }

  public <T> T postAgent(String url, Object body, Class<T> type) {
    return postUsing(agentClient, url, body, type, serviceToken());
  }

  public <T> T postAs(String url, Object body, Class<T> type, String bearer) {
    return postUsing(client, url, body, type, bearer);
  }

  private <T> T postUsing(
      RestClient transport, String url, Object body, Class<T> type, String bearer) {
    try {
      return transport
          .post()
          .uri(url)
          .headers(
              h -> {
                h.setBearerAuth(bearer);
                h.set("X-Request-ID", requestId());
              })
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(type);
    } catch (RestClientResponseException e) {
      throw translate(e);
    }
  }

  public <T> T get(String url, Class<T> type) {
    try {
      return client
          .get()
          .uri(url)
          .headers(
              h -> {
                h.setBearerAuth(serviceToken());
                h.set("X-Request-ID", requestId());
              })
          .retrieve()
          .body(type);
    } catch (RestClientResponseException e) {
      throw translate(e);
    }
  }

  private RuntimeException translate(RestClientResponseException e) {
    try {
      var p = Canonical.read(e.getResponseBodyAsString(), ApiError.class);
      return new Problem(e.getStatusCode().value(), p.code(), p.message());
    } catch (IllegalArgumentException ex) {
      return new Problem(e.getStatusCode().value(), "DEPENDENCY_ERROR", "연결 서비스 요청에 실패했습니다.");
    }
  }

  private String requestId() {
    String id = MDC.get("requestId");
    return id == null ? java.util.UUID.randomUUID().toString() : id;
  }

  public static String env(String name, String fallback) {
    return System.getenv().getOrDefault(name, fallback);
  }
}
