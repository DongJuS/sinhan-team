# 자체 호스팅 Jenkins CI/CD

GitHub Actions는 사용하지 않습니다. 공개 저장소 https://github.com/DongJuS/sinhan-team 의 main을 Jenkins가 HTTPS로 체크아웃하므로 GitHub 토큰이 필요하지 않습니다.

```bash
./scripts/start-local-ai.sh
./scripts/start-jenkins.sh
```

- Jenkins: http://localhost:18090
- 관리자: `admin`
- 생성된 비밀번호: `.ci-secrets/jenkins_admin_password` (Git 제외, 최초 실행 시 생성)
- Job: `sinhan-team`. 처음에는 **Build Now**로 실행합니다. Jenkinsfile이 로드된 이후에는 **Build with Parameters → DEPLOY=true**로 실행합니다. 이후 5분 간격 SCM polling으로 main 변경을 감지합니다.
- CD 시연 화면: http://localhost:38080
- CD Keycloak: http://localhost:38081
- 시연 사용자: `demo` / `demo-password`

## 단계

1. GitHub main 체크아웃, 현재 커밋 SHA 기록.
2. Java 단위 테스트, Python 테스트·Agent 평가, OpenAPI 검증.
3. 독립 Docker 이미지 빌드.
4. 테스트 전용 환경에서 통합·장애 복구 테스트. 이 환경의 볼륨만 초기화합니다.
5. DEPLOY=true이면 Mac의 로컬 Qwen으로 LangGraph 분기·출력·로드 메모리 검증. 외부 AI 키를 사용하지 않습니다.
6. 통과한 이미지를 `sinhan-team/<service>:<commit-sha>`로 태그하고 이미지 ID를 아카이브.
7. DEPLOY=true이면 해당 이미지로 로컬 시연 환경 배포 후 로그인·주문안·승인 대기 smoke 검증.
8. JUnit 결과와 로그·배포 커밋 정보를 Jenkins 빌드에 보관.

테스트가 실패하면 배포 단계에 진입하지 않습니다. 배포 자체가 실패하면 기존 데이터를 보존하고 빌드를 실패 처리합니다. DB 마이그레이션을 자동으로 되돌리지는 않습니다. 복구 시 호환성을 검토한 이전 이미지 SHA를 사용합니다.

## 격리

Jenkins는 별도의 Docker-in-Docker 데몬에 TLS로 연결합니다. 호스트의 Docker socket을 마운트하지 않습니다. 이 데몬에 생성한 테스트·배포 컨테이너와 이미지 캐시는 `sinhan-jenkins` 전용 볼륨에 저장됩니다. Jenkins와 데몬은 빌드 workspace만 공유하며, Jenkins 관리자 데이터는 데몬에 마운트하지 않습니다.

단일 로컬 실행자에서 신뢰하는 main만 실행합니다. 외부 PR의 코드를 자동 실행하지 않습니다. Docker-in-Docker는 privileged 컨테이너이며, 관리자 UI와 배포 포트는 호스트 loopback으로 제한합니다. 운영 CI로 확장할 때는 별도 빌드 노드와 배포 권한을 분리하세요.

`compose.jenkins-deploy.yaml`은 이 격리된 데몬 전용입니다. 내부 데몬의 포트를 외부의 38080/38081로 전달하기 위해 내부 gateway/keycloak은 0.0.0.0에 bind합니다. 호스트에서 직접 이 override를 사용하지 않도록 배포 스크립트가 DOCKER_HOST를 검사합니다.

```bash
# 데이터 보존하고 Jenkins·CI 데몬 중지
docker compose -f compose.jenkins.yaml stop
# 상태 확인
docker compose -f compose.jenkins.yaml ps
```

플러그인은 공식 Jenkins update center, Docker CLI는 Docker 공식 Debian 저장소에서 설치합니다. 기본 Jenkins 이미지는 `2.580.2-jdk21`입니다. 초기 플러그인 설치에는 인터넷 연결이 필요합니다.

## 로컬 AI 연결

Ollama는 Jenkins/Docker VM 밖의 Mac에서 실행하여 Metal GPU와 10~20GB 모델 메모리를 사용합니다. Agent와 검증 컨테이너는 `host.docker.internal:11435`로 연결합니다. 16GB 모델을 Jenkins 이미지나 Git 저장소에 포함하지 않습니다.

일반 통합 테스트는 `compose.test.yaml`에서 `AI_MODE=fixture`로 고정합니다. 실제 모델 검증은 `Local Qwen validation` 단계에서 별도로 실행하고 `.artifacts/local-ai-validation.log`에 결과를 보관합니다. 모델 서버가 꺼져 있거나 준비되지 않았다면 배포 검증이 실패하며 시연 응답으로 대체하지 않습니다. 코드 검사만 필요하면 DEPLOY=false로 실행할 수 있습니다.

## 8GiB Docker VM에서의 순차 검증

Jenkins의 통합 테스트 단계는 `ci-integration.sh`를 사용합니다. 기존 로컬 시연 컨테이너와 테스트 컨테이너를 두 벌 동시에 유지하지 않도록, 실행 중이던 시연 컨테이너만 잠시 멈춥니다. 테스트 종료 시 테스트 환경을 정리하고 기존 PostgreSQL → Keycloak → 업무 서비스 순서로 원래 이미지·설정의 컨테이너를 다시 시작합니다. 원래 중지돼 있던 컨테이너나 다른 프로젝트는 건드리지 않습니다.

이 단계 동안 로컬 시연 화면에 일시 중단이 있습니다. 데이터 볼륨을 삭제하지 않으며 테스트 실패 시에도 복구를 시도합니다. 지속적인 서비스 가용성이 필요한 운영 환경에서는 CI 빌드 노드와 배포 서버를 분리해야 합니다. 호스트 Mac의 Qwen 모델은 Docker VM 밖에서 실행됩니다.
