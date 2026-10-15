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

### 접속과 계정 준비 순서

1. 기존 환경의 `psql --version`을 확인한다. Private 환경에 인터넷 경로를 열어 도구를 설치하지 않는다.
2. 저장소에서 확인한 서울 공개 CA를 임시 디렉터리에 전달하고 Manifest의 SHA-256과 대조한다. 이 PEM은 공개 인증서이며 Password·Private Key가 아니다. 파일 전송 URL 권한이나 AWS Credential 전달을 허용하지 않고, 공개 인증서만 터미널에 붙여 넣는다.
3. 인증서 사슬·RDS Endpoint의 이름을 검증하는 TLS Handshake와 DB Password 인증을 따로 확인한다. 다음의 자리표시자는 실제 Endpoint와 공개 CA 경로로만 바꾼다.

```sh
openssl s_client -starttls postgres -connect '<RDS_ENDPOINT>:5432' \
  -servername '<RDS_ENDPOINT>' -CAfile '<PUBLIC_CA_PATH>' \
  -verify_hostname '<RDS_ENDPOINT>' -verify_return_error -brief < /dev/null

PSQL_HISTORY=/dev/null psql -X -W --set=ON_ERROR_STOP=on \
  "host=<RDS_ENDPOINT> port=5432 dbname=helpdesk user=postgres sslmode=verify-full sslrootcert=<PUBLIC_CA_PATH> connect_timeout=10"
```

`-W`의 Password 입력은 본인이 수행한다. Password 환경 변수·명령 인자·연결 URI의 Password 필드는 사용하지 않는다. `-X`는 별도 psql 시작 설정을 읽지 않으며, 이번 Session의 명령 기록 파일은 사용하지 않는다. TLS Handshake의 `Verification: OK`가 DB 로그인 성공을 의미하지는 않는다.

관리자로 접속한 뒤 지정 DB·Schema의 기존 Table·App 계정 존재를 확인한다. 다른 계정이나 기존 Table이 있다면 아래 초기화문을 그대로 재실행하거나 Password를 덮어쓰지 않는다. 빈 학습 DB와 계정 부재를 확인하고 권한 범위를 승인받은 뒤 한 Transaction으로 준비한다.

```sql
BEGIN;
CREATE ROLE helpdesk_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE helpdesk TO helpdesk_app;
GRANT USAGE, CREATE ON SCHEMA public TO helpdesk_app;
COMMIT;
```

