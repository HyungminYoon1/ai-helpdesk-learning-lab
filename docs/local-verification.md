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
npx.cmd --yes --package 'eslint@10.11.0' eslint 'src/main/resources/static/*.mjs' 'src/test/js/*.mjs' > (Join-Path $helpdeskRun 'eslint.log') 2>&1
$helpdeskExit = $LASTEXITCODE
$helpdeskResult = [ordered]@{
    check = 'eslint@10.11.0'
    gitCommit = $helpdeskCommit
    workingTreeCleanAtStart = $helpdeskClean
    exitCode = $helpdeskExit
}
$helpdeskJson = $helpdeskResult | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $helpdeskRun 'result.json'), $helpdeskJson + "`n", [Text.UTF8Encoding]::new($false))
if ($helpdeskExit -ne 0) { throw "ESLINT_FAILED exit=$helpdeskExit" }
```

각 명령의 실행 결과는 따로 기록한다. 기록에 Credential, 환경변수 목록, Cookie, CSRF Token이나 API Key 값을 추가하지 않는다.

target은 Git에서 추적하지 않는 로컬 생성물이며 Maven clean을 실행하면 지워진다. 보존할 실행 증거는 따로 보관한다. 공유할 때는 필요한 조건/결과/한계를 정리하고 Log나 화면에 비밀값이 없는지 확인한다.
