# AI Helpdesk Learning Lab

> 상태: Week 1 진행 중
> 현재 학습 영역: Java 객체지향, Ticket 상태 전이, Policy 구조 비교와 JUnit 자동 검증
> 실행 기준: Java 25

## 프로젝트 목적

AI Helpdesk Learning Lab은 큰 서비스를 빠르게 완성하는 프로젝트가 아니라 Java Backend의 핵심 개념을 작은 실험으로 학습하고 검증하기 위한 프로젝트다.

Week 1에는 Framework 없이 Ticket 객체가 자신의 상태와 규칙을 지키게 만들고, 조건문과 Strategy·Composition의 변경 범위를 비교하며, JUnit Test로 그 계약을 설명하는 것을 목표로 한다.

## 핵심 질문

> 필드를 `private`으로 선언하는 것에 그치지 않고, 객체가 허용된 행동을 통해 불변조건과 상태 전이 규칙을 스스로 보호하게 만들 수 있는가?

## 현재 범위

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

Java Package는 Ticket Domain의 `lab.helpdesk.ticket`과 독립 Policy 비교용 `lab.helpdesk.responsetime` 하위 Package를 사용한다.

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
| Maven `test` Lifecycle | 완료 | `.\mvnw.cmd clean test`에서 Main·Test Source 재컴파일과 `BUILD SUCCESS` 확인 |
| Ticket 정상 상태 전이 | 자동 검증 완료 | 생성·처리 시작·해결 정상 Case 통과 |
| 제목 경계 입력 | 자동 검증 완료 | `null`·빈 문자열·공백 문자열 거부 Case 통과 |
| 잘못된 상태 전이 거부 | 자동 검증 완료 | 거부 Case 4개에서 예외 Type과 실패 후 상태 보존 확인 |
| Exception Message | 자동 검증 완료 | 서로 다른 대표 Message 3개 확인 |
| 조건문 응답 시간 Policy | 자동 검증 완료 | NORMAL 24시간·URGENT 4시간·VIP 1시간 Case 통과 |
| Strategy 응답 시간 Policy | 자동 검증 완료 | 세 Policy 구현체를 같은 Interface와 Calculator로 검증 |
| JUnit 자동 검증 | 완료 | `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0` |

이 결과는 Ticket Domain Test 10개와 응답 시간 Policy 비교 Test 6개를 자동 검증했다는 의미다. 응답 시간 Policy가 실제 Ticket 업무 흐름에 연결됐거나 동시성, 영속화, 권한과 현재 비범위 기능의 정확성까지 검증했다는 의미는 아니다.

JUnit 기준선은 `cdcbee0`, 대표 Exception Message 검증은 `944aede`, Policy 비교 기준선은 `6fb3365`, VIP 확장은 `3eb8b29` Commit에 기록했다.

## 현재 비범위

- Spring Boot와 Web API
- Database와 영속화
- 인증과 사용자 권한 검사
- Browser UI
- AI 분류와 외부 API 연동
- 담당자 할당, Comment와 이력 조회

현재 학습 질문에 필요하지 않은 기능은 먼저 추가하지 않는다.

## 개발 규칙

- Text 파일은 UTF-8 without BOM과 LF 줄바꿈을 사용한다.
- 컴파일 결과물은 Source와 함께 Commit하지 않는다.
- 필드는 `private`으로 보호하고 상태 변경은 의도가 드러나는 행동으로 제한한다.
- 실행하지 않은 Test나 구현하지 않은 기능을 완료로 기록하지 않는다.
- Secret, Credential, 개인정보와 로컬 절대 경로를 공개 문서에 포함하지 않는다.

## AI 활용 범위

- AI가 보조한 부분: 개념 설명, 반례와 검증 Case 제안, Code와 문서 Review
- 직접 수행한 부분: JDK와 JShell 실행, Ticket·Policy Code와 JUnit Test 작성, Maven 실행, 오류 수정, Diff 관찰과 설명 수정

AI가 제안한 Code도 직접 설명하고 수정하며 검증할 수 있을 때만 학습 결과로 인정한다.

## 다음 단계

1. 현재 16개 Test를 Week 1 회귀 검증 기준선으로 유지한다.
2. 새 기능을 추가하지 않고 Week 1의 완료·부분 완료·미수행 범위와 남은 질문을 정리한다.
3. 8월 22일 전체 Test를 새 Terminal에서 재현하고 Learning Note와 WIL 근거를 검토한다.
4. 학습 질문과 Test 없이 Comment·이력·Service 같은 기능을 먼저 추가하지 않는다.
