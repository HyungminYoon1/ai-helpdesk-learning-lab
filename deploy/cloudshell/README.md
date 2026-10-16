# Private CloudShell에서 psql 17 사용하기

RDS PostgreSQL 17에 접속하는 관리 도구만 맞춘다. RDS Engine·Schema·계정·App Image는 바꾸지 않는다. CloudShell의 기본 `/usr/bin/psql`도 덮어쓰지 않는다.

## 인터넷 경로를 열지 않는 준비 방식

Private CloudShell에는 RDS 5432로 가는 관리 경로만 유지한다. 패키지를 받기 위해 NAT Gateway·Public Subnet·유료 Endpoint·새 EC2를 만들지 않는다.

인터넷에 연결된 **로컬 Docker**에서 AWS 공식 Amazon Linux 2023 Image와 서명된 `postgresql17` 패키지를 사용해 Client만 추출한다. [Dockerfile](./Dockerfile)은 Base Image Digest·Release·17.11 패키지를 고정한다. DB Server 패키지는 설치하지 않는다. 추출물은 psql 실행 파일·전용 libpq·Wrapper·저작권 고지 네 개다. [AWS 패키지 목록](https://docs.aws.amazon.com/linux/al2023/release-notes/new-AL2-AL2023.12.html)

```powershell
docker build --platform linux/amd64 --output 'type=local,dest=local/tools/psql17/bundle' deploy/cloudshell
node scripts/cloudshell-psql17.mjs --part-count
```

현재 Manifest와 일치하는 준비 명령은 32부분이다. 생성기는 [Manifest](./psql17-manifest.json)의 크기·SHA-256과 Archive의 네 파일·경로·일반 파일 여부를 검사한다. 압축 파일은 `local/tools/`에만 두며 Git에 넣지 않는다. 변경된 패키지를 사용하려면 별도 검토와 Manifest·Test 갱신이 필요하다.

## 같은 CloudShell에 작은 부분을 순서대로 붙여 넣기

기존 **서울 CloudShell VPC 환경의 Bash `~ $`**에서 진행한다. psql의 `helpdesk=>`에서 실행하지 않는다. 세션이 끊어져도 RDS는 변경하지 않으며, 새 VPC 환경을 임의로 만들지 않는다.

로컬 **PowerShell 7**에서 첫 부분만 클립보드로 복사한다.

```powershell
$psqlPart = node scripts/cloudshell-psql17.mjs --part 1
if ($LASTEXITCODE -ne 0) { throw 'PSQL17_PREPARE_FAILED' }
Set-Clipboard -Value (($psqlPart -join "`n") + "`n")
```

CloudShell에 붙여 넣는다. 이어서 로컬 명령의 번호만 2, 3, …, `--part-count`의 마지막 번호까지 바꿔 **같은 CloudShell에 차례로** 붙여 넣는다. 마지막 부분 전에는 Shell의 계속 입력 표시가 나오는 것이 정상이다. 중간에 별도 명령·주석·Password를 입력하지 않는다. 각 부분은 20,000자 이하이며 위 PowerShell 명령은 출력의 줄바꿈을 LF로 복원한 뒤 복사한다.

전체 출력은 약 621KB이므로 한 번에 붙여 넣지 않는다. 실제 CloudShell에서 큰 붙여 넣기가 잘린 사례가 있었다. 파일 전송 URL·AWS Credential 전달 권한을 추가하는 대신 작은 부분으로 나눈다. 중간 부분을 놓쳤거나 세션이 끊겼으면 일부를 임의로 이어 맞추지 말고, 입력 중인 명령을 취소한 뒤 처음부터 다시 준비한다. **최종 `psql (PostgreSQL) 17.11` 출력 전에는 활성화 완료로 보지 않는다.**

명령은 공개 실행 파일만 전달한다. 저장소 생성기는 `.env`·Credential·Parameter Store를 읽지 않으며 Network·DB에 접속하지 않는다. 생성 명령·클립보드에 암호를 넣지 않는다.

## 실행과 재접속

CloudShell은 `/tmp/helpdesk-psql17-*` 아래에 전용 도구를 복구하고 Hash를 재확인한다. x86_64·필요 도구·17.11 실행을 확인한 뒤에만 현재 Shell의 `PATH` 앞에 그 `bin`을 추가한다. `sudo`·시스템 패키지 설치·전역 라이브러리 변경은 하지 않는다. Wrapper의 전용 `LD_LIBRARY_PATH`는 자식 psql 한 개에만 적용한다.

```sh
command -v psql
psql --version
/usr/bin/psql --version
```

첫 명령은 `/tmp/helpdesk-psql17-…/bin/psql`, 두 번째는 17.11이어야 한다. 세 번째는 원래 도구의 보존을 확인하는 용도다. 이 도구는 `pg_dump`·`pg_restore`·서버 업그레이드 도구를 제공하지 않는다.

이후 [기존 CA 포함 접속 명령](../iam/db-bootstrap.md#읽기-전용-재접속-명령)을 생성해 붙여 넣고 `Password:`에 App DB 암호를 직접 입력한다. 접속 생성기는 17.x가 아니면 `PSQL17_REQUIRED`로 중단한다. `sslmode=verify-full`·서버 이름 검증·공개 CA Hash·읽기 전용 기본값은 유지한다.

```sql
SHOW server_version;
SELECT ssl, version FROM pg_stat_ssl WHERE pid = pg_backend_pid();
\d public.tickets
```

`\q`로 psql만 끝내면 현재 Bash에서 준비한 도구는 재사용할 수 있다. 반면 VPC 환경 세션이 끝나면 홈·`/tmp`·PATH가 사라질 수 있으므로 **도구 준비 → 접속 명령 생성 → Password 직접 입력**을 다시 한다. `/tmp` 파일을 홈으로 옮겨 영구 보관할 수 있다고 가정하지 않는다. [CloudShell VPC 제약](https://docs.aws.amazon.com/cloudshell/latest/userguide/using-cshell-in-vpc.html)

## 검증과 결정 기록

선택지는 ① 기본 16 Client 유지 ② RDS를 16으로 변경 ③ Private 환경의 인터넷·관리 권한 확대 ④ 공식 Client만 분리 전달이었다. 서버를 변경하지 않는 사용자 요청과 기존 Network 격리를 함께 지키기 위해 ④를 선택했다. 기본 16 Client·App·DB는 그대로 두고, 잘못된 Hash·경로·Version이면 실행 전에 중단한다. 영향 파일은 이 디렉터리, 두 `cloudshell-*` 생성기·Test와 DB 접속 안내다. App의 Java 업무 로직과 배포 Image에는 포함하지 않는다.

```powershell
node --test src/test/js/cloudshell-psql17.test.mjs src/test/js/cloudshell-rds-connect.test.mjs
```

Unit Test의 가짜 실행 파일·Bash 문법 검사는 실제 RDS 접속과 구분한다. 실제 Client는 별도로 비Root·Network 차단 로컬 Container와 Private CloudShell에서 실행한다. Cloud에서는 17.11 선택·기존 16 보존·RDS 17.11 TLS 1.3·`\d`·업무 Table Hash 불변을 확인했다. 날짜와 실행 근거는 WIL 실습 보고서에 기록한다.

후속 검토는 CloudShell OS·CPU Architecture 변경, 공식 패키지 보안 갱신, 서버 Major 변경 때 진행한다. 이 최소 Bundle은 현재 CloudShell의 OS 라이브러리를 사용하므로 모든 Linux 환경에서 실행된다고 일반화하지 않는다. DB Backup·Restore 도구는 별도로 Version 호환성을 검토한다.
