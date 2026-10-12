# ECS 실행 명세 초안

> 상태: Draft — 자리표시자가 남아 있으며 AWS에 등록·실행하지 않음

[task-definition.template.json](./task-definition.template.json)은 첫 Cloud 연결에서 사용할 Helpdesk 실행 명세다. PostgreSQL과 배포용 USER·AGENT를 연결하되 Provider·Worker는 명시적으로 끈다. 먼저 서버·DB·로그인·HTTPS를 확인한 뒤 전체 AI 조립과 실행을 별도로 이어간다.

## 실행 명세가 정하는 것

`image`는 검증 뒤 ECR에 업로드한 Image의 URI와 Manifest Digest로 채운다. 로컬 Image ID를 그대로 넣지 않는다. 첫 초안의 Linux·X86_64는 현재 검증한 `linux/amd64` Image에 맞춘 선택이며, CPU·메모리는 실측과 비용 비교 뒤 확정한다. `awsvpc`의 Container Port 8080은 App의 수신 Port이지 인터넷 공개 허용 규칙이 아니다. [AWS Task Definition](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)

Execution Role은 ECR Pull·Secret 전달·Log 전송에 사용한다. 현재 App은 JDBC와 외부 AI의 HTTP API를 사용하므로 이 초안에는 Task Role을 추가하지 않았다. Java가 AWS API를 직접 호출하는 기능을 추가하거나 ECS Exec 등을 선택한다면 필요한 Task Role 권한도 별도로 검토한다.

`secrets`의 여섯 항목은 서울의 Standard Parameter Store `SecureString`을 참조한다. Standard Throughput과 AWS 관리 Key `aws/ssm`을 선택했으며 Advanced·Higher Throughput·별도 Customer-managed Key는 추가하지 않는다. 값은 Image에 넣지 않고 ECS Agent가 Execution Role로 읽어 새 Container의 환경 변수로 전달한다. 여섯 Parameter는 Console에서 직접 등록했고 이름·Standard Tier·SecureString Type을 확인했다. 실제 값과 저장 후 Key 메타데이터는 읽지 않았다. Execution Role은 생성했으며 실제 ECS 전달은 아직 검증하지 않았다. [ECS Parameter Store 전달](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-ssm-paramstore.html)

## Parameter와 Execution Role

| 환경 변수 | Parameter 이름 |
|---|---|
| `SPRING_DATASOURCE_USERNAME` | `/helpdesk/learning/db/username` |
| `SPRING_DATASOURCE_PASSWORD` | `/helpdesk/learning/db/password` |
| `HELPDESK_DEPLOYMENT_USER_USERNAME` | `/helpdesk/learning/user/username` |
| `HELPDESK_DEPLOYMENT_USER_PASSWORD` | `/helpdesk/learning/user/password` |
| `HELPDESK_DEPLOYMENT_AGENT_USERNAME` | `/helpdesk/learning/agent/username` |
| `HELPDESK_DEPLOYMENT_AGENT_PASSWORD` | `/helpdesk/learning/agent/password` |

DB 계정은 App용 계정이며 RDS 관리자 Credential과 이후 AI Key는 이 여섯 항목에 포함하지 않는다. 이름은 저장할 위치일 뿐 실제 사용자명·Password가 아니다. 모두 `SecureString`·`Standard`로 생성하고 기존 같은 이름이 있으면 덮어쓰지 않는다. 계정의 처리량 설정이 이미 Higher Throughput이면 다른 프로젝트를 고려해 변경 여부를 별도로 확인한다.

