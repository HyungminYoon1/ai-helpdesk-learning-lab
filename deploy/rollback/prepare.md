# 읽기 준비 버전 P의 로컬 PostgreSQL·Image 호환 검증

> 기준 Source: `2e2cc403f73972d5c0f87ab81342b776e7777f6b`
> 작업 Branch: `codex/week8-rollback-prepare`
> 상태: P의 로컬 Spring·PostgreSQL·MockMvc·JavaScript·JAR와 Docker Image 실행 검증 완료. 이 문서는 로컬 검증 결과이며 원격 CI·ECR 이력은 main의 배포 안내에서 관리한다. Cloud Rollback은 별도 단계다.

## P의 변경 범위

현재 Cloud에서 사용한 기준 Source에 두 실패 코드의 읽기만 준비한다.

- `AiJobFailureCode.java`: `PRIVACY_SCAN_TIMEOUT`·`PRIVACY_SCAN_INVALID_RESULT` 추가
- `ai-suggestion-ui.mjs`: 같은 두 코드를 FAILED 응답의 허용값으로 추가

운영 Migration은 V1~V7 그대로이며 Dockerfile·POM·개인정보 처리기·Worker 로직·정책은 변경하지 않는다. 알 수 없는 코드나 추가 응답 필드는 여전히 거부한다. P를 만들기 위해 현재 main의 변경을 되돌리거나 기존 미커밋 자료를 덮어쓰지 않았다.

P의 기존 V7 통합 Test에서는 V7 DB가 저장할 수 있는 실패 코드만 생성한다. 새 두 코드를 읽는 검증은 아래 별도 V8 DB 실험에서 수행한다. Enum에 읽기를 추가한 것과 V7 DB가 새 값의 쓰기를 허용하는 것은 다르다.

## 실제 검증한 흐름

1. 새 PostgreSQL 17.6-alpine Testcontainer를 만든다. 초기 관리자와 별개인 일반 `rollback_app` 계정을 초기화하며, App에는 CONNECT·public Schema USAGE/CREATE만 부여한다. 실행 중 생성한 합성 Password를 SQL 파일·명령 인자·출력에 적지 않는다.
2. 외부 환경 변수와 System Property Source를 제외한 독립 Spring Environment를 구성한다. 로컬 DB와 합성 USER·AGENT, AI 비활성 설정만 전달한다. Hikari Pool은 최대 두 Connection으로 제한한다.
3. `postgres,deployment`의 실제 App을 V7에서 기동한다. MockMvc로 접수·Session·Role·CSRF와 기존 성공·대기·실패 결과를 확인한다. V7이 새 실패 코드의 쓰기를 거부하는 것도 확인한다.
4. 첫 Spring Context를 닫고 같은 DB에 Test 전용 V8을 적용한다. Fixture의 SHA-256은 후속 Source의 실제 V8과 일치한다. 기존 여섯 업무 Table의 내용 Hash와 V1~V7 이력을 대조한다.
5. 두 새 실패 Job을 합성 SQL Fixture로 만든다. 실제 AI 검사기나 Provider를 실행한 것이 아니다.
6. 같은 P 코드의 두 번째 Spring Context를 기동한다. 추가 Ignore 설정이나 Flyway 비활성 없이 V8 DB에서 기동·Validate가 통과하며 Migration 이력을 변경하지 않는지 확인한다.
7. 두 실패 코드를 각각 HTTP 200·Job FAILED·원래 failureCode·suggestion=null로 조회한다. 기존 문의·작성자·OPEN·PENDING·성공 제안·복수 분류·예약을 보존하며, 조회 전후 여섯 Table Hash가 같은지 확인한다.
8. V8에서도 P의 기존 접수는 201·PENDING·예약 0으로 동작한다. 기존 다섯 문의 확인 뒤 여섯 번째 문의를 추가해 Identity 충돌 없이 저장한다.

이 실험은 같은 JVM 안에서 Spring Context를 두 번 생성했다. 실제 JVM Process 교체·Browser E2E·RDS TLS 접속·Cloud 배포의 근거로 사용하지 않는다. HTTP 응답 검증은 MockMvc, UI 검증은 JavaScript Unit Test다.

## 실행 명령

이 Branch의 Worktree에서 실행한다. 실행 전에 Docker Engine이 준비돼 있어야 한다.

```powershell
.\mvnw.cmd -B -ntp '-Dtest=RollbackPreparePostgresCompatibilityIntegrationTest,AiSuggestionQueryServiceTest,AiSuggestionQueryHttpIntegrationTest,DeploymentSecurityHttpIntegrationTest' verify
node --test src/test/js/*.test.mjs
npx --yes --package eslint@10.11.0 eslint scripts/week7-*.mjs src/main/resources/static/*.mjs src/test/js/*.mjs
```

