# AI Helpdesk Learning Lab

> 상태: Week 4 익명 API `401`·BCrypt·Form Login·Session·Role Matrix·CSRF 비교 Baseline 완료 — 전체 Test 42개 통과
> 현재 학습 영역: Session Credential에 대한 CSRF 보호 경계
> 실행 기준: Java 25

## 프로젝트 목적

AI Helpdesk Learning Lab은 큰 서비스를 빠르게 완성하는 프로젝트가 아니라 Java Backend의 핵심 개념을 작은 실험으로 학습하고 검증하기 위한 프로젝트다.

Week 1에는 Framework 없이 Ticket 객체가 자신의 상태와 규칙을 지키게 만들고, 조건문과 Strategy·Composition의 변경 범위를 비교하며, JUnit Test로 그 계약을 설명하는 것을 목표로 한다.

Week 2에는 Week 1의 Ticket Domain과 Test를 회귀 기준선으로 유지하면서 HTTP 메시지와 REST 계약을 먼저 설명한다. 이후 Spring Boot를 최소 구성으로 기동하고, Ticket 생성·단건 조회 흐름을 Controller·Application Service·Repository·Domain으로 분리하여 구현한다. 구현량보다 예상 계약, Test와 실제 HTTP Trace의 차이를 설명하고 재현하는 데 중점을 둔다.

Week 4에는 기존 수직 Slice를 유지하면서 Session 인증과 Role 기반 인가를 작은 단계로 실험한다. 9월 11일 학습이 자정을 넘긴 연장 구간에서 Security Starter만 추가해 Default Auto-Configuration의 영향을 먼저 관찰했다. 실제 Test 실행 시각은 2026-09-12 00:41~00:42 KST였다. 9월 12일에는 실제 Filter Chain을 통과하는 익명 API Request의 `401`, BCrypt Password 검증, Test 전용 USER·AGENT의 Form Login·Session 복원과 Role Matrix, 인증된 POST의 CSRF Token 누락·유효 조건을 작은 단계로 확인했다. Runtime 사용자 구성과 실제 Browser Cookie·CSRF Trace는 아직 구현하거나 실행하지 않았다.

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
- 생성된 개발용 Credential 값은 실행 결과 공유와 문서에서 제외
- Runtime 사용자 구성과 실제 Browser Cookie·CSRF Network Trace는 `NOT_IMPLEMENTED`·`NOT_RUN`

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

`test` Phase를 요청하면 Main Source와 Test Source를 컴파일한 뒤 Maven Surefire가 JUnit Platform을 통해 Test를 실행한다. 현재 Ticket Domain Test 10개, 조건문·Strategy Policy Test 6개, Repository Test 3개, Application Service Test 3개, Ticket Spring MVC Test 7개, Web 공통 처리 Test 4개와 Security Integration Test 9개가 실행된다.

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
| HTTP·REST 예상 계약 | 작성 완료 | 생성·단건 조회의 정상·실패 Given–When–Then과 Method·Status·Header·Body 기록 |
| Spring Boot Dependency·Application 진입점 | 구현·컴파일 완료 | Spring Boot `4.1.1`, `spring-boot-starter-webmvc`, Maven Plugin과 `HelpdeskApplication` 적용 |
| Application Context·내장 Server | 기동 확인 | Java `25.0.4`, Tomcat `11.0.24`, Port `8080`에서 `Started HelpdeskApplication` 확인 |
| Ticket Web API·MockMvc | 완료 | 정상 생성·조회와 공백 제목·잘못된 JSON·ID Type 불일치·부재·대표 내부 실패를 MVC Test 7개로 검증 |
| Validation·오류 응답 | 완료 | `@NotBlank`·`@Valid`, `TicketNotFoundException`, `ResponseEntityExceptionHandler`와 `ProblemDetail` 적용 |
| Filter·Interceptor | 최소 실험 완료 | Request ID·Chain 진행, 요청별 시작 시각과 `preHandle()` 반환을 단위 Test로 확인하고 전체 Context Test에서 등록·404 완료 로그를 검증 |
| 실제 HTTP `curl.exe` Trace | 완료 | 정상 `201`·`200`, 공백 제목·잘못된 JSON·ID Type 불일치 `400`, 부재 `404`와 `application/problem+json` Body 확인 |
| 대표 `500` 계약 | Test 완료 | Repository 수동 Test Double의 통제된 실패를 안전한 `500 ProblemDetail`로 변환하고 내부 Exception은 Server Log에만 보존 |

이 결과는 선택한 생성·조회와 대표 오류 계약을 자동 검증했다는 의미다. 동시성, 영속화, Transaction, 권한과 현재 비범위 기능의 정확성까지 검증했다는 의미는 아니다.

2026-08-25 22:27 KST에 `spring-boot:run`으로 Spring Boot `4.1.1`을 기동했고, 22:28 KST에 `curl.exe --verbose --include --header "Accept: application/json" http://localhost:8080/`를 실행했다. `localhost`의 IPv6 Loopback `::1` 연결, `GET / HTTP/1.1`, `HTTP/1.1 404`와 JSON 오류 Body를 관찰했다. Controller가 없는 상태의 예상 결과이며 Ticket API 동작 근거는 아니다. 실행 후 Server를 종료하고 Port `8080`에 Listener가 없음을 확인했다.

Maven 변경 직후 VS Code가 `HelpdeskApplication.java`에 일시적인 오류 표시를 남겼지만, Maven Clean Compile과 실제 Server 기동은 성공했다. Java Language Server Workspace 정리와 Maven Project Reload 후 표시가 사라졌으므로 Source 오류가 아니라 Editor Dependency 동기화 문제로 판단했다.

JUnit 기준선은 `cdcbee0`, 대표 Exception Message 검증은 `944aede`, Policy 비교 기준선은 `6fb3365`, VIP 확장은 `3eb8b29` Commit에 기록했다.

2026-08-27 실제 Server에서 정상 생성은 `201 Created`와 `Location: /api/tickets/1`, 정상 조회는 `200 OK`와 Ticket JSON을 반환했다. 공백 제목과 잘못된 JSON, 숫자가 아닌 ID는 각각 안전한 `400 ProblemDetail`을 반환했고, 존재하지 않는 숫자 ID는 `404 ProblemDetail`을 반환했다. RFC 9457의 기본 `type`인 `about:blank`는 JSON에서 생략될 수 있으며, `instance`는 실제 Request Path로 설정되는 것을 관찰했다.

## 현재 비범위

- Database와 영속화
- Production용 인증·사용자 권한 검사와 운영 Credential 관리
- Browser UI
- AI 분류와 외부 API 연동
- 담당자 할당, Comment와 이력 조회
- Ticket 전체 CRUD와 검색·정렬·Pagination
- Production에 고의 실패 Endpoint를 추가하는 방식의 `500` 재현
- WebFlux, GraphQL과 다른 Backend Framework 비교
- CORS와 Preflight 실험
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

1. Role Matrix와 CSRF 비교 결과를 자료 없이 다시 설명한다.
2. Source·설정·Test Output과 Log의 Secret·Password 노출 여부를 점검한다.
3. MockMvc Session 근거와 실제 Browser Cookie Trace의 경계를 WIL에 정리한다.
