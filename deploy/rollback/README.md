# 준비 버전 P의 검증과 ECR 전달

P는 현재 Cloud 기준 Source에서 새 실패 코드 두 개의 읽기만 준비한 버전이다. 새 개인정보 검사 처리·운영 V8은 포함하지 않는다. 현재 main은 후속 구현을 포함하므로 main의 Image를 P라고 표시하거나 P Branch를 main에 병합하지 않는다.

## 업로드할 Source

[prepare-release.json](./prepare-release.json)은 검토한 P Commit `77359c9779a0a9cb7f505fe4b82b7f97c5ea02be`와 기준 Commit을 고정한다. [P의 로컬 검증 안내](https://github.com/HyungminYoon1/ai-helpdesk-learning-lab/blob/codex/week8-rollback-prepare/deploy/rollback/prepare.md)에 V7/V8 PostgreSQL·Container/JVM·Session·접수·조회 결과와 범위를 기록했다.

운영 변경은 Java Enum·UI 두 파일이며 SHA-256을 고정한다. Dockerfile·POM·운영 V1~V7·기존 Workflow는 기준 Source와 같아야 한다. 추가 변경은 검토한 Test·Fixture·로컬 Compose 검증·안내 10개 파일만 허용한다. 변경 경로·생산 코드 Hash·V8 Fixture·기준 Commit ancestry를 모두 통과해야 한다.

## CI와 업로드 순서

[수동 P Workflow](../../.github/workflows/verify-prepare.yml)는 main에서만 시작한다. 실행할 Source SHA를 사용자가 입력받지 않고 main에서 관리하는 Manifest를 읽는다. 기존 [main 검증 Workflow](../../.github/workflows/verify.yml)와 기존 AWS IAM Trust·Permission은 변경하지 않는다.

1. main의 [Source Guard](../../scripts/verify-prepare-release.mjs)가 고정한 P Commit·Branch 계보·변경 범위와 파일 Hash를 확인한다.
2. 해당 P Commit을 별도 디렉터리에 Checkout한다. 이 단계에는 AWS 인증 권한·AI Key를 주지 않는다.
3. P의 전체 Java/PostgreSQL Testcontainers, JavaScript·ESLint를 실행한다.
4. 동일 Checkout의 깨끗한 Source로 Image를 Build한다. Label에는 실제 P Commit을 넣고, 포장된 운영 Migration 일곱 개와 V8/Test Fixture 부재를 확인한다.
5. 업로드를 선택한 실행만 Image를 보관 기간 1일의 Artifact로 전달한다. 별도 Publish Job에서 Image ID·P Commit·Platform·역할 Label을 확인한 후 AWS에 인증한다.
6. main 실행 문맥의 기존 OIDC Role로 기존 서울 ECR에 한 번 Push한다. 다시 Build하거나 기존 Image를 삭제하지 않고 Push Digest와 ECR의 실제 Manifest Digest를 대조한다.

CLI 실행 예시는 다음과 같다. 권한을 가진 사용자가 업로드를 승인한 때만 `true`로 지정한다.

```powershell
gh workflow run verify-prepare.yml --ref main -f publish_image=true
```

새 Tag는 `prepare-git-<P_COMMIT>-run-<RUN_ID>-attempt-<RUN_ATTEMPT>`다. Source Commit과 Workflow Commit은 다를 수 있으므로 Evidence에 두 값을 따로 남긴다. Source Guard를 바꾸지 않은 새 P Commit은 자동으로 업로드 대상이 되지 않는다. Manifest 변경은 새 검토·Commit이 필요한 작업이다.

## 배포와 구분할 것

원격 검증의 Image는 Commit Label을 넣어 새로 Build한 산출물이다. 앞서 로컬에서 미커밋 Source로 만든 Image와 ID가 같다고 가정하지 않는다. ECR에는 이번 CI가 실제 검사·Build한 Image가 전달돼야 하며, 후속 Task에는 업로드 후 확인한 Digest를 사용한다.

Image 업로드는 App 실행이 아니다. 이 Workflow는 ECS·RDS·ALB·DNS·Secret·IAM을 변경하지 않는다. 기존 A·후속 B Image를 유지하고, 실제 A→P→B·B→P 전환은 현재 DB 호환성·실행 명세·승인 범위를 확인한 뒤 진행한다. ECR의 P가 있다는 사실만으로 Cloud에서 P가 동작하거나 Rollback이 완료됐다고 기록하지 않는다.

## 결정 기록

- 맥락: P는 기존 main보다 이전 Source에서 분리했지만 업로드 Role은 main 실행 문맥만 허용한다. 현재 main을 P로 되돌리면 후속 구현과 기존 작업을 손상한다.
- 선택지: IAM을 P Branch까지 넓히기, main Source를 P로 바꾸기, main의 고정 Manifest로 P를 검증·전달하는 별도 수동 Workflow를 사용하기.
- 결정: 세 번째 안을 사용한다. 기존 main Workflow·OIDC Subject·서울 Repository·단기 Credential·Image 재사용 조건을 유지한다.
- 이유: Source와 업로드 제어를 분리해 지정 P만 검증하며 AWS 권한이나 실제 서비스 실행을 늘리지 않기 위해서다. P의 Test 코드 실행은 AWS 권한 없는 Job에서 끝내고 Publish Job은 전달받은 Image를 실행하지 않는다.
- 영향 파일: Manifest·Source Guard·선택형 Workflow·안전장치 Test·Lint 대상·README와 이 안내. Java 업무 코드·Schema·기존 IAM은 변경하지 않는다.
- 후속 검토: 원격 CI와 Image Evidence의 실제 결과, P 이후 새 쓰기 활성화 전 호환성, ECR 산출물 보존 필요성. 새 Source 허용이나 Cloud 실행은 별도 범위다.
