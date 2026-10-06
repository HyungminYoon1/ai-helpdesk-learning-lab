# AI Helpdesk Learning Lab

> 상태: Week 6 회귀 유지·Week 7 HTTP 접수·Job 실행권·Spring AI Adapter·선택 Worker·복구·AGENT 조회·최소 UI — Java 449개·JavaScript 145개 통과
> 현재 학습 영역: AI 출력 계약·독립 AI 실험·PostgreSQL 접수·예약부터 결과 저장, AGENT 읽기 전용 조회와 최소 화면의 응답 분기까지의 흐름. 실제 Java AI→PostgreSQL 한 건과 통제된 Provider의 자동 처리·조건부 복구·서로 다른 JVM Process 재시작·실제 Browser 흐름 확인. 유료 자동 Worker 연결과 요약 수동 평가는 후속 단계
> 실행 기준: Java 25

## 프로젝트 목적

AI Helpdesk Learning Lab은 큰 서비스를 빠르게 완성하는 프로젝트가 아니라 Java Backend의 핵심 개념을 작은 실험으로 학습하고 검증하기 위한 프로젝트다.

Week 1에는 Framework 없이 Ticket 객체가 자신의 상태와 규칙을 지키게 만들고, 조건문과 Strategy·Composition의 변경 범위를 비교하며, JUnit Test로 그 계약을 설명하는 것을 목표로 한다.

Week 2에는 Week 1의 Ticket Domain과 Test를 회귀 기준선으로 유지하면서 HTTP 메시지와 REST 계약을 먼저 설명한다. 이후 Spring Boot를 최소 구성으로 기동하고, Ticket 생성·단건 조회 흐름을 Controller·Application Service·Repository·Domain으로 분리하여 구현한다. 구현량보다 예상 계약, Test와 실제 HTTP Trace의 차이를 설명하고 재현하는 데 중점을 둔다.

Week 4에는 기존 수직 Slice를 유지하면서 Session 인증과 Role 기반 인가를 작은 단계로 실험한다. 9월 11일 학습이 자정을 넘긴 연장 구간에서 Security Starter만 추가해 Default Auto-Configuration의 영향을 먼저 관찰했다. 실제 Test 실행 시각은 2026-09-12 00:41~00:42 KST였다. 9월 12일에는 실제 Filter Chain을 통과하는 익명 API Request의 `401`, BCrypt Password 검증, Test 전용 USER·AGENT의 Form Login·Session 복원과 Role Matrix, 인증된 POST의 CSRF Token 누락·유효 조건을 작은 단계로 확인했다. 9월 14일에는 전체 Test와 Source·설정·Test Report를 다시 점검해 자동 생성된 기본 보안 Password 안내가 Report에 남는 문제를 확인하고, Runtime 사용자가 없는 현재 단계에서는 기본 사용자 자동 구성을 제외했다. Runtime 사용자 구성과 실제 Browser Cookie·CSRF Trace는 아직 구현하거나 실행하지 않았다.

Week 6에는 기존 `TicketRepository` Port를 유지한 채 Spring JDBC Adapter를 추가했다. `postgres` Profile에서는 `JdbcTicketRepository`, `in-memory` Profile에서는 기존 In-memory Adapter를 사용한다. Flyway `V1` Migration을 빈 PostgreSQL 17.6 Testcontainer에 적용하고, 생성·단건 조회·Row 복원·누락 조회·Database `CHECK` 위반과 같은 Transaction의 Rollback을 실제 PostgreSQL에서 검증했다. Spring Context 재생성 Test와 별도로, 실제 Browser 수직 검증에서는 같은 PostgreSQL Container를 유지한 채 Java Process를 종료·재시작하고 기존 Row를 조회했다. 최소 Ticket UI는 Session·CSRF를 유지해 생성·조회하며, Credential CORS의 Preflight와 실제 POST, Role·CSRF 실패를 실제 Browser에서 확인했다. PostgreSQL Container·Volume 재시작이나 운영 배포 검증은 아니다.

## 핵심 질문

1. Client와 Server는 HTTP Method·URI·Header·Body와 Status를 통해 요청과 응답의 의미를 어떻게 합의하는가?
2. Ticket 생성·단건 조회의 정상·실패 계약을 구현 전에 설명할 수 있는가?
3. Controller·Application Service·Repository·Domain은 각각 무엇을 알고 무엇을 몰라야 하는가?
4. Spring MVC Test와 실제 Server에 보내는 `curl.exe` Trace는 각각 무엇을 검증하는가?
5. Standalone Controller Test와 실제 Security Filter Chain Test는 왜 서로 다른 결과를 낼 수 있는가?
6. Framework의 Default 거부 동작과 Application이 선택한 `401`·`403` 계약을 어떻게 구분해 검증하는가?
7. Login 성공 Request와 같은 Session을 사용하는 후속 Request는 각각 무엇을 증명하는가?

## 현재 범위

### Week 1 회귀 기준선

- Java 25 기반 단일 Ticket Domain
- 제목 불변조건 검증
- `OPEN → IN_PROGRESS → RESOLVED` 상태 전이
- 허용되지 않은 상태 전이 거부
- 실패한 상태 전이 이후 기존 상태 보존
- 범용 Setter 대신 의도가 드러나는 행동 제공
- 정상·경계·거부 JUnit Test와 대표 Exception Message 검증
- NORMAL 24시간·URGENT 4시간·VIP 1시간 응답 시간 Policy 비교
- 조건문과 Strategy·Composition에 같은 VIP 변경 요구 적용
- 응답 시간 Policy는 Ticket 업무 흐름에 연결하지 않은 독립 학습 Code

### Week 2 학습·구현 범위

- HTTP Request·Response의 Method, URI, Status, Header, Content Type과 Body
- REST Resource·URI·Representation, 안전성과 멱등성
- `POST /api/tickets`와 `GET /api/tickets/{id}`의 구현 전 예상 계약
- Spring Boot 최소 Application Context와 내장 Server 기동
- Spring MVC의 DispatcherServlet·Controller 요청 처리 흐름
- Controller·Application Service·Repository Port·In-memory 구현·Domain 책임 분리
- 정상 생성·조회와 대표 `400 Bad Request`·`404 Not Found`·통제된 `500 Internal Server Error` 검증
- 요청 DTO Validation과 Domain 불변조건 검증의 경계
- `ResponseEntityExceptionHandler`·`@RestControllerAdvice` 기반의 안전한 `ProblemDetail` 오류 응답
- `OncePerRequestFilter` 기반 Request ID 부여와 `HandlerInterceptor` 기반 Handler 실행 시간 기록
- MockMvc Test와 실제 `curl.exe` Request·Response Trace 비교

### Week 4 Security 진행 상태

- `spring-boot-starter-security` 추가
- Spring Boot Security Starter 4.1.1, Spring Security 7.1.1 해석 확인
- Test Scope의 `spring-security-test` 7.1.1 추가
- Security 변경 직전 전체 Test 33개 통과
- Starter 단독 상태에서 Standalone Controller Test 7개 통과
- 실제 Context Test 2개는 기존 `404` 대신 `/login` Redirect `302`를 받아 실패
- `/api/**`는 인증을 요구하고 익명 인증 실패는 `HttpStatusEntryPoint`를 통해 `401`을 반환하는 최소 `SecurityFilterChain` 구성
- 익명 Security Integration Test에서 `401`, `Location` 없음, Controller 미진입 확인
- Web Infrastructure Test에는 Request별 `AGENT` Test Double을 넣어 기존 Filter·Interceptor의 `404` 검증 책임 유지
- `BCryptPasswordEncoder` Bean 구성
- 같은 후보의 두 Encoding이 다르고 두 `matches`는 성공하며 잘못된 후보는 실패하는 Test 통과
- Test 전용 `UserDetailsService`에 실행 중 임의 Password로 Encoding한 `USER`·`AGENT` Fixture 구성
- Form Login 성공·잘못된 Password 실패와 Login 결과의 `MockHttpSession`을 사용한 후속 보호 Request 검증
- 후속 Request에서 Username·Password를 다시 보내지 않고 `AGENT` Authentication 복원과 `TicketController#findById` 도달 확인
- Test 전용 `USER` Fixture를 추가하고 Ticket 생성은 `USER`·`AGENT`, 단건 조회는 `AGENT`만 허용
- 로그인한 `USER` 생성 `201`, 조회 `403`·Controller 미진입, `AGENT`의 존재하는 Ticket 조회 `200` 검증
- 같은 USER Session과 POST 조건에서 CSRF Token 없음은 `403`·Controller 미진입, 유효 Token은 `201`·Controller 진입으로 비교
- 전체 Test 42개 통과, 실패·오류·건너뜀 0
- Runtime 사용자가 없는 상태에서 `UserDetailsServiceAutoConfiguration`을 제외해 자동 생성 기본 Password 안내가 Console·Surefire Report에 남지 않도록 구성
- 2026-09-14 일반 `clean test` 재실행 후 생성 Password 안내, UUID 형태의 해당 값, BCrypt Encoding, Session ID와 CSRF Token 값이 Surefire Report에 남지 않은 것을 Pattern 기반으로 확인
- Runtime 사용자 구성과 실제 Browser Cookie·CSRF Network Trace는 `NOT_IMPLEMENTED`·`NOT_RUN`

### Week 6 PostgreSQL 진행 상태

- Spring JDBC·Flyway·PostgreSQL Driver와 Testcontainers 의존성 추가
- `V1__create_tickets.sql`로 `tickets` Table·Identity·현재 값 Constraint 정의
- `postgres` Profile에서 `JdbcTicketRepository`, `in-memory` Profile에서 `InMemoryTicketRepository` 선택
- Profile을 생략했을 때 In-memory로 조용히 대체하지 않고 조립에 실패하도록 명시적 선택 유지
- `INSERT ... RETURNING id`와 Parameter Binding을 사용한 생성 구현
- `SELECT` Row의 Status 문자열을 `TicketStatus`로 변환하고 `Ticket.restore()`로 Domain 복원
- 실제 PostgreSQL 17.6 Testcontainer에서 Flyway V1 자동 적용 확인
- JDBC Adapter 선택·생성/복원·누락 조회·공백 제목·알 수 없는 Status와 Transaction Rollback의 6개 Integration Test 통과
- 첫 INSERT 성공 뒤 두 번째 INSERT 실패가 같은 Transaction의 첫 INSERT까지 되돌리는 것을 최종 Row 수 0으로 확인
- 같은 PostgreSQL Container를 유지한 채 서로 다른 두 Spring Application Context와 Repository 객체에서 같은 Row 조회 확인
- 인증된 Session의 CSRF Token 응답과 같은 Session·Token을 사용한 후속 POST `201`을 MockMvc로 확인
- `local-browser` Profile에서만 외부 설정으로 임시 USER·AGENT를 구성하며, Cookie 없는 Preflight를 Security 인증보다 먼저 처리하는 Credential CORS 계약 검증
- 최소 `/tickets.html` UI에서 HTTP·JSON·Ticket 검증, `textContent`, Event Delegation과 Response Race 방어 구현
- 실제 Browser의 AGENT 생성·조회, Cross-Origin `OPTIONS`·`POST 201`, USER 조회 `403`, CSRF 없는 POST `403`, 익명 조회 `401`과 PostgreSQL Row 확인
- 같은 PostgreSQL Container를 유지한 새 Java Process에서 기존 Ticket 조회 확인. PostgreSQL Container·Volume 재시작은 `NOT_RUN`
- 전체 Java Clean Test 61개, Node Ticket UI Test 12개 통과. ESLint 오류 0, UI Source Line Coverage `85.51%`·Branch Coverage `77.05%`

