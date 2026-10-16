# 학습용 RDS Snapshot 복원 준비

> 상태: 복원 전용 IAM V2·V1 보존, 복원 DB의 TLS·일곱 Table 건수/Hash·App 조회와 단일 App 순차 전환·원본 복귀 확인. 중지한 임시 DB와 해당 자동 Backup은 이후 별도 최종 승인으로 삭제 완료. 원본 DB·검증 Snapshot 유지
> 계획일: 2026-10-16 KST

## 검증 목적과 유지할 경계

원본 `helpdesk-learning`을 유지하고 Snapshot에서 별도 RDS를 복원해 실제 데이터와 App 조회를 비교한다. 기존 DB를 덮어쓰거나 이름을 바꾸지 않는다. Snapshot 복원과 이전 Image Rollback은 별개다. 복원 실험을 끝내도 아직 하지 않은 Image Rollback·전체 AI 배포를 완료 처리하지 않는다.

| 대상 | 지정 범위 |
|---|---|
| 원본 DB | `helpdesk-learning` 한 개. Schema·계정·Password·기존 자동 Backup 설정 유지 |
| 새 Snapshot | `helpdesk-learning-20261016-recovery` 한 개. Project=helpdesk-learning·비공개·원본 aws/rds 암호화 유지 |
| 새 복원 DB | `helpdesk-learning-restore-20261016` 한 개. PostgreSQL 17·db.t4g.micro·Single-AZ·gp3 20 GiB |
| Network | 기존 학습 VPC·Private DB Subnet Group·RDS Group. Public Access 비활성, 새 Group·규칙·NAT·Endpoint 없음 |
| 검증 App | 기존 검증 Image Digest·0.25 vCPU/512 MiB·동시 실행 최대 한 개. AI Provider·Worker·ECS Exec 비활성 |
| 보존 | 원본 DB·원본 접속 설정·검증 Snapshot 유지. 원본 DB·Snapshot 삭제 권한은 추가하지 않음 |

이름이 이미 존재하면 임의로 재사용·덮어쓰기·삭제하지 않고 소유와 결과를 확인한다. 승인 전에 새 리소스를 만들지 않는다. 새 복원 DB의 데이터에 업무상 새 쓰기를 하지 않는다.

## 한 번에 제시할 IAM 변경안

기존 Foundation·Runtime·Edge·조회·Bootstrap Policy를 수정하는 대신, [복원 전용 Policy Template](./deployer-db-recovery-policy.template.json)로 `helpdesk-deployer-db-recovery` 한 개를 만들고 `helpdesk-deployer`에 연결하는 안이다. 기존 Policy Version 삭제나 Role 변경은 필요하지 않다. Root의 적용과 실제 생성·실행은 따로 승인받는다. 비Root 배포 사용자에게 자기 IAM 변경·Access Key 생성 권한을 주지 않는다.

새 Policy의 모든 문장에 MFA·서울 Region 조건을 명시한다. Action은 다음 여덟 개뿐이다.

| Action | 추가 범위와 이유 |
|---|---|
| DescribeDBSnapshots | 서울의 같은 계정 Snapshot 목록 메타데이터. Console이 요청한 snapshot:*와 원본/복원 DB 필터를 지원하며, 다른 프로젝트의 Snapshot 이름·설정도 조회 가능해짐 |
| DescribeDBSnapshotAttributes | 지정 Snapshot 한 개의 공유 속성 조회. 공유 변경은 불가 |
| ListTagsForResource | 지정 Snapshot·복원 DB의 태그 조회 |
| CreateDBSnapshot | 원본 DB 한 개에서 지정 이름 Snapshot 한 개를 생성. 두 Resource를 각각 허용하며 Project 태그 요구 |
| RestoreDBInstanceFromDBSnapshot | 지정 Snapshot에서 지정 새 DB만 복원. 기존 Subnet·기본 PostgreSQL 17 Group 참조를 따로 허용 |
| AddTagsToResource | 지정 Snapshot·복원 DB 두 개에 Project=helpdesk-learning 태그 한 개만 허용 |
| StopDBInstance | 지정 복원 DB 한 개를 중지 |
| DeleteDBInstance | 검증 후 지정 복원 DB 한 개만 정리. 원본 DB·Snapshot 삭제 불가. 실제 삭제 직전 대상·보존 결과와 승인을 다시 확인 |

