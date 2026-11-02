# 한끼 — Agent 기반 모의 주문 시스템

Java/Spring Boot 주문 서비스가 Python/FastAPI Agent와 혜택·승인통제·결제 서비스를 조율합니다. 사용자 승인 후 재고 예약 → 모의 결제 → 가맹점 확정을 수행합니다. 실제 결제·은행·카드 API는 호출하지 않습니다.

## GitHub와 CI/CD

공개 저장소: https://github.com/DongJuS/sinhan-team

GitHub Actions 없이 자체 호스팅 Jenkins가 테스트 → 이미지 빌드 → 통합·복구 검증 → 커밋 SHA 태그 → 로컬 시연 배포를 수행합니다.

```bash
./scripts/start-local-ai.sh
./scripts/start-jenkins.sh
```

Jenkins는 http://localhost:18090, CI/CD로 배포한 화면은 http://localhost:38080 입니다. 초기 로그인 정보와 구성은 `infra/jenkins/README.md`를 참고하세요. 아래의 18080 실행 방식은 Jenkins와 별도로 직접 실행하는 개발 환경입니다.

## 실행

Docker Compose v2.24.4 이상과 Docker Engine이 필요합니다. Docker VM에 8GiB 이상을 권장하며 JVM 힙·컨테이너 메모리·DB 커넥션 풀 상한을 명시했습니다. 직접 실행과 CI 환경을 동시에 여러 벌 띄우면 추가 메모리가 필요합니다. 호스트에 Java·Python을 설치하지 않아도 컨테이너로 실행할 수 있습니다.

```bash
cp .env.example .env
./scripts/start-local-ai.sh
docker compose up -d --build --wait
```

- 화면: http://localhost:18080
- 시연 사용자: `demo` / `demo-password`
- 다른 사용자: `other` / `demo-password`
- Keycloak: http://localhost:18081 (`admin` / `.env`의 `KEYCLOAK_ADMIN_PASSWORD`)
- API 문서: `contracts/public-api.yaml`, 화면 서버의 `/openapi.yaml`
- 기본 AI는 **LangGraph + 로컬 Qwen3 14B Q8_0**입니다. Ollama는 Mac에서 Metal GPU를 사용하고 Agent는 HTTP로 연결합니다. CI 통합 테스트에서만 `fixture`를 명시합니다.

화면에서 로그인 → 음식 조건 입력 → 자동 추천·혜택 비교 → 최종 승인 → 완료/환불을 확인합니다. 예: **배달비 포함 2만원 안에서 두 명이 먹을 한식 점심**. 예산·인원 누락 시 추가 입력을 요청하며, 화면에서 조건을 보완해 새 구매 요청을 만들 수 있습니다.

```bash
docker compose logs -f order-worker
docker compose stop
# 컨테이너를 제거하되 거래 데이터는 보관
docker compose down
```

`.env.example`과 realm의 계정·비밀번호·클라이언트 시크릿은 로컬 시연 전용입니다. 서비스 포트는 내부 Docker 네트워크에만 두고 Gateway·Keycloak은 호스트 루프백에 공개합니다. Keycloak 개발 모드와 시연 계정을 외부 공개 환경에 그대로 사용하지 마세요.

## 서비스·데이터 소유

| 구성 | 구현 | 데이터 |
|---|---|---|
| gateway | Spring Cloud Gateway | 없음; PKCE 로그인 화면·사용자 API 라우팅 |
| order-service | Java 21 / Spring Boot | `orders`: 구매 요청·불변 주문안·임대 작업 |
| order-worker | 주문 서비스와 같은 코드 | `orders`: 저장된 단계의 실행·재시도·복구 |
| agent-service | Python / FastAPI | `agent`: 조건 추출·후보 생성 결과·RAG 근거 |
| benefit-service | Java / Spring Boot | `benefits`: 버전 규칙·월 혜택 예약/사용 |
| control-service | Java / Spring Boot | `control`: 검증 스냅샷·승인·실행 허가·감사 이력 |
| payment-service | Java / Spring Boot | `payments`: 시연 잔액·결제·환불 원장 |
| merchant-simulator | Python / FastAPI | 독립 SQLite 볼륨: 메뉴·재고·예약·확정 |
| keycloak | OIDC 제공자 | PostgreSQL의 별도 `keycloak` 데이터베이스 |

각 업무 서비스는 자기 스키마만 사용하는 DB 계정과 마이그레이션을 가집니다. 교차 스키마 조인·외래키는 없습니다. 공유 코드는 `platform-contracts`의 통신 DTO/정규화와 `platform-web`의 인증·오류·추적·HTTP 어댑터뿐입니다.

Java 서비스는 `presentation`, `application`, `domain`, `infrastructure`로 나눕니다. 계산·선택·정책은 domain에, 네트워크·DB는 infrastructure 또는 HTTP Adapter에 둡니다. Python은 domain·application·adapter·FastAPI 진입점을 분리합니다.