2026-08-25 야간에 Spring Boot Dependency와 Application 진입점을 추가하고 기존 Unit Test 16개를 다시 통과했다. Application Context와 내장 Server를 기동한 뒤 Root URI에 실제 `curl.exe` 요청을 보내 `404 Not Found` JSON 응답을 관찰했다.

2026-08-26에는 Repository·Application Service·Controller로 정상 생성·조회 수직 Slice를 구성하고 MockMvc Test 2개와 전체 Test 24개를 통과했다. 2026-08-27에는 실제 `POST`·`GET` 호출을 확인하고, 제목 Validation·잘못된 JSON·잘못된 ID 형식·존재하지 않는 Ticket·통제된 내부 실패를 서로 다른 오류 계약으로 구현했다. 전체 Clean Test 29개와 실제 `400`·`404` HTTP Trace를 확인했으며 대표 `500`은 Production 실패 Endpoint 없이 수동 Test Double로만 재현했다.

2026-08-29 야간 학습 세션은 자정을 넘어 8월 30일 01시대까지 이어졌다. 이 세션에서 모든 요청에 Request ID를 부여하는 Filter와 선택된 Controller Method의 이름·최종 HTTP Status·실행 시간을 기록하는 Interceptor를 최소 범위로 추가했다. 단위 Test와 전체 Application Context를 사용하는 MockMvc 통합 Test를 구분했으며, 전체 Clean Test 33개가 실패·오류·건너뜀 없이 통과했다. 실제 Server와 `curl.exe` Trace는 다시 실행하지 않았으므로 8월 27일 근거와 구분한다.

## Ticket Domain 규칙

### 생성 규칙

- Ticket 제목은 `null`일 수 없다.
- Ticket 제목은 공백 문자열일 수 없다.
- 새 Ticket의 초기 상태는 `OPEN`이다.

### 상태 전이 규칙

| 현재 상태 | 행동 | 결과 |
|---|---|---|
| `OPEN` | `startProgress()` | `IN_PROGRESS` |
| `IN_PROGRESS` | `resolve()` | `RESOLVED` |
| 그 외 상태 | 허용되지 않은 행동 | `IllegalStateException` |

상태를 변경하기 전에 현재 상태를 검사하므로, 허용되지 않은 행동이 실패해도 기존 상태는 변경되지 않는다.

## 프로젝트 구조

```text
.
├─ README.md
├─ pom.xml
├─ mvnw
├─ mvnw.cmd
├─ .mvn/
│  └─ wrapper/
│     └─ maven-wrapper.properties
├─ .gitattributes
├─ .gitignore
└─ src/
   ├─ main/
   │  └─ java/
   │     └─ lab/
   │        └─ helpdesk/
   │           ├─ HelpdeskApplication.java
   │           ├─ web/
   │           │  ├─ HandlerTimingInterceptor.java
   │           │  ├─ RequestIdFilter.java
   │           │  └─ WebConfiguration.java
   │           ├─ ticket/
   │           │  ├─ Ticket.java
   │           │  ├─ TicketStatus.java
   │           │  ├─ application/
   │           │  │  ├─ TicketApplicationService.java
   │           │  │  ├─ TicketNotFoundException.java
   │           │  │  └─ TicketResult.java
   │           │  ├─ repository/
   │           │  │  ├─ TicketRepository.java
   │           │  │  └─ InMemoryTicketRepository.java
   │           │  └─ web/
   │           │     ├─ CreateTicketRequest.java
   │           │     ├─ TicketApiExceptionHandler.java
   │           │     ├─ TicketController.java
   │           │     └─ TicketResponse.java
   │           └─ responsetime/
   │              ├─ TicketPriority.java
   │              ├─ conditional/
   │              │  └─ ConditionalResponseTimePolicy.java
   │              └─ strategy/
   │                 ├─ ResponseTimePolicy.java
   │                 ├─ ResponseTimeCalculator.java
   │                 ├─ NormalResponseTimePolicy.java
   │                 ├─ UrgentResponseTimePolicy.java
   │                 └─ VipResponseTimePolicy.java
   └─ test/
      └─ java/
         └─ lab/
            └─ helpdesk/
               ├─ ticket/
               │  ├─ TicketTest.java
               │  ├─ application/
               │  │  └─ TicketApplicationServiceTest.java
               │  ├─ repository/
               │  │  └─ InMemoryTicketRepositoryTest.java
               │  └─ web/
               │     └─ TicketControllerTest.java
               ├─ web/
               │  ├─ HandlerTimingInterceptorTest.java
               │  ├─ RequestIdFilterTest.java
               │  └─ WebInfrastructureIntegrationTest.java
               └─ responsetime/
                  ├─ conditional/
                  │  └─ ConditionalResponseTimePolicyTest.java
                  └─ strategy/
                     └─ ResponseTimeCalculatorTest.java
```

Java Package Root는 `lab.helpdesk`다. Application 진입점은 Root에 두고 Ticket Domain의 `lab.helpdesk.ticket`과 독립 Policy 비교용 `lab.helpdesk.responsetime` 하위 Package를 사용한다.

위 구조는 2026-08-30 현재 실제 Source 기준이다.

## 실행 요구사항

- JDK 25
- PowerShell 또는 동등한 명령행 환경
- 첫 Maven Wrapper 실행 시 Maven Distribution을 받을 수 있는 네트워크
- Week 6 JavaScript Test·Lint·Browser E2E에는 Node.js 22 이상과 Docker Engine이 필요하다. E2E는 Playwright CLI를 일회성으로 내려받는다.

설치된 Java 도구의 Version을 확인한다.

```powershell
java --version
javac --version
jshell --version
```

세 명령 모두 Java 25를 가리켜야 한다.

## Build와 검증 방법

이 Project는 Maven Wrapper `3.3.4`의 `only-script` 방식으로 Maven `3.9.16`을 고정한다. Windows에서는 전역 Maven 설치 대신 Project Root의 `mvnw.cmd`를 실행한다.

```powershell
.\mvnw.cmd --version
.\mvnw.cmd test
```

`test` Phase를 요청하면 Main Source와 Test Source를 컴파일한 뒤 Maven Surefire가 JUnit Platform을 통해 Test를 실행한다. 2026-09-29 전체 `clean test`에서는 61개가 실패·오류·건너뜀 없이 통과했다. PostgreSQL Integration Test에는 Docker Engine과 실제 PostgreSQL Container 기동이 필요하다.

최소 Browser UI의 JavaScript Test·Coverage·Lint와 Local 실제 Browser 수직 검증은 별도 명령이다. E2E Script는 Port `15432`·`18081`·`18082`가 비어 있어야 하며, 일회용 Database·Runtime 사용자만 사용하고 종료 시 정리한다. Credential 값을 출력하지 않는다.

```powershell
node --experimental-test-coverage --test src/test/js/ticket-ui.test.mjs
npx --yes --package eslint@10.11.0 eslint src/main/resources/static/*.mjs src/test/js/*.mjs
.\scripts\Verify-Week6BrowserE2E.ps1
```

