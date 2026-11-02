# 내부 인터페이스

모든 내부 호출은 `aud=sinhan-api`의 서비스 토큰과 endpoint별 permissions가 필요합니다. Gateway에 내부 경로를 라우팅하지 않습니다. Java 요청·응답의 기준은 `platform-contracts`의 `Models.java`, Python 계획·설명의 입력 검증은 FastAPI의 `/openapi.json`입니다.

| 서비스 | 메서드·경로 | 입력/출력 | 권한 |
|---|---|---|---|
| Agent | POST /internal/v1/plans | SearchCommand → SearchResult | agent:plan |
| Agent | POST /internal/v1/explanations | quote, evaluatedCandidates → text, evidence | agent:plan |
| Agent | GET /internal/v1/evidence?q= | evidence 배열 | agent:plan |
| 혜택 | POST /internal/v1/benefit-quotes | QuoteCommand → QuoteResult | benefit:quote |
| 혜택 | POST /internal/v1/reservations | ExecutionCommand → RESERVED | benefit:reserve |
| 혜택 | POST /internal/v1/reservations/{executionId}/commit | {} → COMMITTED | benefit:reserve |
| 혜택 | POST /internal/v1/reservations/{executionId}/release | {} → RELEASED | benefit:reserve |
| 통제 | POST /internal/v1/validations | Proposal → Grant(PREPARED) | control:prepare |
| 통제 | POST /internal/v1/approvals/{proposalId} | ApprovalInput → Grant(APPROVED); 사용자 토큰 | customer |
| 통제 | POST /internal/v1/authorizations/claim | ExecutionCommand → Grant(CLAIMED) | control:claim |
| 통제 | POST /internal/v1/authorizations/check | ExecutionCommand → Grant(CLAIMED) | control:check |
| 통제 | POST /internal/v1/approvals/{proposalId}/revoke | {} → REVOKED | control:prepare |
| 결제 | POST /internal/v1/payments | ExecutionCommand → PaymentResult | payment:charge |
| 결제 | GET /internal/v1/payments/{executionId} | PaymentResult | payment:read |
| 결제 | POST /internal/v1/payments/{executionId}/refund | {} → PaymentResult | payment:refund |
| 결제 | GET /internal/v1/wallets/{userId} | 결제수단별 잔액 Map | wallet:read |
| 가맹점 | GET /internal/v1/catalog | 가맹점·메뉴·재고 목록 | merchant:read |
| 가맹점 | POST /internal/v1/baskets/validate | Basket → 현재 데이터로 정규화한 Basket | merchant:read |
| 가맹점 | POST /internal/v1/reservations | ExecutionCommand → MerchantResult | merchant:write |
| 가맹점 | POST /internal/v1/reservations/{executionId}/confirm | {} → MerchantResult | merchant:write |
| 가맹점 | POST /internal/v1/reservations/{executionId}/release | {} → MerchantResult | merchant:write |

`ExecutionCommand`는 `executionId`와 불변 `proposal`을 전달합니다. 동일 실행 ID에 다른 스냅샷을 전달하면 409입니다. 재시도 시 ID를 새로 만들지 않습니다. 혜택·가맹점 예약은 결제 전 실행되고, 확정 전 실패 또는 환불 후 해제합니다.

상태 조회가 404인 경우와 5xx/타임아웃인 경우를 구분합니다. 후자는 거래가 없다는 의미가 아닙니다. 고객에게는 공개 구매 요청의 상태만 노출합니다.

## 오류

| HTTP | code 예시 | 호출자 동작 |
|---|---|---|
| 400 | INVALID_REQUEST, IDEMPOTENCY_KEY_REQUIRED | 입력 수정 |
| 401 | 인증 계층의 invalid_token | 인증 갱신 |
| 403 | FORBIDDEN, APPROVAL_REQUIRED, NOT_OWNER | 실행 금지 |
| 404 | REQUEST_NOT_FOUND, PAYMENT_NOT_FOUND | 대상별 부재 처리; 사용자 소유권은 404로 숨김 |
| 409 | PRICE_CHANGED, OUT_OF_STOCK, QUOTE_CHANGED, PROPOSAL_EXPIRED | 결제 여부 확인·예약 정리 후 새 주문안과 재승인 |
| 409 | IDEMPOTENCY_CONFLICT, APPROVAL_MISMATCH | 동일 ID의 요청 변경 차단 |
| 503 | DEPENDENCY_UNAVAILABLE, SIMULATED_RESPONSE_LOSS | 저장된 단계에서 결과 확인·재시도 |

## 장애 주입

`compose.test.yaml`에서만 `ENABLE_FAULTS=true`로 활성화하고 test-admin 토큰을 사용합니다.

- 결제 `POST /internal/v1/test/faults/{name}`: charge_response_lost, refund_response_lost, refund_unavailable. body: `{"count":1}`.
- 가맹점 같은 경로: confirm_rejected, confirm_response_lost, confirm_response_delay(확정 후 15초 응답 지연).
- 가맹점 `POST /internal/v1/test/catalog/{merchantId}/{menuId}`: `{"priceKrw":8000}` 또는 `{"stock":0}`.
- 원장과 예약 읽기: 결제 `/internal/v1/test/ledger`, 가맹점 `/internal/v1/test/reservations`.
