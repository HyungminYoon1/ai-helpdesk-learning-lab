# 현재 로컬 검증을 다시 실행하는 방법

> 기준: 2026-11-04, V13 상태 변경/재개까지 구현한 코드
> 범위: 로컬 자동 검증과 그 증거를 읽는 방법. AWS 배포나 상용 AI 내용 평가를 실행하는 안내는 아니다.

## 1 준비와 코드 기준 확인

아래 명령은 Windows의 PowerShell 7에서 Lab 저장소 Root를 기준으로 실행한다.

```powershell
Set-Location -LiteralPath 'C:\developer_folder\ai-helpdesk-learning-lab'
git status --short
git rev-parse HEAD
java --version
.\mvnw.cmd --version
node --version
Get-Command pwsh.exe, npx.cmd -ErrorAction Stop
```

JDK 25는 pom.xml의 기준이다. Node/npm/npx가 필요하며, Maven은 저장소의 Wrapper를 사용한다. PostgreSQL 통합 검사와 아래 Browser 실험에는 Docker Engine도 준비돼 있어야 한다. Node 단위 검사와 ESLint 자체에는 Docker가 필요하지 않다. 처음 실행할 때는 Maven/npm/Browser 관련 파일의 다운로드가 필요할 수 있다.

```powershell
docker info --format '{{.ServerVersion}}'
```

이 명령은 Engine 연결을 확인한다. 기존 Container/Volume을 지우거나 초기화할 필요는 없다. 새 Week 12 기능의 통합 검증은 postgres Profile과 격리된 Testcontainers DB를 사용하는 경로다. README의 이전 in-memory 실행 예시는 별도 범위다. 테스트용 계정/접속 설정은 각 실험이 준비하므로 개인 암호나 실제 AI Key를 명령에 넣지 않는다.

작업 중인 변경이 있으면 Commit 번호만으로 실행한 전체 소스를 특정할 수 없다. 실행 결과에는 Commit과 변경 유무를 함께 남긴다. Git Push, CI, Image 게시나 Cloud 실행은 아래 명령에 포함되지 않는다.

## 2 기본 회귀는 세 명령을 구분해서 실행

```powershell
$ErrorActionPreference = 'Stop'
.\mvnw.cmd -B -ntp verify
$helpdeskJavaExit = $LASTEXITCODE
if ($helpdeskJavaExit -ne 0) { throw "JAVA_VERIFY_FAILED exit=$helpdeskJavaExit" }

node --test --test-reporter=spec 'src/test/js/*.test.mjs'
$helpdeskNodeExit = $LASTEXITCODE
if ($helpdeskNodeExit -ne 0) { throw "NODE_TEST_FAILED exit=$helpdeskNodeExit" }

npx.cmd --yes --package 'eslint@10.11.0' eslint 'src/main/resources/static/*.mjs' 'src/test/js/*.mjs'
$helpdeskLintExit = $LASTEXITCODE
if ($helpdeskLintExit -ne 0) { throw "ESLINT_FAILED exit=$helpdeskLintExit" }
```

| 실행 | 확인하는 범위 | 별도 근거가 필요한 것 |
|---|---|---|
| Maven verify | 기본 Java/Spring/PostgreSQL 검사, 패키징과 POM에 지정한 Failsafe 검사 | 아래 opt-in Browser 실험, 실제 상용 AI, AWS 실행 |
| Node test | UI 상태 처리와 실행 도구의 통제된 입력/응답 사례 | 실제 Session/CSRF/DB Commit의 통합 흐름 |
| ESLint | 현재 설정이 검사하는 JavaScript 정적 규칙 | 업무 계약, DB 보존이나 모델 내용 정확도 |

2026-11-04의 앞선 실행은 Java 1,221건과 Failsafe 2건, Node 418건이었다. 이는 그 코드/시점의 수치이며 앞으로의 고정 기대 개수가 아니다. 집중 Java 검사 15건/45건은 기본 전체에 포함되므로 다시 더하지 않는다.

현재 POM은 SyntheticDeploymentPrivacyPackagedIT를 integration-test/verify 단계에 연결한다. 따라서 Maven test만 실행한 결과를 패키징 후 2건까지 검증한 것으로 기록하지 않는다. 이 두 건은 패키징된 합성 검사 프로그램과 실제 HTTP Adapter/DB를 연결하지만 AI 응답 서버는 Loopback의 가짜 서버다. Provider 호출 수나 응답의 Model 문자열만으로 실제 외부 AI 호출을 주장하지 않는다.

