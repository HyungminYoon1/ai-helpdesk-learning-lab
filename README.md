# AI Helpdesk Learning Lab

> 상태: Week 2 진행 중 — HTTP·REST 예상 계약과 Spring Boot 최소 기동·Root Smoke 검증 완료
> 현재 학습 영역: HTTP 메시지, REST API 계약, Spring Boot·Spring MVC 요청 흐름과 최소 Ticket API
> 실행 기준: Java 25

## 프로젝트 목적

AI Helpdesk Learning Lab은 큰 서비스를 빠르게 완성하는 프로젝트가 아니라 Java Backend의 핵심 개념을 작은 실험으로 학습하고 검증하기 위한 프로젝트다.

Week 1에는 Framework 없이 Ticket 객체가 자신의 상태와 규칙을 지키게 만들고, 조건문과 Strategy·Composition의 변경 범위를 비교하며, JUnit Test로 그 계약을 설명하는 것을 목표로 한다.

Week 2에는 Week 1의 Ticket Domain과 Test를 회귀 기준선으로 유지하면서 HTTP 메시지와 REST 계약을 먼저 설명한다. 이후 Spring Boot를 최소 구성으로 기동하고, Ticket 생성·단건 조회 흐름을 Controller·Application Service·Repository·Domain으로 분리하여 구현한다. 구현량보다 예상 계약, Test와 실제 HTTP Trace의 차이를 설명하고 재현하는 데 중점을 둔다.

## 핵심 질문

1. Client와 Server는 HTTP Method·URI·Header·Body와 Status를 통해 요청과 응답의 의미를 어떻게 합의하는가?
2. Ticket 생성·단건 조회의 정상·실패 계약을 구현 전에 설명할 수 있는가?
3. Controller·Application Service·Repository·Domain은 각각 무엇을 알고 무엇을 몰라야 하는가?
4. Spring MVC Test와 실제 Server에 보내는 `curl.exe` Trace는 각각 무엇을 검증하는가?

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
- MockMvc Test와 실제 `curl.exe` Request·Response Trace 비교

2026-08-25 야간에 Spring Boot Dependency와 Application 진입점을 추가하고 기존 Unit Test 16개를 다시 통과했다. Application Context와 내장 Server를 기동한 뒤 Root URI에 실제 `curl.exe` 요청을 보내 `404 Not Found` JSON 응답을 관찰했다. Ticket Web API와 MockMvc는 아직 구현하지 않았고 `POST /api/tickets`·`GET /api/tickets/{id}` 실제 호출도 `NOT_RUN`이다.

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
   │           ├─ ticket/
   │           │  ├─ Ticket.java
   │           │  └─ TicketStatus.java
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
               │  └─ TicketTest.java
               └─ responsetime/
                  ├─ conditional/
                  │  └─ ConditionalResponseTimePolicyTest.java
                  └─ strategy/
                     └─ ResponseTimeCalculatorTest.java
