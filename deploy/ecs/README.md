# ECS 실행 명세 초안

> 상태: Draft — 자리표시자가 남아 있으며 AWS에 등록·실행하지 않음

[task-definition.template.json](./task-definition.template.json)은 첫 Cloud 연결에서 사용할 Helpdesk 실행 명세다. PostgreSQL과 배포용 USER·AGENT를 연결하되 Provider·Worker는 명시적으로 끈다. 먼저 서버·DB·로그인·HTTPS를 확인한 뒤 전체 AI 조립과 실행을 별도로 이어간다.

## 실행 명세가 정하는 것

`image`는 검증 뒤 ECR에 업로드한 Image의 URI와 Manifest Digest로 채운다. 로컬 Image ID를 그대로 넣지 않는다. 첫 초안의 Linux·X86_64는 현재 검증한 `linux/amd64` Image에 맞춘 선택이며, CPU·메모리는 실측과 비용 비교 뒤 확정한다. `awsvpc`의 Container Port 8080은 App의 수신 Port이지 인터넷 공개 허용 규칙이 아니다. [AWS Task Definition](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)

Execution Role은 ECR Pull·Secret 전달·Log 전송에 사용한다. 현재 App은 JDBC와 외부 AI의 HTTP API를 사용하므로 이 초안에는 Task Role을 추가하지 않았다. Java가 AWS API를 직접 호출하는 기능을 추가하거나 ECS Exec 등을 선택한다면 필요한 Task Role 권한도 별도로 검토한다.

`secrets`의 여섯 항목은 Credential 값이 아니라 참조 위치다. 참조 여섯 개가 유료 Secret 여섯 개를 뜻하지는 않는다. 여러 JSON Field를 한 Secret에 묶는 방식과 Parameter Store 등을 비용·권한·갱신 조건으로 비교한 뒤 확정한다. JSON Field 참조를 쓰면 Linux Fargate의 지원 Platform Version도 확인한다.

`SPRING_APPLICATION_JSON`에는 비밀값 없이 Forwarded Header 처리와 Secure·HttpOnly·SameSite Cookie 설정을 적었다. App의 Inbound는 ALB Group에서만 허용하고 Proxy 신뢰 범위를 확인해야 한다. 이 설정의 실제 해석과 HTTPS Browser 동작은 후속 실행에서 검증한다. [Spring Boot JSON 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.application-json)

Log Group은 사전 생성 대상으로 참조하고 자동 생성은 끈다. `stopTimeout=45`는 기존 로컬 종료 대기에 맞춘 초기 값이며 전체 Job 종료 성공을 보장하지 않는다. Role·Secret·Log·종료 옵션의 필드 의미는 [AWS Fargate Parameter 안내](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html)에서 확인한다.

## 등록 전에 채울 값과 확인할 조건

| 자리표시자 | 필요한 확인 |
|---|---|
| Image URI와 Digest | 변경 Commit의 CI 통과·ECR 업로드·Manifest 대조 |
| CPU·Memory | 지원하는 조합, 대표 요청·기동·Worker를 포함한 측정과 최소 비용 비교 |
| Execution Role | 필요한 ECR·Secret·Log만 허용한 Role의 참조 |
| JDBC URL의 Endpoint·DB 이름 | `verify-full`·Image CA 경로를 유지하고 실제 TLS 연결 확인 |
| Secret 참조 | DB 계정과 배포 USER·AGENT의 참조·권한·Version |
| Log Group | 서울의 생성 범위·최소 보존 기간과 권한 |

RDS의 TLS는 Browser→ALB HTTPS와 별개다. [서울 공개 CA 묶음](../../docker/rds/README.md)을 Runtime의 `/app/certs/rds-ca-bundle.pem`에 제공하도록 Dockerfile을 보완했다. 초안 URL은 `sslmode=verify-full`·`sslrootcert`를 명시하며 Endpoint·DB 이름만 자리표시자로 남긴다. 인증서 검증을 끄는 방법으로 연결 실패를 해결하지 않는다. [RDS PostgreSQL TLS](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.SSL.html)

로컬 재Build에서 비Root 사용자의 파일 읽기·Hash·Mode를 확인했다. 이전 ECR Image에는 이번 변경이 없으므로 실제 배포 전 새 CI Image의 검증·승인된 업로드·Digest 대조가 필요하다. 파일 준비는 실제 RDS의 인증서 사슬·Hostname·DB TLS 연결 검증과 구분한다.

로컬의 AI 비활성 접수·조회 측정에서는 `0.25 CPU·512 MiB`로 두 번 통과했다. [측정 절차](../../README.md#배포용-image의-실행-자원-측정)를 첫 연결 사양의 근거로 사용하되, 이 초안의 CPU·Memory 자리표시자는 전체 구성·비용·생성 범위 확인 뒤 채운다. 개인정보 처리기·Provider·Worker를 켠 측정과 Cloud 실행에서도 다시 확인한다.

실제 참조를 채운 로컬 복사본은 Git 제외 경로 `local/deployment/`에서 관리한다. 비밀값을 입력하지 않고 참조만 사용하며, 이 공개 Template의 자리표시자를 실제 계정 ARN·내부 주소로 바꾸어 Commit하지 않는다. 전체 Task 설정·환경 변수·Credential을 출력하지 않는다.

Task Definition 밖의 Service·Subnet·Security Group·Public IP·ALB·Target Group·ACM·DNS·Backup도 구성해야 한다. 수량·최소 비용·공개 범위와 생성 승인을 확인한 뒤 등록·실행한다. `desiredCount`와 교체 전략은 Service 설정에 속하며 이 JSON에 넣지 않는다.

## 로컬 구조 Test

```powershell
node --test src/test/js/ecs-task-definition-template.test.mjs
```

이 Test는 JSON 문법·필드 조합, 비밀값 대신 참조, Profile·AI 비활성·Cookie 초안과 미확정 값의 유지 여부를 검사한다. AWS API를 호출하지 않으며 AWS의 전체 Schema 검증·실제 Secret 접근·Spring 설정 Binding·Cloud 실행을 대신하지 않는다. 기존 CI의 JavaScript Test 대상에 포함된다.