Application 실행 시 저장 방식을 Profile로 명시한다. `in-memory` Profile은 JDBC·Flyway 자동 설정을 제외하고, `postgres` Profile은 표준 Spring DataSource 설정을 통해 실제 PostgreSQL 연결 정보를 받아야 한다. Profile을 생략해 In-memory로 조용히 대체하지 않는다.

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=in-memory"
```

Build와 Test 실행 후 다음 위치에 Class 파일과 Test Report가 생성된다.

```text
target/classes/lab/helpdesk/
target/test-classes/lab/helpdesk/
target/surefire-reports/
```

`out/`, `target/`, `build/` 같은 생성물 디렉터리는 Git에서 추적하지 않는다.

## 현재 검증 상태

| 검증 항목 | 상태 | 근거 |
|---|---|---|
| JDK와 Java Compiler | 완료 | `java`, `javac`, `jshell` 25.0.4 확인 |
| Java 25 Source 컴파일 | 완료 | `javac --release 25` 성공 |
| Maven Wrapper | 완료 | Wrapper 3.3.4로 Maven 3.9.16과 Java 25.0.4 실행 확인 |
| Maven `test` Lifecycle | 완료 | Spring Boot 구성 추가 후 `.\mvnw.cmd clean test`에서 Main·Test Source 재컴파일과 `BUILD SUCCESS` 확인 |
| Ticket 정상 상태 전이 | 자동 검증 완료 | 생성·처리 시작·해결 정상 Case 통과 |
| 제목 경계 입력 | 자동 검증 완료 | `null`·빈 문자열·공백 문자열 거부 Case 통과 |
| 잘못된 상태 전이 거부 | 자동 검증 완료 | 거부 Case 4개에서 예외 Type과 실패 후 상태 보존 확인 |
| Exception Message | 자동 검증 완료 | 서로 다른 대표 Message 3개 확인 |
| 조건문 응답 시간 Policy | 자동 검증 완료 | NORMAL 24시간·URGENT 4시간·VIP 1시간 Case 통과 |
| Strategy 응답 시간 Policy | 자동 검증 완료 | 세 Policy 구현체를 같은 Interface와 Calculator로 검증 |
| Security 변경 전 JUnit 기준선 | 완료 | `Tests run: 33, Failures: 0, Errors: 0, Skipped: 0` |
| Security Starter 단독 실험 | 원인 확인된 Red | `Tests run: 33, Failures: 2, Errors: 0, Skipped: 0`; 실제 Context Test에서 기대 `404`, 실제 `/login` Redirect `302` |
| 익명 API `401` 최소 Baseline | 자동 검증 완료 | 실제 Filter Chain Test에서 `401`, Redirect Header 없음, Controller 미진입 확인 |
| Security 적용 후 전체 회귀 | 자동 검증 완료 | `Tests run: 34, Failures: 0, Errors: 0, Skipped: 0`; Web Infrastructure Test는 Request별 인증 Test Double 사용 |
| BCrypt Password 검증 | 자동 검증 완료 | 두 Encoding의 차이, 올바른 후보의 두 `matches` 성공과 잘못된 후보 실패; 값 자체는 출력하지 않음 |
| Password 적용 후 전체 회귀 | 자동 검증 완료 | `Tests run: 35, Failures: 0, Errors: 0, Skipped: 0` |
| Test 전용 AGENT Form Login | 자동 검증 완료 | 미등록 사용자 Red 뒤 등록된 사용자 Login 성공, 잘못된 Password 실패와 인증 상태 확인 |
| Login Session 재사용 | 자동 검증 완료 | Login 결과의 동일 `MockHttpSession`만 후속 보호 Request에 전달해 `AGENT` Authentication 복원과 Controller 도달 확인 |
| Session 적용 후 전체 회귀 | 자동 검증 완료 | `Tests run: 38, Failures: 0, Errors: 0, Skipped: 0` |
| Test 전용 Role Matrix | 자동 검증 완료 | `USER` 생성 `201`, 조회 `403`·Controller 미진입, `AGENT`의 존재하는 Ticket 조회 `200` 확인 |
| Role Matrix 적용 후 전체 회귀 | 자동 검증 완료 | `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0` |
| 인증된 POST의 CSRF 비교 | 자동 검증 완료 | 같은 USER Session에서 Token 없음은 `403`·Controller 미진입, 유효 Token은 `201`·Controller 진입 |
| CSRF 비교 후 전체 회귀 | 자동 검증 완료 | `Tests run: 42, Failures: 0, Errors: 0, Skipped: 0` |
| 기본 개발용 Credential Log | 제거·재검증 완료 | 기본 사용자 자동 구성을 제외하고 2026-09-14 일반 `clean test`의 Console·Surefire Report에서 생성 Password 안내 0건 확인 |
| Source·공개 문서·Test Report 노출 점검 | Pattern 기반 점검 완료 | 추적 Source의 대표 Secret 형태·민감 Literal, 추적 Credential 파일과 공개 문서의 로컬 절대 경로 0건; Test Report의 Password·Encoding·Session ID·CSRF Token 값 0건 |
| Week 4 최종 회귀 | 자동 검증 완료 | 2026-09-14 10:43 KST `Tests run: 42, Failures: 0, Errors: 0, Skipped: 0` |
| Flyway V1 Migration | 실제 PostgreSQL 자동 검증 완료 | 빈 PostgreSQL 17.6 Testcontainer에서 `V1__create_tickets.sql` 적용 후 Schema Version v1 확인 |
| PostgreSQL Repository Adapter | 실제 PostgreSQL 자동 검증 완료 | `postgres` Profile에서 JDBC Adapter 선택, 생성·단건 조회·Status Row Mapping·누락 조회 확인 |
| PostgreSQL Constraint | 실제 PostgreSQL 자동 검증 완료 | 공백 제목과 허용 목록 밖 Status의 INSERT가 `DataIntegrityViolationException`으로 거부됨을 확인 |
| Week 6 전체 회귀 | 자동 검증 완료 | 2026-09-22 20:34 KST `Tests run: 49, Failures: 0, Errors: 0, Skipped: 0` |
| PostgreSQL Transaction Rollback | 실제 PostgreSQL 자동 검증 완료 | 같은 Transaction에서 첫 INSERT 1건 성공 뒤 두 번째 INSERT 실패, Transaction 종료 후 최종 Row 수 0 확인 |
| Spring Application Context 재생성 영속성 | 실제 PostgreSQL 자동 검증 완료 | 같은 PostgreSQL Container를 유지하고 Context A를 닫은 뒤 Context B의 새 Repository 객체로 같은 Row 조회; JVM Process·Container 재시작 근거는 아님 |
| Session CSRF Token 전달 | MockMvc 자동 검증 완료 | 인증된 Session으로 Token 응답의 구조를 확인하고 같은 Session·Token Header의 후속 POST `201`과 Controller 진입 확인; Browser E2E 근거는 아님 |
| Week 6 최신 전체 회귀 | 자동 검증 완료 | 2026-09-24 23:57 KST `Tests run: 53, Failures: 0, Errors: 0, Skipped: 0` |
| Week 6 Local Browser 수직 흐름 | 실제 Browser·PostgreSQL 확인 | AGENT 생성·조회와 안전한 Text·Event Delegation, Credential Cross-Origin Preflight·POST, USER·CSRF·익명 실패 |
| 새 Java Process의 PostgreSQL Row 조회 | 실제 Browser·PostgreSQL 확인 | 같은 PostgreSQL Container를 유지한 채 새 Java PID에서 기존 Ticket 조회; DB Container 재시작 근거는 아님 |
| Week 6 전체 회귀·품질 Gate | 자동 검증 완료 | 2026-09-29 Java 61개·JavaScript 12개 통과, ESLint 오류 0, Ticket UI Source Line Coverage `85.51%` |
| Week 7 접수 원자성 | 실제 PostgreSQL 자동 검증 완료 | 정상 Ticket·Message·Job 각 1건, Message·Job INSERT 실패 시 앞선 성공 INSERT까지 Rollback. 새 Integration Test 15개 통과 |
| V1에서 V2로 Migration | 실제 PostgreSQL 자동 검증 완료 | V1 상태에서 만든 기존 Ticket의 ID·제목·Status를 V2 적용 후 유지하며 Message·Job은 임의 생성하지 않음 |
| AI 출력 계약 검증 | Java Unit Test 완료 | 정상·판단 보류 형식, 누락·추가·중복 Field, Type·Enum·공백·Unicode 길이 경계 등 64개 통과. 내용 사실성·Provider 실패·DB 저장은 별도 |
| Week 7 HTTP 접수 연결 | 실제 PostgreSQL 자동 검증 완료 | USER·AGENT 인증 작성자, 원문·PENDING Job 저장, 본문 검증·CSRF·익명 거부, Message·Job 실패의 Rollback 등 HTTP Test 17개 통과 |
| Job 정책·실행권·예약 기반 | 실제 PostgreSQL 자동 검증 완료 | 정책 설정 10개·V2→V3 Migration 1개·실행권 18개 Test 통과. 예약 Commit 뒤 Provider Port 호출·결과 저장은 아래 처리 Test에서 확인 |
| Suggestion·복수 Category·Job 결과 저장 | 실제 PostgreSQL 자동 검증 완료 | 결과 Test 18개·V3→V4 Migration 1개 통과. 저장·복원·중복·이전 Attempt·부분 실패 Rollback·원문 보존 확인 |
| 단일 Job 처리 흐름 | 실제 PostgreSQL·통제된 Provider 자동 검증 완료 | 처리 Test 19개 통과. 예약 Commit·Row Lock 해제 뒤 호출, 고정 Message의 전송용 복사본, 출력 보완 한도·결과 저장 재시도·미완료 응답·일시 거절 확인. 실제 AI 호출은 0회 |
| Spring AI Provider Adapter | 실제 HTTP·통제된 응답 자동 검증 완료 | Adapter Test 44개 통과. 직렬화 Body·단일 전송·실패 분류·안전한 Log 확인. 실제 AI 모델 호출은 별도 |
| 실제 Java AI→PostgreSQL | 선택 Live Test 완료 | 2026-10-06 실제 HTTP 1회·`200`, 원문 보존·Job `SUCCEEDED`·Suggestion 1건·Category 1건과 별도 Live Test 1개 통과. 자동 Worker·Browser·요약 수동 평가는 별도 |
| 선택 자동 Worker·Rate Limit 대기 | 실제 PostgreSQL·통제된 Provider 검증 완료 | V5·대기 예약·자동 처리·설정·안전한 Log의 새 Test 31개 통과. 재시도 전 추가 예약 없음, 최종 FAILED 제외, 같은 JVM의 새 Context에서 정책·횟수·기한 유지 |
| 검증 객체의 제한된 저장 재시도 | 실제 PostgreSQL·통제된 Provider 검증 완료 | 기본 총 3회·최소 5초, 기존 결과 재조회·현재 Attempt·원래 기한 확인. 새 Test 15개, 실제 Rollback·저장 후 응답 유실 주입·조회 실패·종료 경쟁과 원문 보존 확인. 추가 AI 생성 없음 |
| Attempt별 결과와 조건부 RUNNING 복구 | 실제 PostgreSQL·통제된 Provider 검증 완료 | V7·결과 분류·DB 결과 확인 뒤의 조건부 Claim. 새 Test 20개, 경쟁 선점·조회 실패·원장 실패 Rollback과 새 Context의 재시도 미승인 유지 확인. 원격 Provider 조회는 미구현 |
| Worker의 실제 JVM 종료·재시작 | 실제 PostgreSQL·통제된 Provider 검증 완료 | 새 Test 5개. 첫 Java Process 종료를 확인하고 다른 PID로 같은 DB를 연결해 PENDING·결과 미확인/불명·재시도 금지·미래 대기를 확인. 정책·누적 예약·원래 기한·원문 유지 |
| AGENT 전용 AI 상태·제안 조회 | 실제 PostgreSQL·Security·MockMvc 검증 완료 | 새 Test 55개. 익명 401·USER 403, 최초 Message의 Job 조회, 다섯 상태·명시적 null·고정 실패 코드, 정합성/조회 오류의 안전한 500, 반복 조회의 불변과 Provider 미호출 |
| 담당자 최소 AI 조회 화면 | JavaScript·정적 Resource MockMvc 검증 완료 | 조회 Client·Text 표시·Page 연결의 새 Node Test 29개와 정적 파일·익명 API 차단의 MockMvc Test 5개 통과. 실제 Browser·새 유료 Worker는 별도 |
| Week 7 최신 회귀 근거 | 자동 검증 완료 | 2026-10-06 Java Clean Test 449개·JavaScript Test 133개, ESLint 통과. 실패·오류·건너뜀 0, 이번 회귀의 유료 AI 호출 0회 |
| HTTP·REST 예상 계약 | 작성 완료 | 생성·단건 조회의 정상·실패 Given–When–Then과 Method·Status·Header·Body 기록 |
| Spring Boot Dependency·Application 진입점 | 구현·컴파일 완료 | Spring Boot `4.1.1`, `spring-boot-starter-webmvc`, Maven Plugin과 `HelpdeskApplication` 적용 |
| Application Context·내장 Server | 기동 확인 | Java `25.0.4`, Tomcat `11.0.24`, Port `8080`에서 `Started HelpdeskApplication` 확인 |
| Ticket Web API·MockMvc | 완료 | 정상 생성·조회와 공백 제목·잘못된 JSON·ID Type 불일치·부재·대표 내부 실패를 MVC Test 7개로 검증 |
| Validation·오류 응답 | 완료 | `@NotBlank`·`@Valid`, `TicketNotFoundException`, `ResponseEntityExceptionHandler`와 `ProblemDetail` 적용 |
| Filter·Interceptor | 최소 실험 완료 | Request ID·Chain 진행, 요청별 시작 시각과 `preHandle()` 반환을 단위 Test로 확인하고 전체 Context Test에서 등록·404 완료 로그를 검증 |
| 실제 HTTP `curl.exe` Trace | 완료 | 정상 `201`·`200`, 공백 제목·잘못된 JSON·ID Type 불일치 `400`, 부재 `404`와 `application/problem+json` Body 확인 |
| 대표 `500` 계약 | Test 완료 | 안전한 `500 ProblemDetail`과 고정 오류 코드·예외 종류만 기록. 원문 Exception·Cause의 실패 Row는 Log로 출력하지 않음 |

2026-09-24까지의 53개 결과는 기존 HTTP·Security 계약, PostgreSQL 생성·조회·Constraint·Rollback, 같은 JVM의 Spring Context 재생성과 Server-side CSRF 전달의 근거였다. 2026-09-29에는 Local 실제 Browser E2E와 새 Java Process의 기존 PostgreSQL Row 조회를 별도로 확인했다. 동시성, Database Container·Volume 재시작이나 외부 운영 Database까지 검증한 것은 아니다.

2026-08-25 22:27 KST에 `spring-boot:run`으로 Spring Boot `4.1.1`을 기동했고, 22:28 KST에 `curl.exe --verbose --include --header "Accept: application/json" http://localhost:8080/`를 실행했다. `localhost`의 IPv6 Loopback `::1` 연결, `GET / HTTP/1.1`, `HTTP/1.1 404`와 JSON 오류 Body를 관찰했다. Controller가 없는 상태의 예상 결과이며 Ticket API 동작 근거는 아니다. 실행 후 Server를 종료하고 Port `8080`에 Listener가 없음을 확인했다.