## 3 현재 대표 Browser 세 건은 별도 실행

```powershell
$helpdeskPwsh = (Get-Command pwsh.exe -ErrorAction Stop).Source
.\mvnw.cmd -B -ntp '-Dtest=TicketStatusBrowserExperiment,ReplyDraftReviewBrowserExperiment,CustomerConfirmationReselectionBrowserExperiment' "-Dhelpdesk.browser.pwsh=$helpdeskPwsh" test
$helpdeskBrowserExit = $LASTEXITCODE
if ($helpdeskBrowserExit -ne 0) { throw "BROWSER_TEST_FAILED exit=$helpdeskBrowserExit" }
```

세 클래스는 각각 자기 Spring 서버/합성 계정/격리 PostgreSQL을 준비하고, 전용 PowerShell Script에서 고정한 @playwright/cli@0.1.21을 호출한다. 애플리케이션 Port는 임의 배정이다. 이전 Week 6 E2E의 고정 Port 실행법과 섞지 않는다. 평소 사용하는 Browser의 로그인 정보를 넘길 필요가 없다.

| 실험 | 대표적으로 대조하는 것 |
|---|---|
| TicketStatusBrowserExperiment | 새 글 뒤 오래된 해결의 거부, Commit 응답 유실/같은 제출 재확인, 저장된 이력과 현재 상태, 고객 본인 재개 |
| ReplyDraftReviewBrowserExperiment | A→B→A 뒤 초안의 명시적 재작성과 미확인 정정의 원래 제출 보존 |
| CustomerConfirmationReselectionBrowserExperiment | 배정 충돌 뒤 재선택, 선택 후 새 글의 도착, 이전 확인 범위와 확인 필요 |

이 세 건에서는 Provider의 상호작용이 없음을 검사한다. 실제 Browser/HTTP/DB가 참여했다는 사실과 실제 AI 모델을 호출했다는 사실은 별개다. 다른 과거 Browser 실험이나 모든 Browser 제품을 이번 세 건이 대신 검증하지 않는다. 실제 기록은 [상태 처리 계약](./ticket-status.md)의 실행 Checkpoint와 구분한다.

화면/실험 파일은 output/playwright 아래의 해당 실행 디렉터리, Java XML 보고서는 target/surefire-reports와 target/failsafe-reports에서 확인한다. 이전 실행 보고서가 남을 수 있으므로 XML 파일 전부를 단순 합산하지 말고 해당 명령의 최종 결과/시각/실행 대상을 맞춘다.

## 4 로그와 종료 코드는 함께 남기기

ESLint의 성공 로그는 비어 있을 수 있다. 빈 파일만으로는 실행 성공 여부를 판단할 수 없다. 다음은 종료 코드를 다른 외부 명령이 덮어쓰기 전에 저장하고, 비밀값 없이 코드 기준을 남기는 예시다.

