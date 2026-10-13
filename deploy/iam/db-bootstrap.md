# Private RDS의 최초 계정 설정

Private RDS에 처음 접속해 App용 DB 계정을 만드는 관리 통로다. 사람이 배포하는 IAM 신원, PostgreSQL 관리자, JDBC의 App DB 계정과 Helpdesk USER·AGENT를 구분한다. Parameter Store의 값 등록은 PostgreSQL 안의 계정 생성이 아니다.

## CloudShell과 Network

전용 CloudShell VPC 환경 한 개를 `helpdesk-private-a`와 임시 Group `helpdesk-learning-db-bootstrap`에 연결한다. Group의 Inbound는 없고 Outbound는 RDS Group의 TCP 5432 한 개다. RDS에는 기존 App Group 규칙을 유지한 채 Bootstrap Group의 TCP 5432만 임시로 추가한다.

Public DB·NAT Gateway·유료 VPC Endpoint·별도 EC2는 만들지 않는다. CloudShell에서 사용하는 `psql`은 AWS가 제공하는 사전 설치 도구다. Private Subnet에 인터넷 경로를 추가해 패키지를 설치하지 않으며, 도구와 공개 CA 준비를 먼저 확인한다. CloudShell 자체 이용료와 RDS·Storage 비용은 구분한다. [CloudShell의 VPC 환경](https://docs.aws.amazon.com/cloudshell/latest/userguide/using-cshell-in-vpc.html), [사전 설치 도구](https://docs.aws.amazon.com/cloudshell/latest/userguide/vm-specs.html)

## 임시 권한의 두 단계

[Bootstrap 정책 Template](./deployer-db-bootstrap-policy.template.json)은 기존 세 배포 정책과 별도로 사용한다. `helpdesk-deployer`의 기존 MFA·서울 제한은 유지하고 AWS 인증정보의 터미널 전달인 `PutCredentials`와 파일 전송 URL 생성을 명시적으로 거부한다. DB 접속 Password는 별도로 본인이 입력한다. Access Key나 Secret 값 조회 권한을 추가하지 않는다.

1. **환경 생성:** VPC·Private Subnet·Bootstrap Group의 실제 ID를 채우고 `UseOnlyConfirmedBootstrapEnvironment` Statement를 제외한다. 이 단계는 해당 조합의 환경 생성과 메타데이터 조회만 허용하며 터미널 Session은 허용하지 않는다.
2. **지정 환경 사용:** 생성된 환경 ID를 확인한 뒤 그 ID를 채운 전체 정책으로 갱신한다. 상태 조회·시작·Session·중지·정리는 지정 환경 ARN 하나에만 허용한다. `environment/*`를 사용해 다른 환경의 터미널 접근을 열지 않는다.

환경 한 개라는 수량은 실제 목록을 확인해 지킨다. IAM 조건만으로 생성 횟수의 상한을 강제했다고 표현하지 않는다. 정책을 생성·연결·갱신하는 작업은 관리자 신원으로 수행하며 배포 사용자에게 자기 권한을 수정할 권한을 주지 않는다.

CloudShell의 ENI 생성은 정확한 Subnet·Group ARN에 제한한다. 새 ENI는 `ManagedByCloudShell` 태그가 있어야 하고 생성 시에만 해당 태그를 쓸 수 있다. 사용 허가와 정리는 같은 Private Subnet·VPC의 해당 태그가 있는 ENI에 제한한다. Group 삭제 권한은 Bootstrap Group의 정확한 ARN과 `Project=helpdesk-learning`·`Purpose=db-bootstrap`에만 적용한다. 기존 App·RDS·ALB Group의 삭제 권한은 추가하지 않는다. [AWS의 CloudShell 권한 안내](https://docs.aws.amazon.com/cloudshell/latest/userguide/sec-auth-with-identities.html)

## DB 계정과 Migration

RDS 관리자 Credential을 App Task에 전달하지 않는다. 관리자 접속으로 필요한 DB·App 계정과 그 DB 안의 권한을 준비하고 접속을 종료한다. App 계정에는 `rds_superuser`·다른 계정 생성·다른 Database 생성 권한을 주지 않는다.

현재 Lab은 App 시작 시 같은 DataSource의 계정으로 Flyway를 실행하므로 자체 Schema의 `USAGE`·`CREATE`도 필요하다. 읽기 전용 계정만 주고 Migration 성공을 기대해서는 안 된다. 별도의 Migration 신원과 DML 전용 실행 신원 분리는 이후 필요할 때 검토하며, 이번 준비에서 조용히 다른 Credential 구성을 추가하지 않는다.

TLS에는 RDS Endpoint와 공개 CA를 사용해 `verify-full`을 유지한다. 관리자·App Password를 SQL 파일·명령 인자·Shell History·Log에 적지 않고 인증·Password 설정은 본인이 비표시 입력으로 수행한다. Parameter Store의 값과 DB의 실제 Password가 일치해야 한다. Credential을 읽거나 출력해서 일치 여부를 확인하지 않는다.

## 정리 순서

DB 초기 설정과 App의 실제 연결 확인이 끝난 뒤 적용한다. RDS 생성·접속·데이터 정리는 별도 승인 범위다.

1. DB 연결을 종료하고 터미널에 필요한 파일이 없는지 확인한다. CloudShell VPC 저장 공간은 임시이며 종료 후 보존되는 작업 공간이 아니다.
2. 지정 CloudShell 환경을 중지·삭제하고 그 환경이 사용한 ENI의 정리를 확인한다. 다른 환경·ENI는 삭제하지 않는다.
3. RDS에서 Bootstrap Group을 Source로 하는 추가 규칙 한 개만 제거한다. App Group의 기존 5432 규칙은 유지한다.
4. 연결·참조가 없음을 확인한 뒤 Bootstrap Group 한 개를 삭제한다.
5. 관리자 신원으로 임시 Bootstrap 정책을 분리한다. 기존 세 배포 정책과 Execution Role은 유지한다.

삭제 직전 실제 ID·이름·용도·연결 상태를 확인한다. 사용 중인 환경의 파일을 삭제하거나 다른 ENI를 정리하는 것으로 범위를 넓히지 않는다. 완료 뒤 환경·ENI·규칙·Group·임시 권한의 잔존 여부를 각각 확인한다.

## 로컬 구조 Test

```powershell
node --test src/test/js/db-bootstrap-policy.test.mjs src/test/js/iam-deployer-policy.test.mjs
```

JSON·정책 Size·서울·MFA, 환경 생성과 Session 허용의 분리, 정확한 Network 참조와 금지 권한을 검사한다. AWS 편집기 검증·실제 환경 생성·ENI와 터미널·DB 연결은 각각 별도로 확인한다.