Maven 변경 직후 VS Code가 `HelpdeskApplication.java`에 일시적인 오류 표시를 남겼지만, Maven Clean Compile과 실제 Server 기동은 성공했다. Java Language Server Workspace 정리와 Maven Project Reload 후 표시가 사라졌으므로 Source 오류가 아니라 Editor Dependency 동기화 문제로 판단했다.

JUnit 기준선은 `cdcbee0`, 대표 Exception Message 검증은 `944aede`, Policy 비교 기준선은 `6fb3365`, VIP 확장은 `3eb8b29` Commit에 기록했다.

2026-08-27 실제 Server에서 정상 생성은 `201 Created`와 `Location: /api/tickets/1`, 정상 조회는 `200 OK`와 Ticket JSON을 반환했다. 공백 제목과 잘못된 JSON, 숫자가 아닌 ID는 각각 안전한 `400 ProblemDetail`을 반환했고, 존재하지 않는 숫자 ID는 `404 ProblemDetail`을 반환했다. RFC 9457의 기본 `type`인 `about:blank`는 JSON에서 생략될 수 있으며, `instance`는 실제 Request Path로 설정되는 것을 관찰했다.

## Week 7 독립 Provider 비교

N01 합성 문의 한 건으로 Prompt-only와 Structured Outputs를 비교하는 [독립 스크립트 실행 안내](./scripts/week7-openai-pilot.md)를 추가했다. Spring Application과 Database를 사용하지 않고 Provider 요청·응답과 공통 출력 검증부터 확인한다. 실제 API 실행은 Helpdesk 키를 임시로 설정한 전용 PowerShell에서만 진행한다.

사용자가 전용 PowerShell에서 실행한 N01의 실제 Provider 응답 두 건은 별도 WIL의 예비 비교 기록에 남겼다. Spring AI 연결·AI Migration·PostgreSQL 제안 저장 검증은 아직 없다. 기존 Java 61개의 기록과 독립 JavaScript 검증을 구분한다.

13건 × 두 방식 × 두 반복의 본 평가를 준비하는 `scripts/week7-ai-evaluation.mjs`를 추가했다. 현재는 유료 호출 없는 `--dry-run`만 지원한다. 입력은 제목·본문만 보내도록 구성하고, 기대 분류·우선순위는 평가자용 데이터로 분리했다. Label은 검토 후보이며 요약·Injection의 내용은 자동 정답 처리하지 않는다. 새 준비 Test 14개, Pilot Test 15개와 기존 UI Test 12개가 합쳐 41개 통과했다.

공통 Prompt `prompt-v3-abstain-draft`에는 문의 의미를 해석할 수 없어 요약 자체를 만들 수 없는 경우와, 요청은 이해하지만 분류·영향 정보가 부족한 경우의 구분을 추가했다. 두 방식에 같은 지시를 적용하고 기존 13건·52회 계획은 유지한다. 새 준비 Test 2개를 더한 전체 JavaScript 56개가 통과했으며, 실제 Model의 보류 판단을 시험한 것은 아니다.

```powershell
node --test src/test/js/ticket-ui.test.mjs src/test/js/week7-openai-pilot.test.mjs
node scripts/week7-openai-pilot.mjs --dry-run
node --test src/test/js/week7-ai-evaluation.test.mjs
node scripts/week7-ai-evaluation.mjs --dry-run
```

### 독립 가짜 Tool Calling 실험

`scripts/week7-tool-calling-spike.mjs`는 허용 함수 `find_current_ticket`과 빈 인자 `{}`만 받아 Server가 선택한 Ticket ID로 가짜 함수를 실행한다. 금지된 함수·추가 인자는 실행 전에 거부하며, 함수 실행 중 실패와 구분한다. 실제 Provider·Database·Ticket 상태 변경은 연결하지 않았다.

2026-10-03 네 독립 Case의 실행 횟수는 정상 `1`·금지 함수 `0`·금지 인자 `0`·실행 중 실패 `1`이었다. 자동 재시도는 없다. 새 Test 13개와 기존 JavaScript 41개를 함께 실행해 총 54개가 통과했다. 이 결과는 기존 Java·PostgreSQL Test 재실행의 근거가 아니다.

```powershell
node scripts/week7-tool-calling-spike.mjs --run-fake-tools
node --test src/test/js/week7-tool-calling-spike.test.mjs
```

### 최초 Message와 Job의 접수 원자성

`V1__create_tickets.sql`은 유지하고 `V2__create_ticket_messages_and_pending_jobs.sql`을 추가했다. Message는 Ticket을 참조하고 Job은 입력 Message를 참조한다. 같은 Message의 초기 Job 중복 등록은 `UNIQUE (input_message_id)`로 막는다. 기존 Ticket의 없는 본문을 복사하거나 만들어 채우지 않는다.

`postgres` Profile의 [TicketReceiptApplicationService](./src/main/java/lab/helpdesk/ticket/application/TicketReceiptApplicationService.java)는 Ticket·최초 Message·`PENDING` Job을 하나의 `@Transactional` 호출에서 저장한다. [접수 Integration Test](./src/test/java/lab/helpdesk/ticket/application/TicketReceiptIntegrationTest.java)는 실제 JDBC 저장의 성공 횟수를 기록하고, 다음 INSERT의 DB Constraint 실패 뒤 세 Table의 최종 Row 수를 확인한다. [Migration Test](./src/test/java/lab/helpdesk/ticket/repository/TicketReceiptMigrationIntegrationTest.java)는 V1에 기존 Row를 만든 다음 V2를 적용한다. 두 Test의 15개 Case와 전체 Java 76개, 기존 JavaScript 54개가 통과했다.

첫 Service 실습 이후 10/5에는 `postgres`의 `POST /api/tickets`를 접수 Service에 연결했다. 새 요청은 `title`·`body`를 받으며 앞뒤 Java `String.strip()` 공백을 제외한 본문의 상한은 2,000 Unicode Code Point다. DTO와 Domain에서 같은 길이 규칙을 적용하고, 통과한 원문은 공백까지 그대로 저장한다. V2까지의 Job 상태는 `PENDING`만 허용했고 이후 V3에서 실행권·예약, V4에서 Suggestion 저장을 추가했다. 자동 Polling은 후속 단계다.

Message의 `author_username`은 작성자 이름의 Snapshot이며 영속 User ID나 권한 판정 근거가 아니다. HTTP에서는 `Authentication.getName()`만 Service에 전달한다. 서로 다른 USER·AGENT로 실제 Form Login을 수행한 MockMvc Session을 재사용해 작성자를 검증하고, Browser의 다른 작성자·Role 주장이 저장 작성자를 바꾸지 않는 것을 확인했다. 인증 계정은 In-memory이고 Ticket·Message·Job은 PostgreSQL에 저장된다.

생성 Controller는 Profile별로 분리했다. `InMemoryTicketCreationController`는 기존 제목 전용 학습 경로를 보존하며 Message·Job을 저장하지 않는다. `TicketReceiptController`는 PostgreSQL 접수를 수행하고 `TicketController`는 두 모드의 공통 조회를 담당한다. 저장 Profile은 `in-memory` 또는 `postgres` 중 하나를 선택한다. 새 UI는 본문을 함께 보내므로 접수 검증은 `postgres,local-browser` 실행에서 진행한다. JavaScript 함수의 본문 생략 호출은 이전 In-memory 실험에만 사용한다.

[HTTP 접수 Integration Test](./src/test/java/lab/helpdesk/ticket/web/TicketReceiptHttpIntegrationTest.java)는 실제 Security Filter Chain·MVC·Service·JDBC·PostgreSQL을 사용한다. 본문 검증의 `400`과 CSRF의 `403`은 Service 미호출·세 Table의 Row 0건으로 확인하며, Message·Job 저장 실패의 `500`은 접수 전체 Rollback과 안전한 Log를 확인한다. Testcontainer 연결을 확인한 뒤에만 Test DB를 비운다. 새 Test 17개, Domain 길이 Test 4개를 포함한 전체 Java 207개·JavaScript 79개와 ESLint가 통과했다. 변경한 본문 UI의 실제 Browser E2E와 AI 호출은 이번 검증에 포함하지 않았다.

`201`의 응답 Body는 기존 `id`·`title`·`status`를 유지하며 AI 완료를 뜻하지 않는다. 기존 DB `btrim()` CHECK와 Application의 Unicode 공백·길이 검증은 같지 않다. 이번에 본문 길이 CHECK Migration이나 전체 HTTP 요청 Byte 상한을 추가하지는 않았다.

```powershell
.\mvnw.cmd "-Dtest=TicketReceiptIntegrationTest,TicketReceiptMigrationIntegrationTest" test
.\mvnw.cmd clean test
```

### Java AI 출력 계약 검증

[AiSuggestionOutputValidator](./src/main/java/lab/helpdesk/ai/validation/AiSuggestionOutputValidator.java)는 Model의 JSON 문자열을 네 Field 계약에 맞춰 검사한다. `SUGGEST`에서는 공백이 아닌 요약·중복 없는 분류 목록·허용된 우선순위가 필요하다. `UNDETERMINED`는 그대로 보존한다. `ABSTAIN`에서는 나머지 세 Field가 명시적인 `null`이어야 한다.

요약 상한은 생성자 인자로 받는다. [Unit Test](./src/test/java/lab/helpdesk/ai/validation/AiSuggestionOutputValidatorTest.java)의 200자는 초안의 실험값이며 Runtime 기본값을 확정한 것은 아니다. 양끝 공백을 제외한 Unicode Code Point 수를 검사하되 요약 문자열을 바꾸거나 잘라내지 않는다. 중복 JSON Property와 뒤에 붙은 추가 JSON도 거부하며, 오류에는 입력·Parser 원문 대신 고정 코드만 남긴다.

검증기는 순수 Java 객체다. 결과 저장 Service는 이 검증기가 반환한 객체를 받으며 현재 Attempt와 상태를 별도로 확인한다. 아래 단일 Job 처리와 Provider Adapter에 연결했고 Worker는 명시적으로 활성화할 때만 등록한다. 내용이 틀린 요약도 구조를 통과할 수 있고, HTML처럼 생긴 문자열은 Text로 보존한다. Provider 거부를 `ABSTAIN`으로 변환하거나 구조 통과만으로 Tool 실행·Ticket 상태 변경을 허용하지 않는다. 첫 구현의 새 Unit Test 64개와 전체 Java 140개·JavaScript 54개가 통과했다.

```powershell
.\mvnw.cmd "-Dtest=AiSuggestionOutputValidatorTest" test
```

### Job 정책 Snapshot·실행권·호출 예약

`postgres`의 [AiJobPolicy](./src/main/java/lab/helpdesk/ai/job/AiJobPolicy.java)는 `helpdesk.ai.job` 설정을 새 Job에 저장한다. 기본값은 전체 생성 3회·추가 보완 1회, 요청 대기 60초·Attempt 실행권 120초·Backoff 5초·전체 처리 300초다. 전체 기한은 최초 Claim부터 계산하고 Queue 대기는 제외한다. 기존 Job은 설정 변경·재예약 후에도 원래 정책·누적 횟수·마감 시각을 유지한다.