2026-10-16 실행에서 관련 Java 56개가 통과했다. 이후 Container 검증의 소스 안전장치 Test 네 개를 더한 이 Branch의 JavaScript 전체는 181개이며 실패·오류·건너뜀 없이 통과했고 ESLint도 통과했다. 현재 main의 Java 704개·JavaScript 246개 전체 회귀를 실행한 결과는 아니다.

포장한 JAR에는 운영 Migration 일곱 개만 있고 Test 전용 V8·계정 초기화 SQL·호환 Test는 없다. 수정한 Enum Class와 UI Module은 검증한 `target/classes`의 Hash와 일치한다. 아래 Docker 검증은 이 포장 검사와 별도로 수행했다.

## Docker Image와 실제 로컬 Process 검증

P Worktree의 변경하지 않은 Dockerfile로 `linux/amd64` Image를 Build했다. 아직 Commit하지 않은 P 변경이므로 기준 Commit 자체의 깨끗한 Image라고 표시하지 않고 `source-state=uncommitted` Label을 붙였다.

- Tag: `helpdesk:week8-rollback-prepare-20261016-local1`
- 로컬 Image ID: `sha256:18d2ed349fc1e5f0ea65d625ab9c582df7859f211f9e5a7fbef44a0b22ffaf3b`
- 실행: UID/GID `10001:10001`, `java -jar /app/helpdesk.jar`
- Image에서 꺼낸 JAR의 운영 Migration 일곱 개는 Source와 Byte Hash가 일치하며 V8·Test Fixture는 없다. Enum Class는 앞서 검증한 Class와, UI는 P Source와 Hash가 일치했다.
- 별도 공개 RDS CA 파일도 Source Hash와 일치했다. 파일은 root 소유 `0444`, 디렉터리는 `0555`이며 실행 사용자는 읽을 수 있지만 변경할 수 없다. 이것은 파일 포장·권한 확인이지 실제 RDS TLS 접속 결과는 아니다.

고유 이름의 Compose Project와 새 PostgreSQL 17.6 Volume을 만들었다. 합성 Credential은 Process Environment에서 생성·복원하며 App은 일반 `helpdesk_app`으로 접속한다. App은 Loopback 임의 Port만 게시하고 DB Host Port는 게시하지 않는다. `postgres,deployment`, Provider·Worker 비활성, CPU 0.25·메모리 512 MiB·Swap 없음 조건이다.

1. V7에서 실제 HTTP 로그인·Session·Role·CSRF·문의 접수와 기존 대기/성공/실패 조회를 확인했다. 두 새 실패 코드의 쓰기는 V7 Constraint가 거부했다.
2. App을 중지하고, 같은 Image에만 Test 전용 V8 디렉터리를 읽기 전용으로 연결해 Migration했다. V1~V7 이력과 여섯 업무 Table Hash는 유지됐다. 새 실패 Row는 합성 SQL Fixture다.
3. 해당 Mount·Flyway 위치 설정을 제거하고 같은 P Image로 새 App Container/JVM을 실행했다. 검증을 끄지 않고 V8 DB에서 기동했으며 추가 Migration 쓰기 없이 새 실패 두 건을 각각 HTTP 200·FAILED·원래 failureCode·suggestion=null로 조회했다.
4. DB Process·Volume은 그대로였고, 이전 Cookie를 가진 HttpClient는 401을 받았다. 새 로그인 뒤 기존 문의·원문·작성자·예약·제안·분류를 읽을 수 있었으며 조회 전후 내용 Hash도 같았다.
5. V8에서도 신규 접수는 201·PENDING·예약 0이었다. 최종 Ticket/Message/Job은 각 여섯 건, 합성 Attempt 네 건·Suggestion 한 건·Category 두 건이었다. 익명 401·USER 조회 403·CSRF 없는 POST 403도 유지됐다.

검증 명령은 기존 공통 Verifier에 호환성 Mode를 추가해 재사용한다. V8 Override는 중간 Test 단계에만 쓰며 정상 P 재실행에는 전달하지 않는다.

```powershell
docker build --platform linux/amd64 --provenance=false --tag helpdesk:week8-rollback-prepare-20261016-local1 --label lab.helpdesk.source-state=uncommitted --label lab.helpdesk.version-role=rollback-prepare --label lab.helpdesk.base-revision=2e2cc403f73972d5c0f87ab81342b776e7777f6b .
.\scripts\Verify-Week8Compose.ps1 -ImageTag 'helpdesk:week8-rollback-prepare-20261016-local1' -DeploymentResourceProbe -RollbackPrepareCompatibility -CpuLimit 0.25 -MemoryMiB 512 -QueryCount 10
```