[execution-role-policy.template.json](./execution-role-policy.template.json)은 Execution Role의 Permission Policy 초안이다. ECR 인증 Token을 받는 작업만 `Resource: "*"`이며, Pull은 해당 Repository, Log는 사전 생성한 한 Group의 Stream, `ssm:GetParameters`는 위 여섯 ARN으로 제한했다. Parameter 쓰기·삭제·경로 전체 조회, Secrets Manager 조회와 포괄적인 KMS 복호화는 추가하지 않았다. 기본 `aws/ssm` 대신 Customer-managed Key를 사용한다면 해당 Key의 복호화 권한을 다시 검토한다. [AWS 실행 Role 권한](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_execution_IAM_role.html#task-execution-secrets)

이 파일은 Trust Policy가 아니며 Role을 생성하거나 정책을 연결하는 명령도 아니다. [execution-role-trust.template.json](./execution-role-trust.template.json)은 누가 Role을 사용할 수 있는지 정한다. `ecs-tasks.amazonaws.com`의 `sts:AssumeRole`만 허용하고 요청의 Account·서울 ECS Source ARN을 제한한다. Source ARN의 끝 `*`는 동일 Account·Region의 ECS 요청 범위이며 모든 신원·모든 AWS 작업을 허용하는 것은 아니다. ECS의 특정 Cluster만을 Source ARN으로 제한하는 방식은 공식 안내에서 지원하지 않으므로 사용하지 않는다. [ECS 신뢰 정책의 조건](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-iam-roles.html)

실제 Role에는 이 신뢰 정책과 권한 정책을 함께 구성하고 더 넓은 정책이 붙어 있지 않은지 확인한다. Template 구조 Test와 실제 Agent의 Role 사용·필요 권한 확인은 구분한다. Task Role·GitHub 업로드 Role에는 Parameter 읽기 권한을 추가하지 않는다. 실제 Account ID·Log Group은 Git 제외 로컬 복사본에서 채운다. 권한을 적용하기 직전 생성·연결할 범위를 확인하고 승인받는다.

적용 범위를 승인받아 새 Role `helpdesk-ecs-execution`과 인라인 정책 `helpdesk-execution-minimum`을 생성했다. 저장된 Trust·Permission JSON을 승인한 초안과 대조해 일치를 확인했고 연결 정책은 이 인라인 정책 한 개뿐이었다. Log 전송 대상인 서울의 `/ecs/helpdesk-learning`도 Standard 클래스·365일 보존으로 생성했다. 목록에서 이름·클래스·12개월 보존 표시를 확인했다. 기존 Role이나 더 넓은 관리형 정책은 변경·연결하지 않았다.

저장소 변경은 이미 실행 중인 Task를 갱신하지 않는다. Password 값만 교체했다면 같은 Image로 새 Task를 시작하되 DB의 실제 Password도 일치시킨다. Parameter 읽기 실패와 JDBC Password 인증 실패는 발생 단계가 다르다.

## 실습 종료와 재개

실행 수를 0으로 내린 뒤 Service Task뿐 아니라 단독 실행 Task도 남지 않았는지 확인한다. ALB는 Task가 없어도 과금되고, RDS Stop은 Storage·Backup 비용을 중단하지 않으며 7일 후 자동 재시작된다. 장기 휴지기의 기본 절차는 **쓰기·Worker 중단 → 마지막 Snapshot → 별도 복원 검증 → 승인된 ALB·DB Instance 정리 → 필요한 Image·Snapshot·Parameter 보관**이다. 실제 삭제 명령이나 자동 정리는 이 초안에 넣지 않았다.

재개할 때는 Snapshot에서 새 RDS를 복원하고 Endpoint·DB Password·TLS를 확인한다. ALB·Target Group을 다시 구성해 DNS 대상·Task 설정을 맞춘 뒤 같은 검증 Image를 실행한다. 처음에는 AI를 꺼서 미완료 Job의 추가 호출을 막고 기존 Row·로그인·CSRF를 확인한 다음 승인된 AI 실험을 이어간다. Snapshot·Image 보관료는 남으므로 휴지기를 비용 0원으로 표현하지 않는다. [RDS 중지](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_StopInstance.html), [RDS 복원](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_RestoreFromSnapshot.html)

NAT Gateway·상시 유료 Endpoint·고정 Elastic IP는 첫 구성에 추가하지 않는다. CloudWatch Log는 요청한 1년(365일)을 보존하며 필요한 비밀값 없는 근거는 별도 기록한다. 각 Log Event의 보존 기간이지 Group을 생성 후 1년 만에 삭제하는 설정은 아니다. 보관량이 누적될 때의 비용도 확인한다. 이전 정상 Image와 현재 배포 Image는 Rollback용으로 유지하며 승인 없이 자동 삭제하지 않는다.

## Container 실행 설정

`SPRING_APPLICATION_JSON`에는 비밀값 없이 Forwarded Header 처리와 Secure·HttpOnly·SameSite Cookie 설정을 적었다. App의 Inbound는 ALB Group에서만 허용하고 Proxy 신뢰 범위를 확인해야 한다. 이 설정의 실제 해석과 HTTPS Browser 동작은 후속 실행에서 검증한다. [Spring Boot JSON 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.application-json)

Log Group은 사전 생성한 대상으로 참조하고 자동 생성은 끈다. 보존 기간은 Log Group 설정이며 변경을 위해 JAR·Image나 Role 권한을 바꾸지는 않는다. 실제 Task의 Log 전달은 후속 실행에서 확인한다. `stopTimeout=45`는 기존 로컬 종료 대기에 맞춘 초기 값이며 전체 Job 종료 성공을 보장하지 않는다. Role·Secret·Log·종료 옵션의 필드 의미는 [AWS Fargate Parameter 안내](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html)에서 확인한다.

## 등록 전에 채울 값과 확인할 조건

| 자리표시자 | 필요한 확인 |
|---|---|
| Image URI와 Digest | 변경 Commit의 CI 통과·ECR 업로드·Manifest 대조 |
| CPU·Memory | 지원하는 조합, 대표 요청·기동·Worker를 포함한 측정과 최소 비용 비교 |
| Execution Role | 필요한 ECR·Secret·Log만 허용한 Role의 참조 |
| JDBC URL의 Endpoint·DB 이름 | `verify-full`·Image CA 경로를 유지하고 실제 TLS 연결 확인 |
| Parameter의 Account ID | 위 여섯 Standard SecureString의 존재·Type·Tier·Key와 지정 ARN 읽기 권한 |
| Log Group | 서울의 생성 범위·최소 보존 기간과 권한 |

RDS의 TLS는 Browser→ALB HTTPS와 별개다. [서울 공개 CA 묶음](../../docker/rds/README.md)을 Runtime의 `/app/certs/rds-ca-bundle.pem`에 제공하도록 Dockerfile을 보완했다. 초안 URL은 `sslmode=verify-full`·`sslrootcert`를 명시하며 Endpoint·DB 이름만 자리표시자로 남긴다. 인증서 검증을 끄는 방법으로 연결 실패를 해결하지 않는다. [RDS PostgreSQL TLS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.SSL.html)

로컬 재Build에서 비Root 사용자의 파일 읽기·Hash·Mode를 확인했다. 이전 ECR Image에는 이번 변경이 없으므로 실제 배포 전 새 CI Image의 검증·승인된 업로드·Digest 대조가 필요하다. 파일 준비는 실제 RDS의 인증서 사슬·Hostname·DB TLS 연결 검증과 구분한다.

로컬의 AI 비활성 접수·조회 측정에서는 `0.25 CPU·512 MiB`로 두 번 통과했다. [측정 절차](../../README.md#배포용-image의-실행-자원-측정)를 첫 연결 사양의 근거로 사용하되, 이 초안의 CPU·Memory 자리표시자는 전체 구성·비용·생성 범위 확인 뒤 채운다. 개인정보 처리기·Provider·Worker를 켠 측정과 Cloud 실행에서도 다시 확인한다.

실제 참조를 채운 로컬 복사본은 Git 제외 경로 `local/deployment/`에서 관리한다. 비밀값을 입력하지 않고 참조만 사용하며, 이 공개 Template의 자리표시자를 실제 계정 ARN·내부 주소로 바꾸어 Commit하지 않는다. 전체 Task 설정·환경 변수·Credential을 출력하지 않는다.

Task Definition 밖의 Service·Subnet·Security Group·Public IP·ALB·Target Group·ACM·DNS·Backup도 구성해야 한다. 수량·최소 비용·공개 범위와 생성 승인을 확인한 뒤 등록·실행한다. `desiredCount`와 교체 전략은 Service 설정에 속하며 이 JSON에 넣지 않는다.

## 로컬 구조 Test

```powershell
node --test src/test/js/ecs-task-definition-template.test.mjs src/test/js/ecs-execution-role-policy.test.mjs
```

이 Test는 JSON 문법·필드 조합, 비밀값 대신 Parameter 참조, 명세와 Permission Policy의 여섯 ARN 일치, Profile·AI 비활성·Cookie 초안과 미확정 값의 유지 여부를 검사한다. AWS API를 호출하지 않으며 AWS의 전체 Schema 검증·IAM 평가·실제 Secret 접근·Spring 설정 Binding·Cloud 실행을 대신하지 않는다. 기존 CI의 JavaScript Test 대상에 포함된다.

Trust Policy의 ECS 주체·같은 Account와 서울 조건 Test 두 개를 추가한 뒤 JavaScript 전체 173개와 ESLint가 통과했다. 이는 로컬 Template 검사이며 실제 Role 생성·사용 근거는 아니다.
