package com.sinhan.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.web.filter.OncePerRequestFilter;

public class TraceFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(TraceFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = request.getHeader("X-Request-ID");
    if (id == null || !id.matches("[A-Za-z0-9-]{1,64}")) id = UUID.randomUUID().toString();
    MDC.put("requestId", id);
    response.setHeader("X-Request-ID", id);
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      LOG.info(
          "http method={} status={} durationMs={}",
          request.getMethod(),
          response.getStatus(),
          (System.nanoTime() - start) / 1000000);
      MDC.remove("requestId");
    }
  }
}