## 거래 규칙

- 주문은 한 가게에 한정합니다. v1에서 인원 1명당 메뉴 1개로 후보 조합을 생성하고, 인원은 1–10명입니다.
- 비교한 유효 후보 중 `예상 실질 지출 → 실제 결제금액 → 배달시간 → 가맹점 ID → 결제수단` 순으로 선택합니다.
- 예산은 실제 결제금액에 적용합니다. 실질 지출은 청구 할인·캐시백·신규 포인트 환산 가치까지 반영한 예상값입니다.
- CARD_A·CARD_B·LOCAL은 실제 상품이 아닌 버전이 있는 시연 규칙입니다. 금액과 비율 계산은 정수·내림 방식입니다. 월 한도는 서울 시간의 달을 기준으로 예약·확정 금액을 함께 집계합니다. 전월 실적 조건은 CARD_A 30만원, CARD_B 20만원이며 demo는 40만원, other는 5만원으로 설정했습니다.
- 기존 지역화폐 충전 혜택은 다시 차감하지 않습니다. LOCAL은 서울의 지원 가맹점에서만 사용 가능합니다.
- 승인 대상은 메뉴·옵션·수량·배송지·견적·사용자·버전·만료를 포함한 스냅샷입니다. SHA-256으로 동일성을 검증하고 실행 ID를 하나로 고정합니다.
- 승인 유효기간은 견적 발급 후 5분입니다. 승인 시점과 결제 전 재검증을 수행합니다. 재고 예약 이후에는 해당 예약의 가격·수량을 가맹점이 보장합니다.
- 결제·가맹점의 실행 ID는 재시도에도 같습니다. DB 제약과 잠금으로 원장 기록이 한 번만 만들어집니다.
- 작업 임대는 45초, 갱신은 10초입니다. 임대 소유권을 확인한 작업만 상태를 갱신합니다. 네트워크 호출은 DB 트랜잭션 밖에서 수행합니다.
- 결제 응답 유실은 거래 조회로 확인합니다. 가맹점 확정 응답 유실은 같은 예약 확정을 재조회하는 효과의 멱등 호출로 확인합니다. 명시적인 거절 때만 환불합니다.
- 계획 수립은 5회 실패하면 FAILED, 주문안 재작성은 5개 버전까지입니다. 거래 결과가 불명확한 실행·환불은 RECOVERY_PENDING으로 남겨 최대 30초 간격으로 계속 확인합니다.
- 자동 복구가 지속되는 요청은 상태의 errorCode와 requestId로 추적합니다. 결과를 확인하지 않은 채 jobs나 거래 원장을 수동 삭제하면 안 됩니다.

## API와 인증

공개 API는 다음 세 개입니다. 요청마다 Bearer access token을 전달합니다.

```text
POST /v1/purchase-requests                 Idempotency-Key 필수; 202
GET  /v1/purchase-requests/{id}             소유자만 조회
POST /v1/proposals/{id}/approvals           {"version": 1}; 202
```

내부 API와 권한 표는 `contracts/internal-api.md`를 참고하세요. 주문 실행은 `order-worker` 서비스 계정, 고객 승인은 로그인 사용자의 토큰으로 호출합니다. Agent의 권한은 가맹점 읽기뿐이며 결제·승인을 실행할 수 없습니다. 모든 서비스에서 JWT 발급자·서명·audience·만료·주체와 endpoint 권한을 확인합니다.

## LangGraph·로컬 Qwen

구성은 `LangGraph 1.2.19 → Ollama → Qwen3 14B Q8_0`입니다. LangGraph는 AI 처리 단계를 연결하고 Qwen이 조건과 설명 이유를 추출합니다. 주문 승인·결제 상태는 Java 서비스가 관리합니다.

- 계획 그래프: 조건 추출 → 검증 → (누락 시 추가 입력 대기 / 유효하면 카탈로그 조회 → 후보 생성) → 실행 근거 저장.
- 설명 그래프: 로컬 RAG 근거 조회 → Qwen의 이유 코드 선택 → 검증된 금액으로 설명 렌더링.
- 잘못된 모델 출력은 그래프에서 한 번만 재시도합니다. 서버 장애·반복된 출력 오류를 fixture 결과로 바꾸지 않습니다.
- `agent.plan_runs`에 실행 모델과 노드 경로를 저장합니다. 요청이 중단되면 Java의 영속 작업이 읽기·계획 단계를 다시 실행합니다.

