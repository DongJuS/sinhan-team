# 로컬 Qwen 런타임

- Orchestration: LangGraph 1.2.19.
- 모델: Qwen3 14B(14.8B parameters), Q8_0. 공식 모델 `qwen3:14b-q8_0`의 파일 크기는 약 15.9GB.
- 프로젝트 별칭: `sinhan-qwen:14b-q8_0`.
- 실행: Mac의 Ollama + Metal. Docker의 Linux VM 안에 모델을 올리지 않습니다.
- endpoint: 호스트 `http://127.0.0.1:11435`, Docker Desktop 컨테이너 `http://host.docker.internal:11435`.
- 문맥 4096, 출력 최대 384토큰, thinking=false, 동시 추론 1개, 로드 모델 최대 1개.
- 클라우드 모델과 LangSmith 외부 추적은 비활성화합니다. API 키가 필요하지 않습니다.

```bash
./scripts/start-local-ai.sh
docker compose build agent-service
./scripts/check-local-ai.sh
```

모델 가중치는 `.local-ai/models`, 실행 로그·PID는 `.artifacts`에 보관하며 모두 Git에서 제외합니다. 기본 Ollama 포트 11434와 별도로 실행합니다. 다시 실행하면 기존 서버와 내려받은 모델을 재사용합니다.

## 검증 기록

2026-11-02, Apple M5 Pro / 통합 메모리 48GB / Ollama 0.31.1에서 실제 Qwen으로 확인했습니다.

- 한글 금액(만오천 원), 숫자 금액, 누락 정보, 명시적 필드 우선 적용, 조건에 맞는 후보 없음: 5개 통과.
- 설명에서 Java가 계산한 결제금액·실질 지출 보존: 통과.
- Ollama `/api/ps`가 보고한 모델 로드 크기: **15.56~16.39GB**, 모델 전체 GPU 로드, 실행 context **4096**. GPU 사용률 수치는 아닙니다.
- 원본 모델 digest: `304bf7349c71ad37a07eec8be67212b3f05b0f243f4a6f7c98e90dd2f3009f48`.
- 제한된 별칭 digest: `928ac934ca14015eb66035b2cbef1aa808b906828cc34b4402f9e5e92fca4d1f`.

위 크기는 해당 설정에서 관측한 Ollama 보고값이며 Mac 전체 메모리 또는 모든 순간의 최고 메모리를 뜻하지 않습니다. 문맥·동시 처리·추가 모델을 바꾸면 다시 측정해야 합니다. `evaluate_local_ai.py`는 보고된 모델 크기가 10~20GB를 벗어나면 실패합니다.

초기 검증에서 모델이 “만오천 원”을 50,000원으로 잘못 추출한 사례가 있어 한글 수의 자릿값 지침과 실제 모델 회귀 검사를 추가했습니다. 이 검사는 모든 자연어 표현의 정확성을 보장하지 않습니다. 화면의 추출 조건과 최종 주문안을 확인한 뒤 승인해야 합니다.

## 그래프와 실행 권한

계획 그래프는 추출·검증·카탈로그·후보 생성·저장을 연결하며 누락 정보와 잘못된 모델 출력을 분기합니다. 설명 그래프는 근거 검색·이유 코드 선택·금액 고정 렌더링을 연결합니다. 각 모델 출력 오류는 최대 한 번 재시도하고, 실패를 fixture 응답으로 대체하지 않습니다.

승인·결제의 영속 상태와 권한 검증은 Java 서비스가 소유합니다. LangGraph에는 결제 도구나 사용자 승인 권한이 없습니다. 승인된 금액을 LLM이 다시 계산하거나 바꾸지 않습니다.

기본 근거 검색은 로컬 키워드 방식입니다. 선택적인 임베딩도 Ollama의 로컬 Qwen 모델만 지원하며, 별도 색인과 메모리 검증이 필요합니다.

## 메모리 해제

```bash
OLLAMA_HOST=127.0.0.1:11435 ollama stop sinhan-qwen:14b-q8_0
```

서버를 종료할 때는 `.artifacts/ollama.pid`가 이 프로젝트 서버를 가리키는지 확인한 뒤 종료합니다. 모델 파일은 삭제할 필요가 없습니다.

참고: [Qwen3 14B Q8_0](https://ollama.com/library/qwen3:14b-q8_0), [Ollama JSON schema 출력](https://docs.ollama.com/capabilities/structured-outputs), [LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api).