```powershell
$ErrorActionPreference = 'Stop'
$helpdeskCommit = (git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'GIT_COMMIT_UNAVAILABLE' }
$helpdeskChanges = @(git status --porcelain)
if ($LASTEXITCODE -ne 0) { throw 'GIT_STATUS_UNAVAILABLE' }
$helpdeskClean = ($helpdeskChanges.Count -eq 0)
$helpdeskRun = Join-Path 'target' ('verification-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $helpdeskRun | Out-Null
$helpdeskStartedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
npx.cmd --yes --package 'eslint@10.11.0' eslint 'src/main/resources/static/*.mjs' 'src/test/js/*.mjs' > (Join-Path $helpdeskRun 'eslint.log') 2>&1
$helpdeskExit = $LASTEXITCODE
$helpdeskResult = [ordered]@{
    check = 'eslint@10.11.0'
    command = 'npx.cmd --yes --package eslint@10.11.0 eslint src/main/resources/static/*.mjs src/test/js/*.mjs'
    gitCommit = $helpdeskCommit
    workingTreeCleanAtStart = $helpdeskClean
    startedAtUtc = $helpdeskStartedAtUtc
    finishedAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
    exitCode = $helpdeskExit
    logBytes = (Get-Item -LiteralPath (Join-Path $helpdeskRun 'eslint.log')).Length
}
$helpdeskJson = $helpdeskResult | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $helpdeskRun 'result.json'), $helpdeskJson + "`n", [Text.UTF8Encoding]::new($false))
if ($helpdeskExit -ne 0) { throw "ESLINT_FAILED exit=$helpdeskExit" }
```

각 명령의 실행 결과는 따로 기록한다. 기록에 Credential, 환경변수 목록, Cookie, CSRF Token이나 API Key 값을 추가하지 않는다.

target은 Git에서 추적하지 않는 로컬 생성물이며 Maven clean을 실행하면 지워진다. 보존할 실행 증거는 따로 보관한다. 공유할 때는 필요한 조건/결과/한계를 정리하고 Log나 화면에 비밀값이 없는지 확인한다.

## 5 로컬 HTTP와 빈 출력 로그를 읽는 예시

이번 패키징 검사의 요청 주소는 `http://127.0.0.1:<임의 Port>/v1/chat/completions` 형태다. `http://`는 HTTP이며, `127.0.0.1`은 이 실험을 실행한 컴퓨터의 로컬 가짜 서버다. Provider 카운터 1과 HTTP 카운터 1은 Adapter 호출과 가짜 서버의 요청 수신을 보여준다. 외부 HTTPS나 상용 모델의 추론 성공으로 바꾸어 기록하지 않는다. 테스트 전체에는 별도로 마스킹된 요청 내용과 DB 저장 결과의 검사도 있으며, 요청 수 두 값만으로 그 추가 검사까지 입증하지는 않는다.

프로그램 출력 로그는 도구가 화면/출력 스트림에 쓴 내용이다. ESLint가 보고할 문제가 없으면 비어 있을 수 있다. 실행 결과 기록은 어떤 검사를 어떤 코드에 수행했고 어떻게 종료됐는지 남긴 별도 정보다.