Mac에 [Ollama](https://ollama.com/download)가 설치된 상태에서 실행합니다.

```bash
./scripts/start-local-ai.sh
```

스크립트는 localhost **11435**의 프로젝트 전용 서버를 시작하고 `qwen3:14b-q8_0`를 내려받아 `sinhan-qwen:14b-q8_0` 별칭을 만듭니다. 모델 파일은 약 **16GB**, 실행 메모리는 **10~20GB 목표**입니다. 문맥 4,096, 출력 최대 384토큰, 동시 추론 1개, thinking 비활성화로 시작합니다. 메모리 사용량은 문맥·런타임에 따라 달라지므로 별도 검사에서 Ollama가 보고하는 실제 로드 크기를 확인합니다.

- 모델 저장: `.local-ai/models` — Git·Docker 빌드에서 제외.
- 서버 로그·PID: `.artifacts/ollama.log`, `.artifacts/ollama.pid`.
- 서버에서 `OLLAMA_NO_CLOUD=1`, 최대 로드 모델 1개를 적용합니다. LangSmith 외부 추적도 비활성화합니다.
- HTTP 주소는 localhost·Docker host의 로컬 서버만 허용하고 cloud 모델 이름을 거절합니다. API 키가 필요하지 않습니다.
- 호스트 확인: `OLLAMA_HOST=127.0.0.1:11435 ollama ps`.
- 사용 후 메모리 해제: `OLLAMA_HOST=127.0.0.1:11435 ollama stop sinhan-qwen:14b-q8_0`.
- Agent 컨테이너 주소: `http://host.docker.internal:11435`. 현재 구성은 Apple Silicon Mac + Docker Desktop 기준입니다.

기본 RAG는 작은 시연 문서의 로컬 키워드 검색입니다. 임베딩이 필요하면 로컬 Ollama에 Qwen 임베딩 모델을 준비하고 `RAG_EMBEDDING_MODEL`을 설정한 뒤 `docker compose exec agent-service python rag.py`로 재색인합니다. 외부 임베딩 API를 호출하지 않습니다. 임베딩 모델을 추가하면 모델 전환·추가 로드 비용을 별도로 확인해야 합니다.

실제 Qwen 평가를 일반 테스트·부하 측정과 분리했습니다.

```bash
# 기본 Agent 이미지 빌드 후, 실제 로컬 모델로 LangGraph 분기·설명·메모리 검증
docker compose build agent-service
./scripts/check-local-ai.sh
# 호스트 가상환경에서 직접 실행
AI_MODE=local OLLAMA_BASE_URL=http://127.0.0.1:11435 .venv/bin/python tests/evaluate_local_ai.py
```

Jenkins는 고정 응답으로 통합·복구 테스트를 수행한 뒤, DEPLOY=true일 때 실제 로컬 Qwen 검증을 통과해야 배포합니다. 배포 환경은 `local` 모드이며 모델 응답 지연에 대응해 Agent 호출만 별도 타임아웃을 사용합니다. 결제·가맹점 호출의 짧은 타임아웃은 유지합니다.

## 검증

```bash
# Java 21 / Maven
mvn test
# Python 3.12 이상; 가상환경 사용
python -m pip install -r services/python-common/requirements.txt -r services/agent-service/requirements.txt pytest==8.3.5
python -m pytest -q
# 통합 테스트: 별도 프로젝트·볼륨·포트(28080/28081)
./scripts/test-integration.sh
```

통합 테스트는 전용 `sinhan-food-test` 볼륨을 초기화한 뒤 실행합니다. 일반 시연 환경의 데이터는 유지합니다. 중복 승인·결제 재시도, 승인 전 결제 차단, 소유권, 가격 변경·품절 재승인, 만료·철회, 결제·확정·환불 응답 유실, 잔액·원장 일치, 20개 교차 스키마 접근 차단을 검증합니다. 테스트 환경에서만 장애 주입 API가 활성화됩니다. 이어서 Worker를 결제 직후 강제 종료하고 45초 임대 만료 후 복구되어 결제·가맹점 확정이 한 번만 발생하는지도 검증합니다.

테스트 종료 후 컨테이너를 중지하려면:

```bash
docker compose -p sinhan-food-test -f compose.yaml -f compose.test.yaml stop
```

AI 평가와 HTTP 부하 측정은 `tests/evaluate_agent.py`, `tests/load_smoke.py`로 분리합니다. 대규모 TPS 목표를 보장하는 구성은 아닙니다.

## 관측 프로필

```bash
docker compose -f compose.yaml -f compose.observability.yaml up -d --build
```

Grafana는 http://localhost:13000 (`admin` / `GRAFANA_PASSWORD`, 기본 `grafana-dev-only`)입니다. Prometheus는 Java 서비스 지표, Tempo는 OpenTelemetry trace, Loki는 구조화 로그를 수집합니다. 구매 요청 ID로 거래 흐름을 연결하며 토큰·메시지·주소 원문을 로그에 기록하지 않습니다. 시연용 관측 저장소는 장기 보존용이 아닙니다.