[V3 Migration](./src/main/resources/db/migration/V3__add_job_policy_and_execution_reservations.sql)은 V1·V2를 수정하지 않고 Job Column과 예약 원장을 추가한다. 기존 V2 Job은 기본 정책으로 이행하고 출처를 `V2_MIGRATION`으로 표시한다. Application의 새 등록은 `APPLICATION`으로 표시한다. 예약 원장에는 Job·Attempt·요청 종류·예약 시각만 저장하며 문의 본문·Prompt·Credential을 복사하지 않는다.

[AiSuggestionJobClaimService](./src/main/java/lab/helpdesk/ai/job/AiSuggestionJobClaimService.java)는 `REQUIRES_NEW` Transaction에서 실행권 변경과 원장 INSERT를 함께 Commit한 뒤 반환한다. `PENDING` 조회는 `FOR UPDATE SKIP LOCKED`를 사용한다. `RUNNING`의 기한 만료만으로 재호출하지 않으며, 아래 V7 단계에서 기존 결과·현재 Attempt별 분류·횟수·기한 확인 뒤의 조건부 복구를 연결했다. 실패 기록·출력 보완은 현재 Attempt만 반영한다.

[실행권 Integration Test](./src/test/java/lab/helpdesk/ai/job/AiSuggestionJobExecutionIntegrationTest.java)는 경쟁 Claim·복구, 잠긴 Job 건너뛰기, 원장 실패 Rollback, 전체·보완 한도, 처리 기한과 정책 복원을 실제 PostgreSQL에서 확인한다. 새 Repository 객체의 복원 Test는 Application·JVM 재시작 근거와 구분한다. 설정 10개·Migration 1개·실행권 18개와 전체 Java 236개·JavaScript 79개가 통과했다.

이 단계는 Worker의 실행권 기반이다. V3 검증 뒤 이어서 아래 V4 결과 저장과 단일 처리·Provider Adapter를 추가했다. V3 실행권 Test 자체에는 자동 Polling이나 실제 AI 호출이 없으며 유료 API 호출은 0회다.

```powershell
.\mvnw.cmd "-Dtest=AiJobPolicyTest,AiJobPolicyMigrationIntegrationTest,AiSuggestionJobExecutionIntegrationTest" test
```

### 제안·복수 Category와 Job 결과 저장

[V4 Migration](./src/main/resources/db/migration/V4__add_suggestions_and_result_completion.sql)은 기존 Migration을 바꾸지 않고 `ticket_suggestions`와 `ticket_suggestion_categories`를 추가한다. 같은 Job의 제안은 `UNIQUE (job_id)`, 같은 제안의 분류 중복은 `(suggestion_id, category)` 복합 Primary Key로 막는다. Foreign Key와 허용값·공백 CHECK를 적용하며 원문이나 전체 Provider 응답을 복사하지 않는다.

[AiSuggestionResultService](./src/main/java/lab/helpdesk/ai/suggestion/AiSuggestionResultService.java)는 검증된 객체와 Claim을 받아 현재 `RUNNING`·Attempt·전체 처리 기한을 Row Lock 안에서 확인한다. `SUGGEST`의 부모 제안·모든 분류·`SUCCEEDED`를 한 결과 Transaction으로 저장한다. 유효한 `ABSTAIN`이면 제안을 만들지 않고 `ABSTAINED`를 기록한다. 이미 완료한 Job이나 이전 Attempt는 변경 없이 `NOT_CURRENT`를 반환한다.

중간 저장이나 완료 UPDATE가 실패하면 결과 변경만 Rollback하고 접수 원문·호출 예약은 유지한다. 이전 `RUNNING`이 남으며 `FAILED`는 자동 기록하지 않는다. DB 예외는 실패 Row와 Cause를 복사하지 않는 고정 코드로 전달한다. Commit 여부가 불명확할 때 사용할 내부 결과 조회는 읽기 전용 `REPEATABLE_READ`로 여러 SELECT의 Snapshot을 맞춘다. HTTP 조회 API·자동 재시도·AI 호출은 추가하지 않았다.

[결과 Integration Test](./src/test/java/lab/helpdesk/ai/suggestion/AiSuggestionResultIntegrationTest.java) 18개와 [V3→V4 Migration Test](./src/test/java/lab/helpdesk/ai/suggestion/AiSuggestionResultMigrationIntegrationTest.java) 1개가 통과했다. 실제 PostgreSQL에서 복수 분류·불확실한 값·Text 복원, 늦은 이전 Attempt·반복·동시 완료, Category·상태 저장 실패와 Rollback을 확인했다. 전체 Java Clean Test 255개·JavaScript 79개와 ESLint도 통과했다. Test의 JSON은 합성 입력이며 이 결과를 실제 Provider·Worker·Browser 연결로 해석하지 않는다.

요약 상한 200은 이 Test의 검증기 인자다. Runtime 기본값은 아직 확정하지 않았고 DB는 TEXT·공백 CHECK를 사용한다. 빈 분류 목록은 Java 검증기가 거부하고 결과 Service가 전체 저장을 묶는다. 일반 Row CHECK만으로 자식 Row 최소 개수나 Job과 Suggestion의 상태 일치를 강제하지 않으므로 직접 SQL Writer를 추가할 때는 이 경계를 검토한다.

```powershell
.\mvnw.cmd "-Dtest=AiSuggestionResultIntegrationTest,AiSuggestionResultMigrationIntegrationTest" test
```

### 예약부터 결과 저장까지의 단일 Job 처리

[AiSuggestionJobProcessor](./src/main/java/lab/helpdesk/ai/processing/AiSuggestionJobProcessor.java)는 한 Job의 Claim·입력 조회·전송용 개인정보 처리·Provider 호출·출력 검증·결과 저장을 연결한다. Claim의 예약 Transaction이 Commit된 다음 Provider Port를 호출하며, 호출 대기 중 Job Row Lock이나 바깥 Transaction을 유지하지 않는다. 아래 선택 Worker 설정에서만 자동 실행 Bean과 Scheduler를 등록한다.

[입력 Repository](./src/main/java/lab/helpdesk/ai/input/JdbcAiSuggestionInputRepository.java)는 Job에 고정한 `input_message_id`의 원문을 읽는다. 새 Message가 추가돼도 처리 대상을 바꾸지 않는다. DB 원문은 유지하고 [개인정보 Guard](./src/main/java/lab/helpdesk/ai/input/AiInputPrivacyGuard.java)가 만든 전송용 복사본만 Provider Port에 전달한다. 전송 전에 현재 Attempt·예약·실행권·전체 기한을 다시 확인한다.

필수 Field만 누락된 응답은 기존 보완 한도와 Backoff에 따라 다음 예약을 준비한다. 추가 Field·잘못된 JSON·다른 계약 위반과 Provider 거부·설정 오류는 구분해 실패를 기록한다. 외부 처리 결과가 불명확하면 현재 `RUNNING`을 유지하며 곧바로 재호출하지 않는다.

결과 저장 실패 시 같은 Job의 Commit 결과를 먼저 조회한다. 아직 저장되지 않았다면 검증된 객체를 유지하고 `storeValidatedResult`로 DB 저장만 다시 시도할 수 있다. 이 경로에서는 Provider 호출과 예약 횟수를 추가하지 않는다. 결과 저장이나 Provider 실패가 이미 접수한 Ticket·Message를 되돌리지 않는다.

[처리 Integration Test](./src/test/java/lab/helpdesk/ai/processing/AiSuggestionJobProcessorIntegrationTest.java) 17개는 실제 PostgreSQL과 통제된 Provider 응답을 사용한다. 예약의 다른 Connection 조회·별도 Transaction의 `FOR UPDATE NOWAIT`, 이전 Attempt 거부·보완 상한, Category 저장 실패와 저장 전용 재시도를 확인했다. 이 Test의 유료 API 호출은 0회이며 독립 Node 실험의 실제 AI 결과와 구분한다. 전체 Java 272개·JavaScript 79개와 ESLint가 통과했다.

```powershell
.\mvnw.cmd "-Dtest=AiSuggestionJobProcessorIntegrationTest" test
```

### Spring AI OpenAI Provider Adapter

[SpringAiOpenAiSuggestionProvider](./src/main/java/lab/helpdesk/ai/provider/SpringAiOpenAiSuggestionProvider.java)는 `AiSuggestionProvider` Port 뒤에서 Spring AI 2.0.1의 `OpenAiChatModel`을 사용한다. Starter가 아닌 Library만 추가하고 명시적으로 생성한다. 일반 Application 실행·`mvn test`가 유료 호출을 시작하거나 전역 `OPENAI_API_KEY`를 자동으로 읽지 않는다. Key와 요약 상한은 생성자 인자다. 200자는 기존 Test의 실험값을 유지하며 운영 기본값을 새로 정하지 않았다.

Model은 `gpt-6-luna`, reasoning은 `none`, 출력 상한은 600 Token, `store=false`, Structured Output을 사용한다. 이 Adapter는 Chat Completions 경로다. 기존 Node 비교의 Responses API와 업무 판단 기준을 맞추되 실행 근거는 구분한다. [Spring AI OpenAI 문서](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)

[단일 전송 HTTP Client](./src/main/java/lab/helpdesk/ai/provider/SingleAttemptOpenAiHttpClient.java)는 SDK 재시도와 HTTP 연결 재시도·자동 Redirect·환경 Proxy를 끈다. 직렬화가 끝난 실제 Body를 개인정보 Guard와 Credential 검사에 통과시킨 뒤 한 번 전송한다. 요청은 32 KiB, 응답은 64 KiB 상한을 둔다. DB의 원문은 변경하지 않으며 Guard는 설정한 민감 값만 검사하는 현재 범위를 유지한다.

명시적 거부, 인증·과금 설정 오류, 완료되지 않은 출력, 일시적 Rate Limit, 결과 불명 실패를 구분한다. 유효한 `Retry-After`의 초·HTTP-date 값은 최소 대기로 전달하고 Adapter 안에서는 재호출하거나 대기하지 않는다. Processor는 일시적 거절의 안전한 Metadata를 Worker에 전달한다. 아래 Worker는 확인된 Rate Limit과 유효한 최소 대기만 재예약하며, 결과 불명 복구는 Attempt별 코드·기한·한도를 확인하는 별도 Claim으로 처리한다. 응답 원문·Credential·SDK 예외 Cause는 오류에 복사하지 않는다. 사용량이나 반환 Model·Service Tier에 대한 요금 확인이 없으면 비용을 0으로 기록하지 않는다.

[Adapter HTTP Test](./src/test/java/lab/helpdesk/ai/provider/SpringAiOpenAiSuggestionProviderTest.java) 44개는 실제 HTTP·Spring AI·SDK와 통제된 로컬 응답을 사용한다. 재시도 대상 Status·Timeout에도 요청 1회, 마스킹·Credential 검사 실패에는 요청 0회를 확인했다. 요청별 옵션의 기본 Model 덮어쓰기도 실제 전송 Body Assertion으로 잡아 수정했다. PostgreSQL 처리 Test는 19개이며, 전체 Java Clean Test 318개가 통과했다. 이 회귀 검증의 유료 호출은 0회다. 실제 AI 호출·자동 Worker·Browser E2E는 별도 실행 근거가 필요하다.