비밀번호는 SQL에 넣지 않고 `\password helpdesk_app`의 입력·확인·제출을 본인이 수행한다. Parameter Store의 DB 사용자명은 이 계정과 일치해야 하며, App Password도 같은 값으로 준비한다. `\password`는 원문 Password를 명령 기록이나 서버 Log에 남기지 않도록 처리한다. [PostgreSQL psql](https://www.postgresql.org/docs/17/app-psql.html)

`public`을 유지하는 이유는 기존 Migration과 JDBC SQL에 Schema 지정이 없고 로컬 구성도 같은 Schema를 사용하기 때문이다. 새 Schema·별도 Migration Credential을 도입하는 안 대신 현재 구성을 유지해 JAR·Image·JDBC 설정을 바꾸지 않는다. Schema 사용·생성 권한과 App이 생성한 객체의 소유권으로 현재 Migration을 실행할 수 있게 하되, 관리 Role의 멤버십과 Superuser 권한은 주지 않는다. 운영에서 DDL과 DML 신원을 분리하는 후속 검토는 그대로 남긴다. [PostgreSQL Schema 권한](https://www.postgresql.org/docs/17/ddl-schemas.html)

App 계정으로 `verify-full` 재접속한 뒤에는 `pg_stat_ssl`의 현재 연결, `pg_roles`의 권한 Flag와 멤버십, DB `CONNECT`·Schema `USAGE/CREATE`를 확인한다. Password·Hash Column은 조회하지 않는다. 임시 Table의 생성·Identity INSERT·SELECT를 같은 Transaction에서 확인하고 Rollback해 Table·Row가 남지 않는지 검사한다. 이 권한 점검은 ECS App 기동·Flyway Migration·실제 Ticket 저장을 대신하지 않는다.

## 읽기 전용 재접속 명령

CloudShell **VPC 환경은 세션이 끝나면 홈 디렉터리까지 삭제**한다. `/tmp`의 CA를 홈으로 옮기는 것만으로는 해결되지 않는다. 일반 CloudShell의 영구 저장 공간과 구분한다. 세션이 유지되는 동안 `\q`로 psql만 종료하면 CloudShell까지 종료되는 것은 아니지만, 재접속 절차는 이전 CA 파일이나 Shell 변수의 잔존을 전제로 하지 않는다. [AWS의 VPC 환경 제약](https://docs.aws.amazon.com/cloudshell/latest/userguide/using-cshell-in-vpc.html)

[PowerShell 실행기](../../scripts/New-CloudShellRdsConnectCommand.ps1)는 [Node 생성기](../../scripts/cloudshell-rds-connect.mjs)를 사용해 공개 CA를 내장한 Bash 명령을 만든다. 이 JavaScript는 로컬 접속 도구이며 Helpdesk의 Java·Spring 업무 로직이나 AI Worker가 아니다. 새 유료 서비스·인터넷 경로·AWS 권한·DB 계정을 추가하지 않는다.

### 로컬 PowerShell에서 준비

PowerShell 7·Node.js 22 이상을 사용한다. **Lab 저장소 Root**에서 실행하며 `<RDS_ENDPOINT>`만 현재 서울 RDS의 Endpoint로 바꾼다. 실제 Endpoint·계정 ARN을 이 공개 문서에 기록하지 않는다.

```powershell
.\scripts\New-CloudShellRdsConnectCommand.ps1 -Endpoint '<RDS_ENDPOINT>' -CopyToClipboard
```

`CLOUDSHELL_CONNECT_COMMAND_COPIED`가 나오면 **기존 서울 CloudShell VPC 환경**의 `~ $`에 붙여 넣고 실행한다. 새 환경·NAT Gateway·EC2를 만들지 않는다. `helpdesk=>`에 붙여 넣는 SQL이 아니다. 클립보드가 동작하지 않으면 같은 명령에서 `-CopyToClipboard`만 빼고 출력된 전체 Bash 명령을 직접 복사한다.

Database는 `helpdesk`, Username은 `helpdesk_app`이 기본값이다. 다른 승인된 대상이 필요한 경우에만 `-Database`·`-Username`으로 명시한다. 생성기는 서울 RDS DNS 이름과 단순한 소문자 DB 식별자만 허용하며 URI·Password·임의 Shell 명령은 받지 않는다.

### CloudShell에서 실행되는 순서

1. `psql`·복원 도구의 존재를 확인한다. 없으면 설치·권한 확대 없이 중단한다.
2. 새 임시 디렉터리에 저장소의 공개 CA를 복구한다. 외부 다운로드나 이전 파일 검색은 하지 않는다.
3. [CA Manifest](../../docker/rds/bundle-manifest.json)의 SHA-256과 비교하고 파일을 읽기 전용으로 만든다. 불일치하면 접속 전에 중단한다. 생성 시에도 Manifest·공개 Root CA의 형식·유효 기간을 확인한다.
4. `CLOUDSHELL_RDS_CA_VERIFIED` 뒤 `psql -X -W`로 접속한다. `sslmode=verify-full`·명시적인 CA 파일·접속 제한 시간을 유지한다.
5. `Password:`에서 **App DB 암호**를 본인이 입력한다. `/helpdesk/learning/db/password`와 맞췄던 PostgreSQL 계정의 암호이며, Browser의 USER·AGENT·IAM·마스터 암호가 아니다. 암호 값은 스크립트·명령 인자·클립보드·문서에 넣지 않는다.
6. `helpdesk=>`에서 필요한 조회 SQL을 실행한다. `\q`로 종료하면 이번 실행이 만든 공개 CA 파일과 빈 임시 디렉터리만 정리한다. 기존 파일·DB Row·다른 환경은 삭제하지 않는다.

생성한 명령은 CA 생성·검증·접속을 한 번에 실행한다. CloudShell의 임시 저장 특성 자체를 바꾸거나 자동 로그인하는 기능은 아니다. **새 세션에서도 로컬 명령 생성 → 붙여 넣기 → Password 입력**으로 재접속한다. CA 갱신은 기존 Manifest·Test 검토 절차를 따르며 실행 중 자동으로 신뢰 대상을 바꾸지 않는다.

### 비밀값과 읽기 전용 범위

- PowerShell 실행기는 경로·임시 디렉터리 관련 값만 Node 자식 Process에 전달하고 프로젝트 Credential은 전달하지 않는다. Node 생성기는 `.env`·Parameter Store·환경 변수의 Secret을 읽거나 Network에 접속하지 않는다.
- CloudShell 명령은 상속된 `PGPASSWORD`·Service 설정·`PGHOSTADDR`를 사용하지 않고, 저장된 Password 파일 대신 새 임시 디렉터리의 존재하지 않는 경로를 지정한다. Password는 본인이 Prompt에 직접 입력한다.
- `PSQL_HISTORY=/dev/null`, `-X`, `ON_ERROR_STOP=on`, Pager 비활성과 UTF-8을 사용한다. 기본 Transaction은 읽기 전용이며 Statement 5초·Lock 대기 2초 제한을 둔다.
- 이는 **이 접속의 기본 실행 설정**이다. App DB 계정 자체를 읽기 전용 Role로 바꾸거나 관리자가 수동으로 설정을 변경하는 것까지 막는 권한 경계는 아니다. 기존 Flyway·App 권한은 변경하지 않는다.
- 인증서 복구 성공과 TLS·DB 인증 성공은 구분한다. 인증 실패·연결 실패는 자동 재시도하지 않는다. `RDS_CA_HASH_MISMATCH`이면 TLS 검증을 낮추지 말고 저장소의 CA·Manifest와 복사 과정을 점검한다.

### 로컬 검증

```powershell
node --test src/test/js/cloudshell-rds-connect.test.mjs src/test/js/rds-public-ca-bundle.test.mjs src/test/js/db-bootstrap-policy.test.mjs
```

생성기 Test는 공개 CA의 무손실 복구·입력 거부·읽기 전용 접속 옵션과 Bash 실행을 확인한다. Bash 실행의 `psql`은 Test Double이므로 실제 RDS 접속 근거가 아니다. 실제 CloudShell에서의 접속·TLS·Password 인증·SQL Row 조회는 따로 확인한다. Test는 실제 클립보드를 변경하거나 AWS·Database·유료 AI를 호출하지 않는다.

## 2026-10-14 확인 결과

기존 환경에서 공개 CA의 Hash와 TLS 1.3·서버 이름 검증을 확인했고, 관리자/App 계정으로 각각 `verify-full` 인증에 성공했다. 대상 DB의 빈 Schema·App 계정 부재를 확인하고 승인한 계정·권한을 준비했다. App 계정의 관리 권한과 Role 멤버십은 없었으며 DB 접속·Schema 사용/생성은 허용됐다. 임시 Table 생성·Identity INSERT 1건·조회 1건을 같은 Transaction에서 확인한 뒤 Rollback해 Table·Row가 남지 않는 것도 확인했다.

잘못된 서버 이름은 TLS 검사에서 거부됐고, Password를 제공하지 않은 비TLS 접속도 서버에서 `no encryption`으로 거부됐다. SQL의 `SHOW rds.force_ssl`은 설정 이름을 지원하지 않는다는 오류를 반환했으므로 직접 읽은 설정값으로 기록하지 않았다. 서버의 실제 접속 거부와 Client의 `verify-full`은 각각 확인한다.

Password 설정·확인 입력은 본인이 수행했으며, Parameter 값을 읽어 비교하지 않았다. 관리자와 App의 psql 접속은 종료했다. 이후 ECS에서는 지정 DB 암호 Parameter를 맞춘 같은 Image의 새 Task로 JDBC·Flyway·Spring 기동을 확인했다. 첫 실패와 복구는 [ECS 실행 안내](../ecs/README.md#첫-cloud-실행-결과)에 기록한다. Ticket의 실제 Cloud 접수·SQL Row 대조와 관리 통로 정리 여부는 별도 확인한다.

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
