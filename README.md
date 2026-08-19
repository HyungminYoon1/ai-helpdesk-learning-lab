# AI Helpdesk Learning Lab

> 상태: Week 1 진행 중
> 현재 학습 영역: Java 객체지향과 Ticket 상태 전이
> 실행 기준: Java 25

## 프로젝트 목적

AI Helpdesk Learning Lab은 큰 서비스를 빠르게 완성하는 프로젝트가 아니라 Java Backend의 핵심 개념을 작은 실험으로 학습하고 검증하기 위한 프로젝트다.

Week 1에는 Framework 없이 Ticket 객체가 자신의 상태와 규칙을 지키게 만들고, 이후 JUnit Test로 그 계약을 설명하는 것을 목표로 한다.

## 핵심 질문

> 필드를 `private`으로 선언하는 것에 그치지 않고, 객체가 허용된 행동을 통해 불변조건과 상태 전이 규칙을 스스로 보호하게 만들 수 있는가?

## 현재 범위

- Java 25 기반 단일 Ticket Domain
- 제목 불변조건 검증
- `OPEN → IN_PROGRESS → RESOLVED` 상태 전이
- 허용되지 않은 상태 전이 거부
- 실패한 상태 전이 이후 기존 상태 보존
- 범용 Setter 대신 의도가 드러나는 행동 제공

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
   └─ main/
      └─ java/
         └─ lab/
            └─ helpdesk/
               └─ ticket/
                  ├─ Ticket.java
                  └─ TicketStatus.java
```

Java Package는 `lab.helpdesk.ticket`을 사용한다.

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

`test` Phase를 요청하면 Main Source와 Test Source를 컴파일한 뒤 Unit Test를 실행한다. 현재는 JUnit Test가 없으므로 Main Source 컴파일과 Build Lifecycle 재현만 검증한다.

Build에 성공하면 다음 위치에 Class 파일이 생성된다.

```text
target/classes/lab/helpdesk/ticket/
```

`out/`, `target/`, `build/` 같은 생성물 디렉터리는 Git에서 추적하지 않는다.

## 현재 검증 상태

| 검증 항목 | 상태 | 근거 |
|---|---|---|
| JDK와 Java Compiler | 완료 | `java`, `javac`, `jshell` 25.0.4 확인 |
| Java 25 Source 컴파일 | 완료 | `javac --release 25` 성공 |
| Maven Wrapper | 완료 | Wrapper 3.3.4로 Maven 3.9.16과 Java 25.0.4 실행 확인 |
| Maven `test` Lifecycle | 완료 | `BUILD SUCCESS`와 `target/classes` 생성 확인 |
| Ticket 정상 상태 전이 | 수동 검증 완료 | JShell에서 상태 변화 확인 |
| 잘못된 상태 전이 거부 | 수동 검증 완료 | 예외와 실패 후 상태 보존 확인 |
| JUnit 자동 검증 | 미수행 | JUnit Dependency와 Test Case 미구성 |

수동 검증과 Maven `test` 성공은 자동 회귀 Test가 준비됐다는 의미가 아니다.

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
- 직접 수행한 부분: JDK와 JShell 실행, Ticket Code 작성, 컴파일, 결과 관찰과 설명 수정

AI가 제안한 Code도 직접 설명하고 수정하며 검증할 수 있을 때만 학습 결과로 인정한다.

## 다음 단계

1. JUnit Dependency와 Test Source를 구성한다.
2. 정상·경계·거부 Case를 Given-When-Then Test로 작성한다.
3. 새 Terminal에서 Wrapper를 사용해 전체 Test를 다시 실행한다.
4. 실제 관찰 결과와 남은 질문을 학습 문서에 반영한다.