```powershell
.\mvnw.cmd "-Dtest=SpringAiOpenAiSuggestionProviderTest,AiSuggestionJobProcessorIntegrationTest" test
```

### 실제 Java AI 호출과 PostgreSQL의 선택 실행

[실행기](./scripts/week7-java-provider-live.mjs)와 [Live Experiment](./src/test/java/lab/helpdesk/ai/provider/SpringAiOpenAiSuggestionProviderLiveExperiment.java)는 합성 문의 한 건만 처리한다. 실행기는 기존 Node 실험과 같은 날짜별 비용 원장을 사용하고 Java 실행 전에 한 번의 비용을 예약한다. 실험은 새 PostgreSQL Testcontainer에서 접수 Service·예약·실제 Spring AI 호출·검증·결과 저장을 연결한다. 기존 로컬 DB에 정리 SQL을 실행하지 않는다.

Live Experiment는 일반 `mvn test`의 Class 이름 규칙에서 제외한다. Helpdesk 전용 키·당일 확인·파일 원장의 예약과 Lock이 있어야 호출하며, 자식 Process에 일반 `OPENAI_API_KEY`를 넘기지 않는다. 전용 PowerShell에 설정한 키를 명시적으로 `HELPDESK_OPENAI_API_KEY`로 전달한다. 전역 환경 변수는 수정하지 않는다. 키 값은 출력하지 않는다.

```powershell
# Helpdesk 키가 있는 전용 창에서만 실행한다. 다른 프로젝트 창에서 실행하지 않는다.
$env:HELPDESK_OPENAI_API_KEY = $env:OPENAI_API_KEY
node .\scripts\week7-java-provider-live.mjs --dry-run
node .\scripts\week7-java-provider-live.mjs --preflight
```

`--preflight`는 API Key 없이 Maven Wrapper·Java 25 기동을 점검한다. Live 실행도 예약 전에 이 점검을 거친다. 자식 환경에는 `PATHEXT`·`COMSPEC`을 포함한 필요한 Windows 변수만 전달한다. 단순 종료 코드 `0`뿐 아니라 Maven 3.9.16·Java 25의 출력이 있어야 통과한다. 원문 진단이나 환경 변수 값은 출력하지 않는다.

Live 실행에는 `--live --confirm-helpdesk-key --confirm-synthetic --day YYYY-MM-DD --budget-usd 1`을 명시한다. 해당 날짜의 원장이 처음 만들어지는 경우에만 `--prior-estimated-usd`로 확인한 이전 누적 비용을 추가한다. 기존 원장이 있으면 이전 비용을 다시 넣지 않는다. 날짜나 이전 비용을 임의로 채우지 않는다. $1은 하루 누적 상한이며 이번 실행만의 예산이 아니다.

추가 비용 확인 없이 한 건의 실험을 진행하도록 명시적으로 요청한 경우에는 `--proceed-with-unknown-prior`를 사용한다. 확인한 이전 비용을 넣는 옵션과 함께 사용할 수 없다. 새 원장은 이전 비용과 하루 총액을 `null`·미확인으로 표시하고 이 실행기에서 확인한 사용량만 누적한다. 이전 비용을 0으로 확인했다고 기록하거나 하루 전체가 $1 이내라고 주장하지 않는다. 이 예외 원장에서는 생성 예약 한 번만 허용하며 자동 재실행·다른 실행기의 묵시적 승계를 막는다. 기존 원장의 비용·예약·보류 상태는 초기화하지 않는다.

32 KiB 요청 Byte 상한·600 출력 Token을 사용한 보수적 1회 예약은 $0.009004다. 응답 Token 수와 반환 Model·Service Tier가 확인되면 사용량 추정치로 정산한다. 사용량·실행 결과가 불명확하면 원장을 잠정 보류하고 추가 호출을 막는다. 이 원장은 실행기 밖의 호출이나 계정의 실제 청구를 자동 수집하지 않는다.

Windows 기동 오류를 수정한 뒤 사용자가 명시적으로 재실행할 때만 `--recover-launcher-once`를 함께 사용할 수 있다. 적용 대상은 이전 비용 미확인 원장의 첫 예약 한 건이 `UNKNOWN_COST`로 보류되고 진행 중 예약이 없는 경우다. 수정된 Wrapper의 사전 점검을 통과한 뒤 기존 보류 금액·횟수와 사유를 보존하고 새 예약을 한 번 추가한다. 원장 삭제·횟수 초기화·이전 비용의 0원 판정은 하지 않는다. 복구 이력은 `launcherRecovery`에 남기며 같은 복구를 반복하거나 진행 중 예약·상한 초과를 우회할 수 없다. 이는 합성 Live 실험의 수동 기동 복구이며 Runtime Job의 Provider 재시도 정책과 별개다.

출력은 HTTP 시도 횟수·Status·사용량, 예약 횟수·원문 보존·제안 Row 수·Job 완료 여부와 비용 집계 범위만 포함한다. Prompt·요약·Credential·Cookie는 Log에 출력하지 않는다. 실행기 Unit Test 25개는 가짜 Java 실행 결과로 기동·예산·판정 경계를 확인하며 실제 AI Test를 대신하지 않는다.

2026-10-06 선택 Live 실행에서 합성 문의 `SYNTHETIC_LOGIN_RECOVERY`를 처리했다. 실제 HTTP 전송은 1회·`200`, 결과는 `STORED`였으며 PostgreSQL 원문 보존·Job `SUCCEEDED`·Suggestion 1건·Category 1건을 확인했다. 별도 Live JUnit Test는 실패·오류·건너뜀 없이 한 건 통과했다. 입력 1,169·출력 54 Token과 해당 호출 추정치 `$0.000173125`를 기록했다. 이전 기동의 보류 예약은 유지하며, 이전 비용과 하루 전체 합계는 미확인이다.

이 실행은 접수 Service 뒤 Processor를 직접 한 번 호출했다. Browser E2E·자동 Worker·내용 수동 채점은 별도 확인한다. 회귀 Java Test 318개와 이 유료 Live Test 한 건을 구분해 기록한다.

### 선택 자동 Worker와 영속 재시도 대기

[Worker](./src/main/java/lab/helpdesk/ai/processing/AiSuggestionJobWorker.java)는 한 번의 Tick에서 Processor를 실행하고, 확인된 Rate Limit과 유효한 최소 대기가 있으면 다음 실행 가능 시각을 DB에 기록한다. 재시도를 예약하는 것만으로 호출 횟수를 늘리지 않는다. 다음 Claim이 현재 상태·시각·전체 한도를 다시 확인하고 새 예약을 Commit한 뒤 Provider를 호출한다.

[V5 Migration](./src/main/resources/db/migration/V5__add_rate_limit_retry_kind.sql)은 `TEMPORARY_RETRY`와 `PROVIDER_RATE_LIMITED`를 허용한다. 기존 원문·정책 Snapshot·예약·제안과 V1~V4는 유지한다. `next_attempt_at`은 DB 시각에 `max(Backoff, Retry-After)`를 더해 저장한다. 재시도는 전체 생성 횟수만 사용하고 출력 보완 횟수는 늘리지 않는다. 최소 대기가 전체 기한을 넘으면 더 일찍 호출하지 않고 기한 종료 때 Job을 실패로 마감한다.

[설정](./src/main/java/lab/helpdesk/ai/processing/AiSuggestionWorkerConfiguration.java)은 `postgres` Profile과 `helpdesk.ai.worker.enabled=true`에서만 활성화된다. 기본은 꺼짐이며 확인 간격 `helpdesk.ai.worker.poll-delay-ms`의 기본값은 1,000ms다. Provider·개인정보 Guard·출력 검증기를 명시적으로 제공해야 하며, 전역 Key나 Runtime 요약 상한을 자동 선택하지 않는다. 설정값을 켜는 것만으로 Live Provider가 만들어지지는 않는다.

최종 `FAILED`는 일반 Polling 대상에서 제외한다. 크레딧·설정 오류를 같은 요청으로 반복하지 않는다. 대기 Header가 없는 Rate Limit과 다른 미승인 일시 거절은 V7의 결과 코드로 복구 경로에서도 재호출을 막는다. 아래에 조건부 결과 불명 복구를 연결했으며, 관리자 재개·응답 객체의 영속 복원과 실제 유료 Runtime 조립은 후속 단계다. 메모리에 남은 객체의 저장 재시도는 아래와 같이 연결했다. Scheduler 오류는 메시지·Cause 없이 `AI_WORKER_TICK_FAILED`만 Log에 남긴다.

재시도·Migration Test 11개, Worker Test 9개, 설정 Test 5개, Scheduler Test 2개, Context Test 4개가 통과했다. 같은 PostgreSQL을 유지한 Context 재시작에서 미실행 `PENDING`과 미래 대기 시각을 이어갔고, 최종 실패와 Lease가 만료된 결과 불명 `RUNNING`은 임의로 재실행하지 않았다. 이는 같은 JVM의 Spring Context 재시작이며 실제 JVM Process·DB Container 재시작은 별도 검증한다. 이번 Provider 응답은 통제된 합성값이고 새 유료 AI 호출은 0회다.

```powershell
.\mvnw.cmd "-Dtest=AiRateLimitRetryIntegrationTest,AiRateLimitRetryMigrationIntegrationTest,AiSuggestionJobWorkerIntegrationTest,AiSuggestionWorkerConfigurationTest,AiSuggestionWorkerSchedulerTest,AiSuggestionWorkerContextIntegrationTest" test
```

### 검증된 응답 객체의 저장 재시도

첫 결과 저장이 실패하면 Worker가 검증된 객체 한 개를 현재 Process 메모리에 보관하고, 다음 Tick에서 같은 객체의 DB 저장만 재시도한다. 기본값은 최초 저장을 포함한 총 3회·추가 저장 간 최소 5초다. `helpdesk.ai.worker.max-storage-attempts`와 `helpdesk.ai.worker.storage-retry-delay-ms`로 설정한다. 재시도 시각 전에는 저장하지 않고, 대기 동안 DB Transaction·Row Lock을 유지하거나 Thread를 Sleep시키지 않는다.

저장 전에 Job·제안을 재조회한다. Commit이 완료됐다면 기존 결과를 사용하며, 조회 실패를 ‘결과 없음’으로 바꾸지 않는다. 현재 Attempt가 교체됐거나 Job이 종료됐다면 이전 객체를 반영하지 않는다. 조회 뒤의 경쟁도 있으므로 실제 결과 Transaction의 현재 Attempt·RUNNING·기한 확인은 그대로 유지한다.

세 번을 소진한 뒤에도 먼저 DB 결과를 확인한다. 결과가 없는 현재 실행만 `RESULT_STORAGE_RETRY_EXHAUSTED`로 마감한다. 원래 전체 처리 기한이 먼저 끝나면 추가 저장을 중단한다. DB 장애로 종료 기록까지 확인할 수 없다면 `STORAGE_STATE_UNCONFIRMED`를 반환하며 실패가 Commit됐다고 단정하지 않는다. 메모리 객체는 기한에 해제하고, DB가 돌아오면 기존 만료 정리가 해당 Job을 종료한다.