고유 Project를 사용한 두 번의 실행이 모두 PASS였다. 최종 재실행의 V7 기동은 약 50.71초, cgroup 메모리 최고값은 V7 204.86 MiB·정상 P의 V8 연결 200.85 MiB였다. 첫 실행을 포함해 관찰한 최고값은 313.64 MiB이며 모든 측정의 OOM Event/Kill은 0이었다. 메모리는 파일 Cache와 측정 Process도 포함한다. 각 단계의 순차 조회 10회 결과일 뿐 Worker·실제 개인정보 검사·동시 부하를 포함한 용량 검증은 아니다.

안전한 JSON 결과와 Image 산출물 검사는 Git 제외 `local/deployment/rollback-prepare/`에 보관했다. 종료 때 각 검증 Project의 App·DB Container만 중지하고 Volume과 Image는 유지했다. 생성한 Password·관찰한 Token/Cookie가 검증 종료 시 남아 있는 App·DB Log에 포함되지 않았음을 값 대조로 확인했다. 일반 HttpClient의 실제 HTTP 검증이며 Browser E2E나 B→P의 다른 Image 교체를 실행한 것은 아니다. AWS API·CI·ECR·실제 AI에는 접근하지 않았다.

## Flyway 경고의 의미

V8 DB에 연결한 P에는 V7까지의 파일만 있으므로 최신 적용 버전이 더 높다는 경고가 나왔다. Flyway의 기본 Ignore Pattern은 `*:future`다. 이번에 검증을 끄거나 새 Ignore 규칙을 추가한 것은 아니지만, 기본 설정이 Future Migration을 허용한다는 경계는 남는다. [Flyway 공식 설정 설명](https://documentation.red-gate.com/fd/flyway-ignore-migration-patterns-setting-277579002.html)

따라서 기동 성공만으로 모든 미래 Schema와 호환된다고 할 수 없다. P는 자신이 가진 V1~V7을 검증하며, 이 실험은 정확히 지정한 V8과 기존/신규 상태 조회·접수에 대한 결과다. V8 자체의 파일 대조는 별도 Fixture Hash로 확인했다. V8을 V7로 내리거나 적용 이력을 삭제하지 않았다.

## 결정 기록

- 맥락: 후속 버전의 새 실패 문자열을 기존 버전이 읽지 못한다. 원본 데이터의 실패 사유를 바꾸는 것은 허용하지 않는다.
- 선택지: 기존 Image로 바로 복귀, 현재 main 전체를 P라고 사용, 기준 Source에서 읽기만 준비하는 별도 P 구성.
- 결정: 승인받은 세 번째 안으로 일반 App 계정의 격리 DB에서 먼저 검증한다. 운영 Source 변경은 두 파일뿐이며 새 쓰기 로직과 V8은 P에 포함하지 않는다.
- 이유: 현재 main에는 이미 V8과 개인정보 처리 변경이 있어 그대로 사용하면 준비와 활성화를 분리할 수 없다. 별도 Branch가 원래의 읽기·쓰기 경계를 보존한다.
- 영향 파일: 두 운영 파일, 기존 V7 HTTP Test의 Enum 범위, 새 호환 Integration Test·UI/Container 안전장치 Test·Test Fixture, 선택형 Compose Verifier·로컬 V8 Override와 이 안내.
- 수정한 검증 준비: 최초 시도에서는 초기 Bootstrap Superuser의 속성을 제거하려다 거부됐다. 별도 일반 App 계정을 생성하는 방식으로 고쳤으며 권한을 넓히지 않았다. [PostgreSQL 역할 변경 제한](https://www.postgresql.org/docs/17/sql-alterrole.html)
- Container 검증 결정: V8을 P Image에 넣는 안 대신 외부의 읽기 전용 Test Mount로 한 번 적용하고 제거했다. 정상 P Image의 V7 파일 구성을 유지하면서 V8 이후 읽기 호환성을 검사하기 위해서다. 후속 검토에서는 이 합성 적용을 실제 B 배포와 구분한다.
- 후속 검토: P의 CI·전달·Cloud 복귀. 현재 배포 Workflow의 main 전용 검증·업로드 조건을 유지해야 하므로 이 Branch의 검증만으로 임의 ECR Push를 하지 않는다. 현재 main을 P 내용으로 덮어쓰거나 Cloud DB를 변경하는 것도 이 실험 범위가 아니다.

위 로컬 검증 중 Commit·Push·ECR 업로드·AWS API 호출·실제 AI 호출은 하지 않았다. 이후 원격 전달은 [main의 준비 버전 배포 안내](https://github.com/HyungminYoon1/ai-helpdesk-learning-lab/blob/main/deploy/rollback/README.md)에서 해당 Source Commit·CI·Image Digest로 연결한다. JUnit Testcontainer는 자동 정리했고 Compose 검증 Container는 중지했다. 기존 다른 Container·Volume·Image는 삭제하지 않는다.