| 기록할 항목 | 이번 ESLint 기록의 예 | 필요한 이유 |
|---|---|---|
| 명령/도구 버전/대상 | ESLint 10.11.0, src/main/resources/static/*.mjs와 src/test/js/*.mjs | 무엇을 검사했는지 구분 |
| 코드 기준/변경 유무 | 당시 Commit 1eca282, 시작 시 작업 트리 Clean | 어느 소스를 검사했는지 구분 |
| 시작/종료 시각 | result.json의 UTC 시각 두 값 | 어느 실행의 결과인지 연결 |
| 종료 코드 | exitCode=0 | 도구의 정상 종료/판정 확인 |
| 출력 로그 크기 | logBytes=0 | 이번에는 출력이 없었다는 관찰 |

ESLint의 종료 코드 0은 Lint 오류 없이 검사를 마친 결과다. 경고 상한 옵션을 썼다면 그 조건도 적용된다. [ESLint 공식 종료 코드 설명](https://eslint.org/docs/latest/use/command-line-interface#exit-codes)

따라서 이번 기록은 해당 소스/명령의 Lint 통과를 뒷받침한다. 빈 파일 하나만 있고 종료 코드나 실행 기준이 없다면 성공/실패를 미확인으로 둔다. Lint 통과가 DB Commit/원문 보존/실제 모델 정확도까지 증명하지는 않는다.

## 6 학습자가 직접 재현할 작은 검사

요청/Commit/화면의 문답을 마친 뒤 직접 실행하고 결과를 읽는 연습이다. 이번 대상은 TicketCustomerFollowupHttpIntegrationTest의 customer_and_official_reply_commit_in_parent_lock_order_without_skipping_a_page 한 메서드다. 고객 먼저/공식 답변 먼저를 파라미터로 실행하므로 현재 사례는 두 건이다. 같은 Ticket의 부모 잠금 대기, 두 Commit 뒤 메시지/페이지 순서와 기존 Row 보존을 검사한다.

1. Docker Desktop에서 Engine이 실행 중인지 확인한다. 기존 Container/Volume을 지우지 않는다.
2. PowerShell 7을 열고 아래 명령을 순서대로 실행한다. 현재 Lab 작업공간의 소스를 사용하며 개인 계정 암호나 API Key 입력은 필요하지 않다.
3. 마지막 Tests run/Failures/Errors/Skipped, BUILD 결과와 exitCode를 읽는다. 첫 두 Git 명령은 실행한 코드와 작업 트리 상태를 확인한다.

```powershell
Set-Location -LiteralPath 'C:\developer_folder\ai-helpdesk-learning-lab'
git rev-parse --short HEAD
git status --short
New-Item -ItemType Directory -Path '.\target' -Force | Out-Null
.\mvnw.cmd -B -ntp '-Dtest=TicketCustomerFollowupHttpIntegrationTest#customer_and_official_reply_commit_in_parent_lock_order_without_skipping_a_page' test 2>&1 | Tee-Object -FilePath '.\target\week12-self-reproduction.log'
$helpdeskJavaExit = $LASTEXITCODE
"exitCode=$helpdeskJavaExit"
```

현재 기준의 예상 결과는 Tests run: 2 / Failures: 0 / Errors: 0 / Skipped: 0, BUILD SUCCESS와 exitCode=0이다. 준비/컴파일/실행이 실패하면 이 예상치를 실적으로 기록하지 않고 실제 오류와 exitCode를 확인한다. 로그는 target/week12-self-reproduction.log에 남으며, 요약을 공유할 때는 실행한 Commit과 마지막 결과를 함께 적는다. 이 경로도 target 아래이므로 clean 시 지워진다.

이 명령의 test는 선택한 두 Java/PostgreSQL 사례를 실행한다. 전체 verify의 모든 Java/Failsafe, Node/Lint/Browser나 실제 상용 AI를 실행한 결과로 해석하지 않는다. 기존 28건 집중 클래스 결과나 전체 1,221+2 결과에 두 건을 추가 합산하지 않는다.

2026-11-04 참고 실행: 위 메서드 선택 인자를 사용한 Maven 명령은 clean commit fd879eb6758750afad6879e4c3a88094baa95b40에서 두 건/실패·오류·Skip 0, BUILD SUCCESS/exit 0을 확인했다. 새 Surefire Dump와 남은 소유 fork JVM은 0이었다. 이는 당시 Codex의 선택 명령 검증이다. 이후 사용자가 f226b53의 Clean 작업 트리에서 직접 같은 명령을 실행해 두 건/실패·오류·Skip 0, BUILD SUCCESS/exit 0을 확인했다. 원래 28건 XML은 덮어쓰기 전에 별도 복사했다.

참고 실행의 명령/Commit/시각/종료 코드와 로그는 ignored target/week12-focused-reproduction-20261104/result.json 및 maven.log, 이번 XML은 focused-report.xml, 이전 XML은 previous-class-report.xml에 있다. 위 Tee-Object 안내의 파이프와 로그 경로는 PowerShell 구문으로 확인했고, 실제 참고 실행은 같은 Maven 선택 인자를 사용해 별도 로그로 출력한 것이다.

## 7 Mockito 경고와 테스트 JVM 시작 설정

사용자의 직접 실행은 성공했지만 Mockito is currently self-attaching 경고가 있었다. Mockito가 inline Mock/Spy를 준비하기 위해 실행 중인 JVM에 Instrumentation Agent를 동적으로 연결했다는 뜻이다. Agent는 테스트에서 클래스 동작을 조정하도록 돕는 JVM 보조 코드다. 이 테스트의 Spy는 실제 JDBC Repository의 INSERT 호출을 관찰하며 DB는 격리 PostgreSQL이다. Provider Mock은 외부 AI 상호작용을 검사하는 용도다.

경고 출력의 색과 테스트 실패는 구분한다. 첨부 결과는 두 건 통과와 종료 코드 0이었다. 다만 이 연결 방식의 향후 JDK 제약에 대비할 필요가 있어 [프로젝트의 Mockito 5.23 공식 안내](https://github.com/mockito/mockito/blob/v5.23.0/mockito-core/src/main/java/org/mockito/Mockito.java#L188-L280)를 기준으로 테스트 시작 설정을 보완했다.

| 검토 대상 | 적용한 설정과 이유 |
|---|---|
| 실행 중 연결 허용 옵션 또는 시작 시 Agent 등록 | Surefire와 Failsafe의 -javaagent로 시작 시 등록. 동적 연결을 계속 허용해 경고를 숨기는 옵션은 추가하지 않음 |
| 개인 PC의 JAR 경로 또는 해석된 의존성 경로 | dependency:properties를 initialize에 연결해 org.mockito:mockito-core:jar 경로 사용. 기존 Boot 관리 버전과 test scope 유지 |
| 추가 JVM 옵션과 Agent의 결합 | 기본 빈 argLine을 정의하고 ${argLine} 뒤에 따옴표로 감싼 Agent 경로를 추가. 명령행 옵션 전달도 별도 확인 |
| 적용 범위 | pom.xml의 Maven 테스트 준비/Surefire·Failsafe fork JVM. Application의 실행 옵션, 전역 환경변수나 배포 설정을 바꾸지 않음 |

현재는 커버리지 Agent가 없다. 향후 JaCoCo 등에서 argLine을 실행 중 설정한다면 Mockito 문서의 후평가 방식과 옵션 결합을 함께 재검증한다. 이번 변경만으로 그 조합까지 검증했다고 설명하지 않는다. 기본 진입점은 기존과 같은 Maven test/verify 생명주기다.

사용자의 직접 재현 성공은 설정 변경 전의 결과다. 아래 Agent 설정 검증과 합산하지 않으며 전체 회귀/Browser/실제 AI/CI 결과와도 구분한다.

설정 보완 뒤 기본 명령과 -DargLine=-XX:-EnableDynamicAgentLoading 조건에서 각각 같은 두 사례가 통과했다. 각 실행은 실패/오류/Skip 0, BUILD SUCCESS/exit 0이며 self-attaching/동적 Agent 경고는 0건이다. 새 Dump는 없고 로그에서 확인한 테스트 JVM PID도 종료됐다. 동일 사례의 두 조건 재실행이므로 새 네 사례를 추가한 것으로 세지 않는다.

검증 중 처음 시도한 후평가 표현식에서는 명령행 옵션 전달을 확인하지 못해 현재 ${argLine} 구성을 선택했다. JVM 플래그를 출력하는 진단은 시작 시 native 출력이 Surefire 통신에 섞여 Dump에 기록됐다. 따라서 해당 관찰 스크립트의 실패를 업무 테스트 실패로 해석하지 않고, Dump의 실제 플래그를 확인한 뒤 출력 진단 옵션을 뺀 기본/차단 조건을 재실행했다. PrintCommandLineFlags는 POM이나 일반 실행 명령에 추가하지 않았다.

별도의 Sharing is only supported for boot loader classes 경고는 두 검증에서 각각 남았다. 이 문서에서 해결한 self-attaching/동적 연결 경고와 구분하며 모든 JVM 경고가 사라졌다고 기록하지 않는다.

변경 전 사용자 Log와 XML, 진단 기록 및 최종 기본/차단 실행의 Log/XML/result.json은 ignored target/week12-mockito-startup-agent-20261104 아래에 보존했다. 최종 두 실행은 validated-default와 validated-strict에 있으며, 시작 시각/종료 코드/POM SHA-256/경고 수/종료 관찰을 기록했다. 원래 사용자 Log를 덮어쓰지 않았다.

이후 전체 확인은 Surefire 1,223건/Failsafe 2건, 실패/오류/Skip 0, BUILD SUCCESS/exit 0이었다. 이때 남은 self-attaching/동적 경고 각 한 건은 별도 Failsafe JVM에서 발생한 것을 로그로 확인했다. Failsafe에도 같은 시작 설정을 적용한 뒤 initialize와 Failsafe 두 목표만 실행해 해당 두 건을 동적 로딩 차단 조건에서 재검증했다. 두 건/실패·오류·Skip 0, BUILD SUCCESS/exit 0과 해당 경고 0건이었다. 마지막 Failsafe 설정 뒤 전체 verify를 다시 실행한 것으로 기록하지 않는다.

전체 확인의 근거는 full-verify 아래, 마지막 Failsafe의 근거는 validated-failsafe-jdk25 아래에 분리했다. 후자의 첫 시도는 Codex 셸의 기존 JAVA_HOME 경로가 유효하지 않아 Maven 실행 전에 중단됐다. 이 실패와 오래된 XML 복사본을 별도로 표시하고, 해당 실행에서만 설치된 JDK 25.0.5를 지정해 재개했다. 원래 환경값은 복원했으며 시스템 환경변수 값은 변경하거나 기록하지 않았다. 전체 확인은 Java 25.0.4, 마지막 Failsafe 확인은 25.0.5이므로 조건도 구분한다.