```

Java Package Root는 `lab.helpdesk`다. Application 진입점은 Root에 두고 Ticket Domain의 `lab.helpdesk.ticket`과 독립 Policy 비교용 `lab.helpdesk.responsetime` 하위 Package를 사용한다.

위 구조는 현재 실제 Source 기준이다. Week 2의 Web·Application·Repository Package는 구현하고 검증한 뒤 구조도에 추가한다.

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

`test` Phase를 요청하면 Main Source와 Test Source를 컴파일한 뒤 Maven Surefire가 JUnit Platform을 통해 Unit Test를 실행한다. 현재 Ticket 정상·경계·거부 Test 10개, 조건문 Policy Test 3개와 Strategy Policy Test 3개가 실행된다.

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
| JUnit 자동 검증 | 완료 | `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0` |
| HTTP·REST 예상 계약 | 작성 완료 | 생성·단건 조회의 정상·실패 Given–When–Then과 Method·Status·Header·Body 기록 |
| Spring Boot Dependency·Application 진입점 | 구현·컴파일 완료 | Spring Boot `4.1.1`, `spring-boot-starter-webmvc`, Maven Plugin과 `HelpdeskApplication` 적용 |
| Application Context·내장 Server | 기동 확인 | Java `25.0.4`, Tomcat `11.0.24`, Port `8080`에서 `Started HelpdeskApplication` 확인 |
| Ticket Web API·MockMvc | `NOT_IMPLEMENTED` | Controller·Application Service·Repository 미구현 |
| 실제 HTTP `curl.exe` Trace | Root Smoke 완료 | `GET /`에서 `404 Not Found`, `Content-Type: application/json`과 기본 오류 Body 확인. Ticket API Trace는 `NOT_RUN` |

이 결과는 Ticket Domain Test 10개와 응답 시간 Policy 비교 Test 6개를 자동 검증했다는 의미다. 응답 시간 Policy가 실제 Ticket 업무 흐름에 연결됐거나 동시성, 영속화, 권한과 현재 비범위 기능의 정확성까지 검증했다는 의미는 아니다.

2026-08-25 22:27 KST에 `spring-boot:run`으로 Spring Boot `4.1.1`을 기동했고, 22:28 KST에 `curl.exe --verbose --include --header "Accept: application/json" http://localhost:8080/`를 실행했다. `localhost`의 IPv6 Loopback `::1` 연결, `GET / HTTP/1.1`, `HTTP/1.1 404`와 JSON 오류 Body를 관찰했다. Controller가 없는 상태의 예상 결과이며 Ticket API 동작 근거는 아니다. 실행 후 Server를 종료하고 Port `8080`에 Listener가 없음을 확인했다.

Maven 변경 직후 VS Code가 `HelpdeskApplication.java`에 일시적인 오류 표시를 남겼지만, Maven Clean Compile과 실제 Server 기동은 성공했다. Java Language Server Workspace 정리와 Maven Project Reload 후 표시가 사라졌으므로 Source 오류가 아니라 Editor Dependency 동기화 문제로 판단했다.

JUnit 기준선은 `cdcbee0`, 대표 Exception Message 검증은 `944aede`, Policy 비교 기준선은 `6fb3365`, VIP 확장은 `3eb8b29` Commit에 기록했다.

## 현재 비범위

- Database와 영속화
- 인증과 사용자 권한 검사
- Browser UI
- AI 분류와 외부 API 연동
- 담당자 할당, Comment와 이력 조회
- Ticket 전체 CRUD와 검색·정렬·Pagination
- Production에 고의 실패 Endpoint를 추가하는 방식의 `500` 재현
- WebFlux, GraphQL과 다른 Backend Framework 비교
- Must 범위 완료 전 CORS·Filter·Interceptor 확장 구현

현재 학습 질문에 필요하지 않은 기능은 먼저 추가하지 않는다.

## 개발 규칙

- Text 파일은 UTF-8 without BOM과 LF 줄바꿈을 사용한다.
- 컴파일 결과물은 Source와 함께 Commit하지 않는다.
- 필드는 `private`으로 보호하고 상태 변경은 의도가 드러나는 행동으로 제한한다.
- 실행하지 않은 Test나 구현하지 않은 기능을 완료로 기록하지 않는다.
- Secret, Credential, 개인정보와 로컬 절대 경로를 공개 문서에 포함하지 않는다.

## AI 활용 범위

- AI가 보조한 부분: 개념 설명, 반례와 검증 Case 제안, Code와 문서 Review
- 직접 수행한 부분: JDK와 JShell 실행, Ticket·Policy Code와 JUnit Test 작성, Spring Boot Dependency·Application 진입점 구성, Maven Clean Test, Server 기동, `curl.exe` Trace, Editor 동기화 오류 조치와 Diff 관찰

AI가 제안한 Code도 직접 설명하고 수정하며 검증할 수 있을 때만 학습 결과로 인정한다.

## 다음 단계

1. 기존 16개 Unit Test를 Spring 변경 이후에도 회귀 기준선으로 유지한다.
2. Controller·Application Service·Repository Port·In-memory 구현으로 생성·단건 조회 수직 Slice를 구성한다.
3. 정상 생성·조회 MVC Test에서 Status·Header·Body와 Layer 호출을 검증한다.
4. 실제 `POST /api/tickets`와 `GET /api/tickets/{id}`를 호출하고 구현 전 예상 계약과 비교한다.
5. 잘못된 제목·존재하지 않는 ID·잘못된 ID 형식의 오류 계약은 정상 수직 Slice 이후 추가한다.
