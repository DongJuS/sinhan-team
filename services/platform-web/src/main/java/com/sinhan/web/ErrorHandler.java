package com.sinhan.web;

import com.sinhan.contracts.Models.ApiError;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ErrorHandler {
  @ExceptionHandler(Problem.class)
  ResponseEntity<ApiError> problem(Problem e) {
    return ResponseEntity.status(e.status)
        .body(new ApiError(e.code, e.getMessage(), MDC.get("requestId")));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  ResponseEntity<ApiError> validation(Exception e) {
    return ResponseEntity.badRequest()
        .body(new ApiError("INVALID_REQUEST", "요청 형식을 확인해주세요.", MDC.get("requestId")));
  }

  @ExceptionHandler(org.springframework.web.client.RestClientException.class)
  ResponseEntity<ApiError> unavailable(Exception e) {
    return ResponseEntity.status(503)
        .body(
            new ApiError("DEPENDENCY_UNAVAILABLE", "연결 서비스의 결과를 확인하고 있습니다.", MDC.get("requestId")));
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  ResponseEntity<ApiError> forbidden(Exception e) {
    return ResponseEntity.status(403)
        .body(new ApiError("FORBIDDEN", "권한이 없습니다.", MDC.get("requestId")));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(ErrorHandler.class)
        .error("request failed errorType={}", e.getClass().getSimpleName());
    return ResponseEntity.status(500)
        .body(new ApiError("INTERNAL_ERROR", "요청 처리 중 오류가 발생했습니다.", MDC.get("requestId")));
  }
}