이 저장 주기는 생성 예약·보완 횟수·Attempt·기한을 변경하지 않는다. 저장 시도 횟수는 현재 Worker가 보관한 객체에 대한 횟수이며 기존 Job의 생성 정책 Snapshot을 대체하지 않는다. Process가 종료되면 객체와 저장 시도 횟수도 사라진다. 재시작 후의 결과 불명 복구나 응답 객체 영속 보관은 별도 계약이다. 객체를 보관하는 동안 같은 Worker는 새 Job을 추가로 받지 않아 메모리 Queue가 늘어나지 않는다.

[저장 재시도 Test](./src/test/java/lab/helpdesk/ai/processing/AiSuggestionStorageRetryIntegrationTest.java) 12개, [V5→V6 Migration Test](./src/test/java/lab/helpdesk/ai/job/AiStorageRetryMigrationIntegrationTest.java) 1개와 설정 Test 추가 2개가 통과했다. 실제 PostgreSQL Rollback·원문 보존·기존 결과·새 Attempt·기한·ABSTAIN 완료를 확인했다. 저장 Commit 뒤의 응답 유실과 조회 실패는 Test용 주입이며 실제 네트워크 단절 실험은 아니다. 5초 경계는 통제된 Clock으로 검사했다. 전체 Java Clean Test 364개·JavaScript 104개·ESLint도 통과했으며 이 회귀의 유료 AI 호출은 0회다.

[V6](./src/main/resources/db/migration/V6__allow_storage_retry_failure.sql)는 새 실패 코드만 허용한다. V1~V5와 원문·정책·예약·기존 제안은 바꾸지 않는다. 설정 생성자가 늘어나며 발생한 Binding 오류는 Canonical Constructor를 명시해 수정했고, 실제 Binder의 기본값·설정값 Test로 확인했다.

```powershell
.\mvnw.cmd "-Dtest=AiSuggestionStorageRetryIntegrationTest,AiStorageRetryMigrationIntegrationTest,AiSuggestionWorkerConfigurationTest" test
```

### Attempt별 결과와 조건부 RUNNING 복구

[V7](./src/main/resources/db/migration/V7__record_attempt_result_classification.sql)은 예약 원장에 `result_code`를 추가한다. 새 예약은 `UNCONFIRMED`, 결과 불명 관찰은 `OUTCOME_UNKNOWN`, 알려진 실패의 미승인 자동 복구는 `AUTO_RETRY_BLOCKED`다. 기존 예약에는 결과 분류가 없으므로 보수적으로 금지 코드를 적용한다. 이 이행은 과거 Provider의 실제 거절을 증명하지 않으며, 원문·정책·횟수·기한·기존 예약 Column과 제안은 유지한다.

Processor는 확인한 Provider 실패·거절과 잘못된 출력의 코드부터 Commit한다. 유효한 Rate Limit 대기·필수 Field 보완은 그 다음 별도의 승인된 PENDING 경로로 기록한다. 새 Attempt는 자기 결과 코드를 사용하므로 Job에 남은 이전 `last_failure_code`와 혼동하지 않는다. 금지 코드는 결과 불명 경로를 차단하지만 이미 승인해 저장한 대기 경로까지 취소하지 않는다.

Worker는 검증 객체가 있으면 저장 재시도를 우선하고, PENDING 처리가 없을 때 만료된 결과 미확인·불명 실행을 찾는다. DB의 기존 결과를 읽은 뒤 복구 Claim에서 현재 Attempt·RUNNING·허용 코드·Lease+Backoff·원래 전체 기한·남은 전체 생성 한도·기존 제안 부재를 다시 확인한다. 조회 실패는 `RECOVERY_STATE_UNCONFIRMED`이며 추가 예약·호출을 하지 않는다. 제안 없이 완료된 ABSTAIN도 재생성하지 않는다.

결과 분류와 복구 Claim은 같은 Job Row Lock을 사용한다. 복구는 짧은 `READ COMMITTED` Transaction에서 `FOR UPDATE SKIP LOCKED`로 먼저 잠그고, 뒤의 SQL 문장이 새 Snapshot으로 코드를 확인한다. 조건을 만족하면 새 Attempt·원장 INSERT를 함께 Commit하고 Provider는 Transaction 밖에서 호출한다. 예약 INSERT 실패는 Attempt 증가도 Rollback한다. 이전 Attempt는 현재 결과 코드를 바꾸지 못하며, 금지된 실행을 불명 상태로 되돌리지 않는다.

[복구 Test](./src/test/java/lab/helpdesk/ai/processing/AiSuggestionRecoveryIntegrationTest.java) 18개, [V6→V7 Test](./src/test/java/lab/helpdesk/ai/job/AiAttemptResultMigrationIntegrationTest.java) 1개와 Context 추가 1개가 통과했다. 기존 Context의 불명 실행 사례도 조건부 복구로 갱신했다. 실패 주입·경쟁 Claim·기존 Commit·대기 Hint 없는 거절·원문 보존을 실제 PostgreSQL에서 확인했다. 전체 Java Clean Test 384개·JavaScript 104개·ESLint가 통과했고 새 유료 호출은 0회다.

현재 Provider Port에는 원격 결과 조회가 없다. 응답을 받았더라도 코드 Commit 전에 Process가 끝나거나 DB 기록이 실패하면 원장에는 `UNCONFIRMED`가 남을 수 있다. 이를 미실행이라고 판단하거나 예약을 반환하지 않는다. 검증 객체가 Process와 함께 사라지면 기존 DB 결과 확인 뒤 위 정책 안에서만 새 생성한다. DB의 제안 한 건은 외부 실행의 정확히 한 번을 보장하지 않는다. 같은 JVM의 Context Test에 이어 아래의 별도 Process Test로 종료·재시작을 확인했다.

```powershell
.\mvnw.cmd "-Dtest=AiSuggestionRecoveryIntegrationTest,AiAttemptResultMigrationIntegrationTest,AiSuggestionWorkerContextIntegrationTest" test
```

### Worker의 실제 JVM Process 종료·재시작

[Process 재시작 Test](./src/test/java/lab/helpdesk/ai/processing/AiSuggestionWorkerProcessRestartIntegrationTest.java)는 같은 PostgreSQL 17.6 Container를 유지하고 실제 Java 자식 Process를 두 번 시작한다. 첫 Process가 접수·예약·결과 분류를 Commit한 지점에서 그 Process만 강제 종료하고 종료를 기다린다. 두 번째 Process의 PID가 다르고 같은 Ticket·Message·Job을 처리하는지 확인한다.

미실행 PENDING, 호출 전 예약만 Commit된 UNCONFIRMED, 관찰한 OUTCOME_UNKNOWN, 대기 Hint 없는 Rate Limit, 승인된 미래 재시도 대기의 5개 사례가 통과했다. 재시작 후의 Application 기본 설정을 전체 생성 5회로 바꿔도 기존 Job의 3회 Snapshot·누적 예약·첫 처리 기한은 유지한다. 재호출 금지 사례는 새 Worker Tick에서도 추가 예약·호출 0회다. 호출 전 중단 사례의 예약 2건과 통제된 Provider 호출 합계 1회도 따로 확인한다.

[자식 Application](./src/test/java/lab/helpdesk/ai/processing/AiWorkerProcessTestApplication.java)은 Test Source에만 있으며 통제된 Provider를 제공한다. 전역 API Key·JVM 주입 옵션·Spring DB 설정을 상속하지 않고, 검증된 Testcontainer 접속 정보만 내부 환경으로 전달한다. 출력은 단계·PID·Row ID·호출 횟수의 고정 형식뿐이며 원문·Credential·예외 원문은 내보내지 않는다. 별도 Process의 출력 Pipe는 계속 읽어 버퍼 정체를 막는다.

Lease·다음 실행 시각은 검증한 Test DB의 해당 Row만 이동해 경계 전후를 확인한다. 120초 대기를 실제로 Sleep하거나 외부 AI를 호출한 Test는 아니다. PostgreSQL Container·Volume 재시작, Provider 응답 중간의 종료와 실제 Browser는 별도 과제다. 이번 변경은 Test와 문서뿐이며 운영 Worker·정책·Migration은 바꾸지 않았다.

```powershell
.\mvnw.cmd "-Dtest=AiSuggestionWorkerProcessRestartIntegrationTest" test
```

### AGENT 전용 AI 상태·제안 조회

`postgres` Profile의 `GET /api/tickets/{id}/ai-suggestion`은 최초 Message의 Job 상태와 검증되어 저장된 제안을 읽는다. 익명은 `401`, USER는 `403`, AGENT는 정상 결과를 조회할 수 있다. 조회 URI의 권한 규칙을 일반 `/api/**` 인증 규칙보다 먼저 적용했다. `@GetMapping`이 자동 지원하는 HEAD도 USER가 접근하지 못하도록 Method와 무관하게 이 URI에 AGENT를 요구한다. 새 POST 실행 API는 없다. 허용된 CORS Preflight는 기존 CORS Filter에서 먼저 처리한다. `in-memory`에는 이 Controller·Service·JDBC Adapter가 등록되지 않는다. [Spring의 HEAD 매핑](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html)

응답은 `ticketId`·`job`·`suggestion`으로 나눈다. Job에는 `id`·`status`·`failureCode`, 제안에는 `id`·`summary`·`categories`·`priority`·`reviewStatus`만 담는다. 조회가 원문·Prompt·Provider 오류 전체·예약 원장·Credential을 공개하거나 AI를 호출하지 않는다. 정상 응답은 `Cache-Control: no-store`다.

| DB에서 확인한 결과 | HTTP 응답 |
|---|---|
| 없는 Ticket | `404` |
| 기존 Ticket에 최초 Message 또는 그 Job이 없음 | `200`, `job: null`·`suggestion: null` |
| PENDING·RUNNING | `200`, 저장된 Job·`suggestion: null`·`failureCode: null` |
| FAILED | `200`, 저장된 Job·허용된 고정 실패 코드·`suggestion: null` |
| ABSTAINED | `200`, 해당 Job·`suggestion: null` |
| SUCCEEDED와 저장된 제안 | `200`, 해당 Job·제안·`PENDING_REVIEW` |
| SUCCEEDED인데 제안 부재 등 저장 결과의 모순 | `500 ProblemDetail`, `code: AI_RESULT_INCONSISTENT` |
| DB 읽기 예외 | `500 ProblemDetail`, `code: AI_RESULT_QUERY_FAILED` |

숫자가 아닌 ID와 양수가 아닌 ID는 `400`이다. Job의 FAILED는 AI 작업 결과이고 HTTP 200은 그 결과 조회가 성공했다는 뜻이다. 읽기 예외나 정합성 오류를 정상적인 null·UNDETERMINED로 바꾸지 않으며, Job 상태를 고치거나 복구를 시작하지 않는다.

