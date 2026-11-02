# 컴포넌트별 커밋 기준

하나의 컴포넌트 또는 하나의 동작 변경을 한 커밋으로 묶습니다. 커밋 본문만으로 목적·계약·실패 시 동작·검증을 이해할 수 있도록 작성합니다. 작업 대화나 이전 메시지를 참조하지 않습니다.

```text
feat(order): resume approved purchases from durable workflow steps

Problem:
An interrupted process must not lose an approved purchase or charge twice.

Change:
Persist each workflow step and lease jobs with an owner token. Use the same
execution ID for payment and merchant retries. Reconcile unknown results
before proceeding; refund only after an explicit merchant rejection.

Contracts:
POST /v1/proposals/{id}/approvals accepts the immutable proposal version.
Only the proposal owner can approve. Changed terms require a new approval.

Validation:
Run the order selection tests and integration scenarios. Kill the worker
after payment and verify recovery creates one charge and one merchant order.
```

버그 수정 커밋에는 재현 조건, 변경 전후 결과와 회귀 검증을 포함합니다. 기반 구성 커밋에는 후속 컴포넌트가 의존하는 경로·규격을 명시합니다. 실제로 실행한 검증과 아직 실행하지 않은 검증을 구분합니다.

비밀 값, `.env`, `.ci-secrets`, `.artifacts`, 빌드 결과·캐시는 커밋하지 않습니다. 공개 저장소에 포함된 계정은 로컬 시연 전용임을 명시합니다. CI/CD 변경은 `Jenkinsfile`과 자체 호스팅 Jenkins 구성으로 관리합니다.