V2 Template은 MFA·서울·Project 태그·지정 새 DB·db.t4g.micro·20 GiB·Single-AZ·비공개 제한을 유지한다. PostgreSQL과 암호화는 지정한 암호화 PostgreSQL Snapshot으로, VPC는 지정 Subnet Group 참조로 범위를 유지한다. Security Group·gp3·추가 기능·자동 증설의 실제 선택은 실행 전후 별도로 확인한다. IAM이 모든 실행 설정과 비용을 강제한다고 주장하지 않는다.

AWS의 [공식 IAM 서비스 참조 JSON](https://servicereference.us-east-1.amazonaws.com/v1/rds/rds.json)에서 Action·Resource·Condition Key를 확인했다. Source Snapshot·Subnet·기본 Group의 권한을 새 DB 생성 권한과 구분한다. 로컬 구조 Test는 AWS의 실제 Allow 판정을 대신하지 않는다. 적용 전 AWS Policy Validation과 저장 JSON을 대조하고, 승인 범위를 넘는 누락이 나오면 멈춘다.

Source의 Key는 aws/rds로 확인했다. 새 KMS Key·복호화·Grant 권한이나 Secret 조회 권한은 이 초안에 추가하지 않는다. Customer-managed Key로 바뀌었거나 KMS 거부가 나타나면 범위를 다시 검토하며 암호화를 끄지 않는다. [RDS의 KMS Key 관리](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Overview.Encryption.Keys.html)

## IAM 적용 결과

2026-10-16 승인한 `helpdesk-deployer-db-recovery` Version 1을 생성했다. 실제 계정 식별자를 채운 JSON은 12개 Statement·여덟 Action·공백 제외 4,799자이며, AWS 편집기의 보안·오류·경고·추천은 모두 0이었다. 입력 JSON과 저장 후 복사한 전체 JSON을 각각 Template 적용본과 대조해 일치를 확인했다.

연결 직전 별도 승인을 받고 `helpdesk-deployer` 한 명에게만 연결했다. 사용자의 연결 정책은 기존 다섯 개를 유지한 여섯 개이며 기존 Policy Version과 Execution·GitHub Role은 변경하지 않았다. Root를 로그아웃하고 작성자가 비Root 로그인·MFA를 완료했다.

비Root에서 원본 DB의 사용 가능 상태와 기존 자동 Snapshot 세 개를 조회했다. 이전 `DescribeDBSnapshots` 거부는 해소됐고, 승인한 수동 Snapshot 이름은 목록에 없었다. 불필요한 `DescribeGlobalClusters` 거부는 그대로다. 원본 SQL 기준 결과를 확보한 뒤 지정 Snapshot 한 개를 생성했다. 생성 시각 14:51 KST·Available·원본/엔진/gp3 20 GiB·같은 aws/rds Key·Project 태그를 확인했다. 생성 폼에 태그 입력란은 없었지만 기존 태그 복사 설정으로 태그가 보존됐으며 권한 조건은 완화하지 않았다. 공유 설정의 Private·외부 계정 목록 없음도 읽기 전용으로 확인하고 취소했다.

원본에서 [읽기 전용 대조 SQL](../rds/recovery-verification.sql)을 오류 없이 실행했다. Ticket·Message·PENDING Job은 각각 2건, 본문/작성자 존재는 각각 2건, Attempt·Suggestion·Category는 각각 0건, 다섯 참조 누락도 모두 0건이었다. Job의 실행 Attempt·생성/보완 예약은 0이며 V1~V7 성공·Sequence와 일곱 Table의 건수/Hash를 기록했다. verify-full·TLS 1.3·Read-only·Repeatable Read를 유지하고 접속을 종료했다. 이 기준은 Git 제외 local/deployment에 보관한다.

관련 로컬 Test는 정책 38개와 SQL Source 보호 3개, 총 41개가 통과했다. 이어 JavaScript 전체 246개가 실패·건너뜀 없이 통과했고 ESLint 10.11.0도 통과했다. 실제 원본 SQL·Snapshot 결과와 로컬 Test는 구분한다. 이후 복원 DB·Row 대조·App 전환 결과는 다음 절에 기록한다.

복원 폼의 Single-AZ·db.t4g.micro·gp3 20 GiB·Private·기존 Network·Project 태그 등을 확인한 최초 요청은 지정 새 DB ARN의 `RestoreDBInstanceFromDBSnapshot` Allow 불충족으로 거부됐다. 별도 승인한 Root 읽기 전용 진단에서 해당 이벤트 한 건의 MFA=true·AccessDenied를 확인했다. `requestParameters`와 `responseElements`는 null이어서 정확히 어떤 조건이 맞지 않았는지는 확인하지 못했다. 이것을 실제 요청에 옵션이 없었다는 증거로 해석하지 않는다.

추가 승인을 받고 15:45 KST에 Version 2를 기본값으로 저장했다. `RestoreOnlyVerificationDb`의 `rds:DatabaseEngine`·`rds:StorageEncrypted`·`rds:Vpc` 세 조건만 제거했고 나머지 11문장·모든 Action/Resource·명시한 보안/비용 제한은 유지했다. 실제 저장 JSON은 승인한 V1-minus-three와 완전히 일치하며 12문장·여덟 Action·공백 제외 4,720자다. V1도 보존했다. 세 조건은 실패 원인이 확정된 항목이 아니라 Snapshot·Subnet에서 범위가 유지되는 속성에 대한 제한된 가설 검증이다.

Root 로그아웃 뒤 작성자가 비Root 로그인·MFA를 완료했다. 원본 한 개만 존재하는 목록과 사용 가능 상태를 확인하고, 새 세션에서 초기화된 복원 폼을 같은 승인 설정으로 다시 맞췄다. 지정 임시 DB 한 개의 재요청은 수락됐으며 목록에 원본 사용 가능·임시 DB 생성 중을 확인했다. 임시 DB 구성에는 PostgreSQL 17.11·db.t4g.micro·Single-AZ·gp3 20 GiB·같은 aws/rds 암호화·자동 증설 비활성·표준 모니터링/향상된 모니터링 비활성이 표시됐다. V2 조합으로 요청이 수락된 사실과 세 조건 중 어느 하나가 원인이었는지는 구분한다. 이후 실제 SQL 결과는 다음 절에 기록한다. V2 변경 후 JavaScript 전체 246개와 ESLint도 다시 통과했다.

## 복원 데이터 대조 결과

지정 임시 DB는 15:54 KST 생성 기록과 Available을 확인했다. 실제 Private Endpoint·기존 VPC/DB Subnet Group/RDS Group·5432·CA·Project 태그를 확인했고, Storage 초기화 안내는 사용 가능 상태와 별도로 남아 있었다. 기존 Private CloudShell에서 공개 CA Hash 검증 뒤 작성자가 App 암호를 직접 입력했다. `helpdesk_app`·`helpdesk`·verify-full·TLS 1.3·Read-only·Repeatable Read로 접속하고 원본과 같은 SQL을 실행한 뒤 종료했다.

일곱 Table 모두 건수와 내용 SHA-256이 일치했다. Ticket·Message·PENDING Job 각 2건, 본문/작성자 존재 각 2건, 실행 Attempt·생성/보완 예약 0, Attempt/Suggestion/Category 각 0건, 다섯 참조 누락 0건, Flyway V1~V7 성공과 세 Sequence 값도 같았다. 비밀값·원문·작성자 전체를 출력하지 않았고 비교 근거는 Git 제외 `local/deployment/rds-recovery-restored-baseline-20261016.json`에 보관했다. 비어 있던 제안 Table의 검증은 빈 상태 보존 범위다.

App 검증 준비로 `helpdesk-learning:2`를 등록했다. 실제 :1 JSON에서 JDBC Host만 복원 Endpoint로 바꿨고 저장 후 전체 JSON을 대조했다. Image·여섯 Secret 참조·Role·256 CPU/512 MiB·Profile·AI/Worker 비활성과 나머지 설정은 같다.

원본 App에서 AGENT 조회 성공을 확인하고 기존 Target을 해제했다. 300초 Draining·Target 0개·Task STOPPED 뒤 :2의 App 한 개를 실행했다. 실제 복원 Endpoint의 verify-full 연결·기존 Migration 7개·Spring 시작과 Target Healthy를 확인했다. 같은 Browser의 기존 Session은 로그인 필요로 처리됐고 작성자의 AGENT 재로그인 뒤 Ticket 1/2의 기존 제목·OPEN과 PENDING Job을 조회했다.

다시 300초 Draining과 검증 Task STOPPED를 확인한 뒤 :1의 원본 App 한 개를 실행했다. 원본 Endpoint·Migration 7개·RUNNING·같은 Digest·Target Healthy와 이전 Session 거부를 확인했고, AGENT 재로그인 뒤 원본 Ticket 1/2·OPEN·PENDING Job을 다시 조회했다. 동시 App 두 개·새 문의·AI 호출·Schema·Secret·DNS 변경은 없었다.

정리 직전 원본의 백업 표와 지정 Snapshot 상세에서 검증 Snapshot의 사용 가능 상태를 재확인했다. 최종 질문에서 작성자는 삭제하지 않고 임시 DB만 중지하도록 선택했다. 삭제창을 취소하고 추가 Snapshot 없이 지정 임시 DB만 중지 요청했으며 18:22 KST에 일시적으로 중지됨·연결 0개를 확인했다. 원본 App의 Job 조회와 단일 Target Healthy도 유지됐다. 원본과 검증 Snapshot은 유지한다. 확인창의 자동 재시작 예정은 10/23 18:12 KST였다. 중지 중에도 Storage·Backup 비용이 남고 연속 7일 뒤 자동 재시작하므로 이를 장기 무료 보관으로 취급하지 않는다. [RDS 중지와 자동 재시작](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_StopInstance.html)

이후 작성자는 원본 DB와 검증 Snapshot을 유지하고 임시 DB·해당 자동 Backup만 삭제하도록 선택을 변경했다. 삭제 직전 지정 이름·최종 Snapshot 미생성·자동 Backup 미보존을 다시 제시하고 최종 승인을 받은 뒤 실행했다. 삭제 중을 거쳐 임시 DB가 목록에서 사라지고 사용 가능한 원본 DB 한 개만 남은 것을 확인했다. 검증 Snapshot 상세도 새로 열어 manual·사용 가능을 확인했다. 자동 Backup 미보존은 삭제 요청의 선택값으로 확인했으며 별도 보존 Backup 목록 조회는 하지 않았다. 원본 App·RDS·Snapshot·DNS·Secret·IAM은 변경하지 않았다. [RDS 삭제 시 Backup 처리](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_DeleteInstance.html)

복원용 실행 명세 :2는 삭제한 임시 Endpoint를 참조하는 실험 기록이다. 현재 원본 App의 :1과 혼동해 다시 실행하지 않는다. 삭제 후 Browser의 보호된 조회는 로그인 필요로 처리됐으므로 이후 교체 실험의 인증 기준은 새 AGENT 로그인으로 확보한다.

## 실행 순서와 성공 기준

1. 승인한 Policy를 검증·생성·연결한 뒤 비Root·MFA·서울로 돌아온다. 실제 두 이름의 충돌 여부와 원본 상태·태그·Key·Private 설정을 확인한다.
2. 원본의 쓰기를 멈춘 기준 시점을 잡는다. AI는 계속 끄고 검증 중 새 문의를 접수하지 않는다. 같은 Read-only Transaction에서 Ticket·Message·Job·Attempt·Suggestion·Category·Flyway 이력을 확인한다. 원문·작성자·상태·관계의 일치 여부와 건수만 비교하며 Credential·원문 전체를 출력하지 않는다.
3. 지정 Snapshot 한 개를 만들고 Available·Source·생성 시점·Project 태그·암호화·공유 대상 부재를 확인한다. Single-AZ Snapshot 생성은 잠시 I/O를 멈출 수 있으므로 그 영향과 App 상태를 구분한다. [Snapshot 생성](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_CreateSnapshot.html)
4. 지정 복원 DB 한 개를 만든다. Private Subnet·RDS Group·default.postgres17·default:postgres-17·TLS·gp3 20 GiB·자동 증설 비활성·유료 추가 기능 비활성을 명시한다. Source 엔진·암호화를 유지하고 Available 뒤 실제 저장 설정과 새 Endpoint를 확인한다. 기본 선택을 원본 보안 설정의 자동 복원으로 가정하지 않는다. [RDS 복원](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_RestoreFromSnapshot.html)
5. 기존 Private CloudShell 통로에서 새 Endpoint에 verify-full로 접속한다. App Password는 작성자가 직접 입력한다. 원본과 같은 Row·본문/작성자 일치·FK 관계·Job 상태·Attempt/예약 수·Suggestion/Category·Migration을 비교한다. Available만으로 Row 보존을 완료 처리하지 않는다.
6. App 조회는 동시 Task 두 개를 띄우지 않고 순차 교체로 확인한다. 원본 App에서 인증된 조회 기준을 확보한 뒤 Target 해제·Draining·종료한다. 같은 Image와 여섯 Parameter 참조의 새 명세에서 JDBC Endpoint만 복원 DB로 맞추고 AI는 계속 끈다. 새 IP:8080 등록·Healthy → 이전 Session의 조회 거부 → AGENT 재로그인·기존 문의와 Job 조회를 확인한다. 같은 Image이므로 새 Source의 Migration V8을 적용하지 않는다.
7. 검증 Task를 종료하고 원본 명세·원본 RDS를 사용하는 새 Task 한 개로 서비스를 돌려놓는다. Target Healthy·재로그인·기존 문의를 재확인한다. DNS·Secret·원본 Schema를 바꾸지 않는다. 단독 Task 순차 교체이므로 잠깐의 중단과 Session 소실이 발생한다.
8. Snapshot·원본 보존과 검증 결과, 복원 DB에 새 업무 데이터가 없음을 확인한다. 삭제 직전 작성자에게 정확한 복원 DB 한 개의 삭제를 확인받아 정리한다. 삭제 승인이 없으면 해당 DB만 중지하고 Storage·자동 재시작 비용이 남는다고 알린다. Snapshot은 삭제·공개 공유하지 않는다. 모든 정리를 완료했다고 확인하기 전 비용 0원으로 쓰지 않는다.

App의 접속 대상만 바꿔도 Snapshot 시점의 DB 계정·Password와 현재 Parameter 값이 일치해야 한다. 오류가 나면 값을 읽어 출력하거나 원본 Credential을 임의 변경하지 않고 작성자의 직접 입력과 인증 결과로 확인한다. 검사용 Worker를 켜거나 Job/예약을 초기화하는 것은 이번 범위에 없다.

## 추가 비용과 대기 관리

2026-10-16 [AWS 서울 공개 요금 JSON](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonRDS/current/ap-northeast-2/index.json)에서 PostgreSQL Single-AZ db.t4g.micro 0.025 USD/시간, gp3 0.131 USD/GB·월을 확인했다. 복원 DB를 대기 포함 2시간 실행한다는 예시의 Instance 요금은 0.05 USD다. 20 GiB의 Storage는 한 달 전체 유지 시 2.62 USD이며 실제 사용 기간에 따른 청구를 따로 본다.

2시간은 예상 작업량이지 완료 보장이나 강제 과금 상한이 아니다. Snapshot은 할당 20 GiB를 곧바로 실제 유료 Backup 20 GiB로 계산하지 않고 실제 보관량과 계정의 Backup 무료 범위를 확인한다. Snapshot·추가 Backup·CPU Credit·전송·세금·환율, 기존 원본 RDS·App·ALB·Log 비용은 위 0.05 USD에 포함되지 않는다. 월 3만 원은 사용 목표가 아닌 전체 상한이다.

복원 대기 중 App Task를 추가로 실행하지 않는다. 불필요한 재시도나 두 번째 복원 DB를 만들지 않으며, 예상보다 지연되면 현재 한 개의 상태부터 확인한다. 임시 DB를 정리하고도 보존한 Snapshot의 요금은 남을 수 있다. 비용 알림을 실시간 강제 상한으로 취급하지 않는다.

## 설계 검토 기록

- 선택지: 기존 Runtime을 계속 확장하거나, 복원 전용 Policy를 분리한다. 후자를 초안으로 선택해 기존 권한·Version을 보존한다. 단순히 Console 경고를 숨기기 위한 Global Cluster·Aurora 조회는 추가하지 않는다.
- 정리: Stop만 허용하거나 지정 복원 DB 삭제도 허용한다. 최소 비용을 위해 후자를 승인안에 포함하되 실제 삭제는 보존 검증과 별도 최종 확인 뒤 수행한다. 원본·Snapshot 삭제는 제외한다.
- 실제 보존 선택: 검증·원본 복귀 완료 뒤 작성자가 삭제 대신 임시 DB 중지를 선택했다. 삭제 요청은 보내지 않았으며 Storage·Backup 비용과 7일 뒤 자동 재시작을 기록한다. IAM의 삭제 가능 범위가 있다는 사실을 삭제 승인으로 대신하지 않는다.
- 이후 정리 선택: 원본 DB와 검증 Snapshot으로 재복원이 가능하고 임시 DB의 새 업무 쓰기가 없음을 확인했다. 중지 보관과 지정 임시 DB 삭제 중 작성자가 후자를 별도 승인했으며, 최종 화면 승인 뒤 추가 Snapshot·자동 Backup 보존 없이 삭제했다. 중복 Storage와 자동 재시작 대상을 남기지 않되 원본과 수동 Snapshot은 유지한다. 영향은 이 안내와 WIL 보고서·계획·노트이며 운영 코드·IAM은 바꾸지 않는다. 후속 검토는 보존한 Snapshot의 필요 기간과 전체 비용이다.
- 실행 객체: 두 App을 동시에 띄우거나 순차 교체한다. 기존 단일 실행 경계를 유지하는 순차 교체를 선택하며 중단·재로그인 필요를 사전 고지한다.
- V2 조건 조정: CloudTrail 한 건만으로 정확한 불일치 조건을 알 수 없어, 전체 조건 제거·Root 복원 대신 별도 승인한 세 속성만 조정하는 안을 선택했다. 지정 Snapshot·Subnet과 나머지 비용/보안 조건을 유지하며 실제 비Root 요청으로 검증한다. 영향 파일은 Policy Template·정책 Test·이 안내와 WIL 실행 보고서다. 재거부 시 추가 변경은 다시 검토·승인한다.
- 영향: 새 복원 Policy Template·구조 Test·이 안내, WIL의 배포 계획·학습노트. 운영 Java 코드와 기존 IAM Policy·Secret·DNS·DB Schema는 수정하지 않는다.
- 후속 검토: 실제 IAM Validation·허용 결과, Snapshot 비공개/암호화·복원 Row·App 조회·원본 복귀·임시 DB 정리와 실제 비용. 이 문서나 로컬 Test 통과를 실행 승인으로 대신하지 않는다.