[조회 Service](./src/main/java/lab/helpdesk/ai/query/AiSuggestionQueryService.java)는 기존 Worker·Claim·결과 저장 Service와 분리한다. [JDBC 조회 Adapter](./src/main/java/lab/helpdesk/ai/query/JdbcAiSuggestionQueryRepository.java)는 하나의 Parameterized SELECT로 Ticket·최초 Message·Job·제안·분류를 읽는다. 최초 Message는 해당 Ticket의 가장 작은 Message ID이며, 최근 Message의 Job을 대신 가져오지 않는다. 단일 SELECT의 Statement Snapshot으로 결과 Commit 전후를 섞지 않고 읽기 전용 Transaction 안에서 상태·허용값·제안 존재의 정합성을 검사한다. 새 Index·Migration·Row Lock은 추가하지 않았다. [PostgreSQL SELECT Snapshot](https://www.postgresql.org/docs/17/transaction-iso.html#XACT-READ-COMMITTED)

[HTTP Integration Test](./src/test/java/lab/helpdesk/ai/web/AiSuggestionQueryHttpIntegrationTest.java) 34개는 실제 Form Login·Session·Security Filter·MVC·JDBC·PostgreSQL을 사용한다. 반복 조회 전후 여섯 Table과 Provider·Claim·저장 Service 미호출을 비교했고, 기한 만료 Job을 조회만으로 종료하지 않는 것도 확인했다. 결과 SQL 실행 뒤 Commit 전에는 RUNNING·제안 없음, Commit 뒤에는 SUCCEEDED·전체 분류가 보이며 읽기가 Writer Row Lock을 기다리지 않았다. PostgreSQL의 `transaction_read_only=on`도 확인했다.

[Service Unit Test](./src/test/java/lab/helpdesk/ai/query/AiSuggestionQueryServiceTest.java) 20개와 [Profile Test](./src/test/java/lab/helpdesk/ai/web/AiSuggestionQueryProfileIntegrationTest.java) 1개를 포함해 새 Test 55개가 통과했다. DB 읽기 예외는 Test용으로 주입해 원문·Cause가 HTTP와 Log에 나오지 않는지 확인했다. 실제 DB 네트워크 장애·Browser E2E·새 유료 AI 호출을 실행한 것은 아니다. 전체 Java Clean Test 444개·JavaScript 104개·ESLint가 통과했다.

```powershell
.\mvnw.cmd "-Dtest=AiSuggestionQueryServiceTest,AiSuggestionQueryHttpIntegrationTest,AiSuggestionQueryProfileIntegrationTest" test
```

### 담당자의 최소 AI 조회 화면

`/ai-suggestions.html`은 Ticket ID로 최초 문의의 Job·제안을 읽는 별도 화면이다. 기존 `/tickets.html`에는 이 화면으로 가는 링크만 추가했다. 빈 HTML·Module은 기존 정적 파일 정책을 유지하고, 데이터 API는 Server에서 AGENT 권한을 검사한다. `postgres` 모드에서 사용하며 `in-memory`에는 AI 조회 API가 없다.

조회 버튼은 Session Cookie가 포함될 수 있는 같은 Origin GET 한 건을 보낸다. CSRF Token 조회·자동 Polling·AI 생성·실패 재시도를 실행하지 않는다. HTTP `200`의 `FAILED`는 AI 처리 실패로, `SUCCEEDED`·`PENDING_REVIEW`는 ‘AI 제안 생성 완료·담당자 검토 대기’로 표시한다. Job 미등록·대기·실행·생성 보류도 구분한다. HTTP 조회 오류·JSON 오류·잘못된 응답 구조는 Job 실패 상태로 바꾸지 않는다.

[조회 Client와 View](./src/main/resources/static/ai-suggestion-ui.mjs)는 응답의 ID·필드·허용값·Job과 Suggestion 관계를 확인한 뒤 요약을 `textContent`로 표시한다. 새 조회에서 이전 요약·실패 코드를 지우며 Abort Signal과 현재 요청 번호를 함께 사용한다. UI의 별도 요약 길이 상한은 추가하지 않았다. Runtime 출력 검증기의 주입 정책은 그대로다.

새 Node Test 29개는 합성 Response·DOM Test Double과 Page 연결을 확인한다. [정적 Resource Test](./src/test/java/lab/helpdesk/ai/web/AiSuggestionPageIntegrationTest.java) 5개는 `in-memory` Context에서 HTML·Module 제공과 익명 데이터 GET의 `401`을 확인한다. HTML은 UTF-8이며 MockMvc의 한글 Body 검증에도 UTF-8을 명시했다. 전체 Java Clean Test 449개·JavaScript 133개·ESLint가 통과했다. 실제 Browser의 Cookie·PostgreSQL 조회·유료 자동 Worker는 다음 수직 검증에서 확인한다.

```powershell
node --test src/test/js/ai-suggestion-ui.test.mjs src/test/js/ai-suggestion-page.test.mjs
.\mvnw.cmd "-Dtest=AiSuggestionPageIntegrationTest" test
```

### 실제 Browser·자동 Worker·PostgreSQL 실험 구성

[실행기](./scripts/week7-worker-browser-live.mjs)는 실제 Browser의 USER 로그인·접수, 예약된 Worker, 제안 저장과 AGENT 화면을 연결한다. [Test 전용 조립](./src/test/java/lab/helpdesk/ai/provider/Week7WorkerBrowserExperiment.java)만 명시적인 Provider·Guard·검증기와 Worker를 활성화한다. 일반 Application의 유료 Provider 자동 구성은 추가하지 않았다.

새 PostgreSQL 17.6 Testcontainer, 임의 Port의 Loopback Server, 일회용 USER·AGENT와 별도의 Browser Session을 사용한다. 기존 DB·사용자 Process를 종료하거나 정리하지 않는다. Browser 실행 자식 Process에는 AI Key를 넘기지 않는다. 문의는 로그인 복구 사실과 합성 이메일 표식을 포함한 고정 입력 한 건이다.

Browser는 실제 접수 화면의 제목·본문을 입력하고 생성 버튼을 누른다. 같은 Session의 CSRF 없는 대조 POST는 `403`, 정상 UI 접수는 `201`이다. Scheduler가 Commit된 Job을 처리한 뒤 AGENT가 실제 조회 버튼으로 읽는다. 화면·HTTP 응답·DB의 요약·분류·우선순위·검토 상태를 대조하고, 조회 전후 여섯 Table의 변화가 없는지도 확인한다. Screenshot에는 화면만 저장하며 Cookie·Token·Prompt·Header를 출력하지 않는다. 끝나면 일회용 Session 기록과 Container는 정리하고 Screenshot은 Git 제외 `output/playwright/`에 남긴다.

이 실험 Job만 생성 한도 1회·출력 보완 0회 Snapshot을 사용한다. 한 문의의 수직 연결을 확인하기 위한 실험값이며, 일반 Job 정책의 3회·보완 1회와 실패별 재시도 조건은 바꾸지 않았다. Test용 추가 호출 차단도 적용한다.

```powershell
node .\scripts\week7-worker-browser-live.mjs --dry-run
node .\scripts\week7-worker-browser-live.mjs --smoke
```

`--smoke`는 통제된 Provider를 사용한다. 실제 Browser·Session·CSRF·자동 Worker·PostgreSQL·화면을 확인하지만 유료 AI 호출은 0회다. 일반 Java 회귀와 별도로 선택하는 Experiment 한 건이다. 실제 Provider 모드는 전용 PowerShell에서 실행한다.

```powershell
# 해당 날짜에 비용 상한 해제를 명시적으로 승인한 경우에만 실행한다.
# HELPDESK_OPENAI_API_KEY는 이미 Helpdesk 전용 창의 Process에 있어야 한다.
node .\scripts\week7-worker-browser-live.mjs --live --confirm-helpdesk-key --confirm-synthetic --confirm-cost-limit-waiver --day YYYY-MM-DD
```

2026-10-06에 사용자가 당일 비용 상한 해제를 승인했다. 실행기는 기존 날짜별 원장에 `costLimitWaiver`를 추가하고 이전 비용 미확인·보류 금액·예약 횟수를 그대로 유지한다. `limitUsd`는 이전 승인값이며 새 Report의 `costLimitEnforced: false`가 현재 적용 여부다. 미정산 진행 예약은 삭제하지 않고, 비용 상한 해제 옵션을 받지 않은 다른 실행기는 이 원장을 자동 승계하지 못한다. Job의 생성 한도와 일일 실험 비용 제한은 서로 다른 정책이다.

사용량 추정에는 기존의 보수적인 입력 `$0.125/M`·출력 `$0.50/M`을 유지한다. 현재 공식 Standard 입력 단가는 `$0.10/M`이며, 추정 원장을 실제 청구액으로 표현하지 않는다. [GPT-6 Luna 요금](https://developers.openai.com/api/docs/models/gpt-6-luna)

새 실행기 Test 12개를 포함한 JavaScript 145개와 ESLint가 통과했다. 통제된 Provider의 실제 Browser Experiment는 접수 `201`·누락 CSRF `403`·익명 조회 `401`·USER 조회 `403`·AGENT 조회 `200`, 원문·인증 작성자 보존, 예약·제안·분류 각 1건, 화면과 DB 일치·조회 무변경을 확인했다. 실제 유료 자동 Worker 결과는 별도 Live Report를 받은 뒤 기록한다.

## 현재 Application 비범위

- 외부 운영 Database 구성과 Backup·복구
- Production용 인증·사용자 권한 검사와 운영 Credential 관리
- 원격 Provider 결과 조회·응답 객체 영속 보관과 운영 Application의 유료 AI 자동 조립. 통제된 Provider의 실제 JVM 재시작 Test와 구분한다.
- 담당자 할당, Comment와 이력 조회
- Ticket 전체 CRUD와 검색·정렬·Pagination
- Production에 고의 실패 Endpoint를 추가하는 방식의 `500` 재현
- WebFlux, GraphQL과 다른 Backend Framework 비교
- 비동기 Dispatch, 분산 Trace와 Production Monitoring

현재 학습 질문에 필요하지 않은 기능은 먼저 추가하지 않는다.

## 개발 규칙

- Text 파일은 UTF-8 without BOM과 LF 줄바꿈을 사용한다.
- 컴파일 결과물은 Source와 함께 Commit하지 않는다.
- 필드는 `private`으로 보호하고 상태 변경은 의도가 드러나는 행동으로 제한한다.
- 실행하지 않은 Test나 구현하지 않은 기능을 완료로 기록하지 않는다.
- Secret, Credential, 개인정보와 로컬 절대 경로를 공개 문서에 포함하지 않는다.

## AI 활용 범위

- AI가 보조한 부분: 개념 설명, 반례와 검증 Case 제안, Code와 문서 Review
- 직접 수행한 부분: JDK와 JShell 실행, Ticket·Policy Code와 JUnit Test 작성, Spring Boot와 Ticket 수직 Slice 구성, Validation·Exception Handler·오류 Test 작성, Filter·Interceptor와 관련 Test 작성, Maven Clean Test, Server 기동, 정상·실패 `curl.exe` Trace와 Diff 관찰

AI가 제안한 Code도 직접 설명하고 수정하며 검증할 수 있을 때만 학습 결과로 인정한다.

## 다음 단계

1. Week 6 핵심 개념을 자료 없이 다시 설명하는 복습을 이어간다.
2. Week 7에서는 AI Native 학습을 시작하되 이미 검증한 Ticket 수직 흐름을 유지한다.
3. Week 8 배포·HTTPS 학습에서 PostgreSQL Volume·복구 경계와 운영 Credential 정책을 별도로 다룬다.
