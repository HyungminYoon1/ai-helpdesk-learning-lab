# 학습용 배포 사용자 권한

> 상태: Runtime Version 4와 생성 조건 유지. 조회 보조 정책 Version 2·Private TLS·App DB 계정과 Migration 7개를 확인했다. Edge Version 4의 학습 VPC 조회 적용 뒤 ALB·새 App Task·Cloudflare 서비스 DNS를 연결했다. Target Healthy·HTTPS Health 200/UP·로그인 페이지 200·익명 API 401 확인. 실제 로그인·Session·Role·CSRF·접수/DB Row 검증은 남아 있다.

`helpdesk-deployer`는 사람이 Console에서 배포할 때 사용하는 IAM 사용자다. ECS Agent의 `helpdesk-ecs-execution`, GitHub Image 업로드의 `helpdesk-github-ecr-push`, Helpdesk의 USER·AGENT 로그인 계정과 구분한다. 기존 Role의 정책은 바꾸지 않는다.

이 계정에서는 배포용 비Root 접근이 없어 Console 사용자 한 명을 준비한다. IAM Identity Center나 외부 신원을 사용하는 임시 인증이 AWS의 우선 권고지만, 이번 단계에서 Organizations·새 외부 신원 서비스를 추가하지 않는다. Console Password는 작성자가 직접 정하고 MFA를 등록한다. Access Key는 만들지 않는다. [AWS Root 사용 지침](https://docs.aws.amazon.com/IAM/latest/UserGuide/root-user-best-practices.html)

## 기본 세 정책과 보조 정책

| 정책 이름 | Template | 주요 범위 |
|---|---|---|
| `helpdesk-deployer-foundation` | [Foundation](./deployer-foundation-policy.template.json) | MFA·서울 제한, 학습 Network, 지정 Execution Role 전달, 필요한 서비스 연결 Role 생성 |
| `helpdesk-deployer-runtime` | [Runtime](./deployer-runtime-policy.template.json) | 학습 Cluster·Service·Task, 첫 Private PostgreSQL과 DB Subnet Group |
| `helpdesk-deployer-edge` | [Edge](./deployer-edge-policy.template.json) | 학습 ALB·Target Group·Listener·ACM, 학습 VPC 한 개의 보안 그룹 조회, 지정 ECR와 Log 조회 |
| `helpdesk-deployer-rds-console-read` | [RDS Console 조회](./deployer-rds-console-read-policy.template.json) | MFA·서울 조건의 세 메타데이터 조회와 지정 DB 한 개의 태그 조회 |

IAM 사용자 인라인 정책 대신 Network·실행·접속 설정을 세 Customer-managed Policy로 나눈다. [Private DB 관리 통로](./db-bootstrap.md)와 RDS Console 조회는 각각 보조 정책으로 분리한다. Managed Policy 하나의 공백 제외 6,144자 한도와 권한의 경계를 함께 유지하며, 서비스 전체 관리형 정책은 연결하지 않는다. 현재 지정 사용자에는 이 다섯 정책이 직접 연결되어 있다.

공개 Template의 Account ID는 자리표시자다. Edge의 `<LEARNING_VPC_ID>`도 확인한 학습 VPC 한 개의 ID로 채운다. 실제 계정의 적용본은 Git 제외 경로에서만 관리하며 Password·MFA Seed·Token·환경 변수 값은 포함하지 않는다. 이 JSON은 AWS 리소스를 생성하거나 정책을 자동 연결하는 스크립트가 아니다.

## 허용 범위

Region은 서울 `ap-northeast-2`로 제한한다. 글로벌 IAM의 본인 MFA·Password 관리, 지정 Role 확인·전달과 필요한 서비스 연결 Role 생성은 별도로 허용한다. 사용자에게 Password 변경을 허용하지만 MFA 없이 변경하도록 예외를 두지는 않는다.

새 Network에는 `Project=helpdesk-learning` Tag를 요구한다. 기존 다른 VPC에 Subnet·Route Table·Security Group을 추가하지 못하도록 별도의 거부 규칙을 둔다. Network 설정 변경도 같은 Project Tag를 가진 대상만 허용하며, 기존 리소스를 임의로 학습 대상으로 다시 Tag하는 작업은 허용하지 않는다. 같은 이름·Tag의 기존 대상이 있으면 소유·용도를 확인하기 전 사용하거나 변경하지 않는다.

첫 ECS 실행의 이름은 Cluster·Service·Task Definition Family 모두 `helpdesk-learning`이다. 등록·실행 사양은 로컬에서 확인한 `256 CPU Units·512 MiB`로 제한하고 Fargate를 명시한다. ECS Exec·새 EBS Volume·Service Connect·VPC Lattice를 추가하지 않는다. 전체 AI의 측정 결과에 따라 더 큰 사양이 필요하면 권한과 비용을 함께 다시 확인한다.

RDS Instance와 DB Subnet Group의 이름도 `helpdesk-learning`이다. 첫 생성은 PostgreSQL·`db.t4g.micro`·Single-AZ·20 GiB·Storage 암호화·Public Access 비활성으로 제한한다. PostgreSQL 17의 기본 Parameter·Option Group을 사용한다. App용 DB 계정과 관리자 Password는 별개이며, Password 자동 관리용 Secrets Manager는 추가하지 않는다. Stop·Start는 허용하지만 삭제·Snapshot·복원·임의 Instance 수정은 이번 정책에 넣지 않는다.

ALB와 Target Group은 `helpdesk-learning` 이름으로 생성하고 Tag를 함께 지정한다. App의 Inbound는 ALB에서만, DB의 Inbound는 App에서만 허용한다. ACM은 `helpdesk.hmyoon.com`의 Public·DNS 검증·비수출 RSA 2048 인증서만 요청한다. 기존 Cloudflare Record와 Nameserver는 변경하지 않고 필요한 두 CNAME을 구분한다.

목록과 일부 Describe API는 Resource 단위 제한을 지원하지 않거나 Console 탐색에 필요해 서울 Region 전체를 조회할 수 있다. 따라서 다른 프로젝트의 이름·주소·명세 메타데이터도 조회 가능한 권한이 포함된다. 다른 프로젝트의 Task Definition·환경 설정·Log는 열지 않는다. 변경 권한의 학습 대상 제한과 목록 조회의 범위를 같은 의미로 표현하지 않는다.

### DB Subnet Group의 태그 검사

RDS Console의 Subnet Group 생성 폼에는 태그 입력란이 없어 생성 요청의 `aws:RequestTag/Project`를 요구하면 거부된다. 2026-10-14에 이름이 `helpdesk-learning`인 Group 한 개의 생성만 허용하고, 생성 뒤 `Project=helpdesk-learning`을 등록하는 변경을 승인받았다.

태그 검사를 없애는 대신 DB 생성에서 이 Group을 사용할 때 `rds:subgrp-tag/Project`를 검사한다. DB 자체의 생성 요청에도 같은 Project 태그를 요구한다. 해당 Group의 `ListTagsForResource`만 추가하고, 기존의 한정된 `AddTagsToResource`를 유지한다. 기본 `default.postgres17`을 사용하므로 전용 Parameter Group 생성·수정 권한은 추가하지 않는다. [AWS RDS 권한 정의](https://servicereference.us-east-1.amazonaws.com/v1/rds/rds.json)

생성 시 태그를 요구하는 기존안, 서비스 전체 권한을 주는 안, 생성 후 태그를 확인하고 사용 단계에서 검사하는 안을 비교해 마지막 안을 선택했다. Console과 맞는 생성 흐름을 확보하면서 정확한 Resource 이름과 태그 검사를 함께 유지하기 위해서다. 변경 파일은 Runtime Template과 정책 구조 Test이며, AWS 적용 후 정책 JSON·Group의 VPC·Private Subnet 두 개·태그를 다시 확인한다. 정책 구조 Test 통과와 AWS의 실제 허용 결과는 구분한다.

2026-10-14에 Runtime 정책의 기본 Version 2를 적용하고 기존 Version 1을 유지했다. 저장된 전체 JSON은 실제 계정 번호를 반영한 Template과 일치하며, 공백 제외 6,119자다. 동일 대상을 사용하는 생성·조회 문장을 합치고 RDS 문장 식별자를 줄여 6,144자 한도를 맞췄으며 권한 조건은 유지했다. 배포 사용자로 Group 한 개를 생성하고 기존 학습 VPC·Private Subnet 두 개·`Project=helpdesk-learning` 태그를 재조회했다. DB 생성 시 태그 조건의 실제 평가는 후속 RDS 요청에서 확인한다.

뒤이어 연 RDS 전체 구성 화면에서는 연결 설정 영역에 리소스 조회 오류가 나타났고, Browser 진단에서 `outposts:ListOutposts`의 허용 부재를 확인했다. 2026-10-14에 이 조회 한 개의 추가 범위를 별도로 승인받았다. Runtime의 메타데이터 조회 문장에 Action 한 개만 추가하며 Foundation의 직접 호출 MFA·서울 거부 조건은 유지한다. 개별 Resource 제한을 지원하지 않아 서울의 다른 프로젝트 Outposts 메타데이터도 조회할 수 있지만, Outposts 생성·변경·삭제나 Secret 조회는 허용하지 않는다. [AWS Outposts 권한 정의](https://servicereference.us-east-1.amazonaws.com/v1/outposts/outposts.json)

현 정책 유지·서비스 전체 권한 추가·목록 조회 한 개 추가를 비교해 마지막 안을 선택했다. RDS 연결 설정 화면의 조회 실패를 해결하기 위한 최소 변경이며, 적용 후 화면이 정상 표시되는지는 별도로 확인한다. Runtime Template·구조 Test·이 안내에 반영했다.

Runtime의 기본 Version 3에 적용했고 기존 Version 1·2는 보존했다. 전체 저장 JSON은 조회 한 개만 추가한 Template과 일치하며, 15개 문장·공백 제외 6,143자다. AWS 편집기의 보안·오류·경고·추천은 모두 0이었다. 지정 사용자에 기존 네 정책이 직접 연결된 것을 재확인했고, Foundation의 전체 저장 JSON도 Template과 일치했다. 관련 구조 Test 32개·JavaScript 전체 205개·ESLint가 통과했다.

Root 로그아웃과 배포 사용자 재로그인 뒤 전체 생성 화면을 다시 열었다. 초기 Aurora 화면에서는 연결·기존 학습 VPC·Subnet Group 영역이 표시됐지만, 일반 PostgreSQL로 전환하면 연결 영역부터 리소스 로드 오류가 발생했다. 비밀번호를 입력하지 않은 상태에서 한 번 새로고침한 뒤에도 재현됐다. 새 진단에서는 구체적인 거부 Action을 확인하지 못했으므로, 기존 Outposts 조회 거부가 모든 화면 오류의 원인이라고 확정하거나 다른 권한을 추측해 추가하지 않는다.

일반 PostgreSQL `17.11-R1`·`db.t4g.micro`·Single-AZ·gp3 20 GiB·자체 Credential 관리까지만 폼에 준비했다. 20 GiB 승인 범위를 넘는 자동 Storage 증설은 비활성으로 선택했다. 연결·암호화·태그·Monitoring·백업·요금 확인을 마치지 못한 상태에서 작성자가 전달한 생성 응답은 `rds:CreateDBInstance`의 HTTP `403`·신원 정책의 Allow 미매칭이었다.

Action이 정책에 없는 경우와 Resource·Condition이 맞지 않는 경우를 구분해야 한다. 화면의 이름·사양은 확인했지만 실제 생성 요청의 `StorageEncrypted`·`PubliclyAccessible`·Project 태그는 미확인이다. 태그 누락 등 특정 조건을 원인으로 확정하지 않고, 비밀값 없는 요청 필드와 저장된 정책을 대조한다. [AWS 조건 평가](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_condition-logic-multiple-context-keys-or-values.html)

직접 페이로드 조회를 위한 화면 제어는 현재 URL 식별 문제로 중단됐다. 전체 요청 Body·HAR·Credential은 기록하지 않았고 추가 권한 변경·생성 재시도도 하지 않았다. 10/13 학습 회차를 마감해 원인 확인·RDS·TLS·DB 연결을 10/14에 이어간다. 생성 요청의 거부와 DB 생성·연결 성공을 구분하며, 재개 때 실제 리소스와 서비스 연결 Role 상태도 확인한다.

### CloudTrail로 확인한 추가 조회 거부

10/14 회차에는 CloudTrail에서 `rds:DescribeDBClusters`, `ds:DescribeDirectories`, `kms:ListAliases`의 Allow 부재를 확인했다. 앞 회차의 `CreateDBInstance`는 서울·MFA 인증이 확인됐지만 `requestParameters`가 `null`이었다. 그 기록만으로 실제 요청의 Project 태그·암호화·Public Access 설정을 판단하거나 특정 조건을 거부 원인으로 확정할 수는 없다.

현 정책 유지·서비스 전체 조회·확인된 세 조회 추가를 비교해 마지막 안의 적용을 승인받았다. 기존 Runtime 메타데이터 문장의 `Resource: "*"`에 세 Action만 추가했고, Foundation의 직접 호출 MFA·서울 제한과 기존 DB 생성 조건을 유지했다. 서울의 다른 프로젝트 클러스터·디렉터리·키 별칭 메타데이터도 조회 가능하지만 Secret 값 조회·KMS 복호화·디렉터리 변경은 추가하지 않는다. 디렉터리 조회는 Helpdesk 인증 방식의 변경이 아니다. [AWS Directory Service 권한 정의](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ds.html), [AWS KMS 별칭 조회 권한](https://docs.aws.amazon.com/kms/latest/developerguide/alias-access.html)

권한 크기를 맞추기 위해 `Sid` 여섯 개만 짧게 바꿨다. 기존 Action·Resource·Condition을 대조했으며, 적용본은 15개 문장·공백 제외 6,130자다. 변경 전 저장 JSON은 Version 3 Template과 일치했다. 승인한 기본 Version 4로 저장한 뒤에도 전체 정책 내용을 수정안과 대조했고, Version 1~3 보존과 지정 사용자 한 명의 연결을 확인했다. AWS 편집기의 보안·오류·경고·추천은 모두 0, 관련 구조 Test 34개·JavaScript 전체 207개·ESLint는 통과했다.

영향 파일은 Runtime Template·정책 구조 Test·이 안내다. Foundation·Edge·Bootstrap·기존 Role·운영 Java·JAR·Image는 바꾸지 않았다. 적용 후 Root에서 로그아웃했으며, 다음은 배포 사용자로 생성 화면을 확인하고 실제 생성 설정을 대조하는 것이다. 세 조회 권한을 추가한 것을 이전 DB 생성 거부까지 해결한 것으로 표현하지 않는다.

### RDS 조회와 생성 조건의 종합 점검

Version 4 적용 뒤 생성 화면에서 실패한 네 조회와 기존 정책을 함께 대조했다. Foundation·Runtime·Edge의 저장 JSON은 적용 명세와 일치했고, Bootstrap에는 RDS 생성 권한이 없었다. 지정 사용자에게 권한 경계가 없다는 점과 기존 네 정책의 연결을 확인했다. DB Subnet Group에는 기존 학습 VPC의 Private Subnet 두 개와 `Project=helpdesk-learning` 태그가 있었다. 점검 시 서울의 DB Instance·Cluster는 모두 0개였다. DB 생성 요청의 Resource·Condition이 실제로 일치하는지는 별도 요청으로 확인한다.

Runtime에 다시 조회를 추가하는 안, 넓은 RDS 조회 정책을 연결하는 안, 작은 보조 정책으로 분리하는 안을 비교해 마지막 안을 승인받았다. Runtime은 이미 공백 제외 6,130자여서 기존 조건과 Version을 유지한 채 조회 권한을 분리했다. 영향 파일은 새 조회 Template·정책 구조 Test·이 안내이며 운영 Java·JAR·Image는 변경하지 않았다.

처음에는 CloudTrail에서 거부된 네 이름을 모두 초안에 넣었다. 그러나 AWS 편집기는 `rds:DescribeServerlessV2PlatformVersions`를 `Invalid Action`으로 거부했다. 공식 서비스 참조에서도 이 API는 `Operations`에 있지만 같은 이름의 IAM `Actions`에는 없었으며, 별도 권한 매핑은 확인되지 않았다. API의 존재와 같은 이름의 IAM 권한 지원을 동일하게 가정하면 안 된다. 이 API는 Aurora Serverless v2용 조회이므로 이번 일반 PostgreSQL 구성에서는 제외했고, 다른 권한으로 대체하거나 와일드카드를 추가하지 않았다. [RDS 서비스 참조](https://servicereference.us-east-1.amazonaws.com/v1/rds/rds.json), [해당 API의 대상](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_DescribeServerlessV2PlatformVersions.html)

보조 정책은 `rds:DescribeCertificates`, `rds:DescribeDBSecurityGroups`, `rds:DescribeDBClusterParameterGroups` 세 조회만 `Resource: "*"`에 허용한다. 각 허용에 MFA `true`와 서울 Region 조건을 명시한다. 서울의 다른 프로젝트 인증서·DB Security Group·Cluster Parameter Group 메타데이터도 조회할 수 있으나 DB Row·Secret 조회나 리소스 변경은 허용하지 않는다. PostgreSQL·Single-AZ·20 GiB·암호화·비공개·Project 태그 등 기존 생성 조건은 그대로다.

18:40에 `helpdesk-deployer-rds-console-read` Version 1을 생성하고 `helpdesk-deployer` 한 명에게만 연결했다. 저장 JSON 전체는 세 조회 Template과 일치하며 공백 제외 326자다. AWS 편집기의 보안·오류·경고·추천은 모두 0, 집중 정책 구조 Test 23개가 통과했다. 기존 네 정책도 계속 직접 연결되어 있다. 적용 뒤 Root를 로그아웃하고 비Root 로그인 화면을 준비했다. 후속 검토는 비Root 재로그인 후 일반 PostgreSQL 화면·실제 생성 설정·생성 요청의 결과다. 제외한 Serverless 조회 오류가 일반 PostgreSQL 생성에 영향을 주는지는 그 단계에서 확인한다.

배포 사용자로 재로그인한 뒤 일반 PostgreSQL의 연결·태그·암호화 영역이 표시됐다. PostgreSQL `17.11-R1`·`db.t4g.micro`·Single-AZ·gp3 20 GiB·비공개·암호화·학습 Group·Project 태그를 준비하고 기존 생성 조건과 대조했다. 기본 VPC Security Group은 제외해 `helpdesk-learning-rds`만 선택했다. 자동 증설·RDS Proxy·추가 모니터링·교차 Region 백업 복제는 껐고, 자동 백업의 7일 기본값은 유지했다. 초기 DB 이름은 App 연결에 사용할 `helpdesk`로 준비했다. 새 마스터 암호의 입력·확인·생성 제출은 작성자가 직접 수행하며 실제 생성 결과는 다음 확인 단계다. 화면에 남은 비용 조회 실패를 해결하려고 권한을 확대하지 않았다.

작성자가 마스터 암호를 직접 입력해 생성 요청을 제출했다. 비Root 목록에서 `helpdesk-learning` Instance 한 개의 생성 중 상태·PostgreSQL·`db.t4g.micro`·Single-AZ를 확인했다. 같은 화면의 `rds:DescribeGlobalClusters` 거부는 글로벌 목록 조회이며 이 생성 요청의 실패가 아니다. 해당 조회 권한을 추가하지 않고 지정 Instance 상세로 이어간다. 저장된 전체 설정·준비 완료·실제 TLS 연결은 계속 확인한다.

생성 시간은 19:10이며 이후 `사용 가능` 상태를 확인했다. 구성에서 PostgreSQL 17.11·DB `helpdesk`·`db.t4g.micro`·Single-AZ·gp3 20 GiB·기본 키 암호화·자동 증설 비활성·기본 Parameter/Option Group과 추가 모니터링 비활성을 확인했다. 연결 설정에는 학습 VPC·Private Subnet 두 개·전용 RDS Group·Public Access 비활성이 저장되어 있다. 자동 백업 7일·Snapshot 태그 복사·교차 Region 복제 없음도 확인했다. 준비 완료는 실제 Client의 TLS·DB 인증이나 App 연결 성공을 대신하지 않는다.

DB 태그 탭은 0개로 표시됐지만 진단에서는 지정 DB의 `rds:ListTagsForResource` 거부를 확인했다. 당시 이 Action은 Subnet Group 한 개에만 허용되어 있었으므로 0개 표시를 실제 태그 누락으로 확정하지 않았다.

지정 DB 한 개의 태그 조회 추가를 승인받아 보조 정책에 별도 문장을 넣었다. 대상은 서울의 `db:helpdesk-learning` ARN 한 개이며 MFA `true`·서울 조건을 명시한다. 기존 세 메타데이터 조회 문장과 Runtime·Foundation·Edge·Bootstrap은 유지한다. 현 상태 유지·전체 RDS 태그 조회·지정 DB만 조회하는 안 중 마지막 안을 선택했고, 글로벌 클러스터·Snapshot 조회나 Secret·변경 권한은 추가하지 않았다. 변경 파일은 보조 정책 Template·정책 구조 Test·이 안내다.

19:54에 기본 Version 2로 저장하고 Version 1을 보존했다. 저장된 전체 JSON은 승인한 수정안과 일치하며 공백 제외 598자·두 문장이다. AWS 편집기의 보안·오류·경고·추천은 모두 0이었다. 연결 대상은 기존 `helpdesk-deployer` 한 명이다. Root 로그아웃·배포 사용자 MFA 로그인 뒤 `Project=helpdesk-learning` 태그 한 개를 실제로 확인했다. 태그 변경이나 DB 생성 재요청은 하지 않았다. 관련 로컬 정책 구조 Test 36개·JavaScript 전체 209개도 통과했다.

이후 [DB Bootstrap 절차](./db-bootstrap.md)로 관리자/App 계정의 실제 TLS 접속과 한정된 권한을 확인했다. App 계정의 임시 Table 생성·INSERT·조회와 Rollback은 통과했고 데이터는 남기지 않았다. 이 SQL 점검은 ECS App의 Parameter 전달·Migration·Ticket 접수 성공과는 구분한다. Secret 값 조회·다른 DB 변경·인터넷 경로와 유료 통로는 추가하지 않았다.

## ACM 인증서 검색 조회

ACM 목록 화면은 `acm:ListCertificates`와 별도로 `acm:SearchCertificates`를 사용한다. 기존 Edge 정책에는 검색 Action이 없어, 인증서가 0개로 표시되는 화면에 검색 권한 거부가 함께 나타났다. 이 상태를 실제 인증서 없음으로 확정하지 않았다.

기존 권한 유지·넓은 ACM 조회 정책 연결·필요한 검색 한 개 추가를 비교하고, 승인받은 검색 Action 한 개만 별도 문장으로 추가했다. 이 Action은 Resource 수준의 제한을 지원하지 않으므로 `Resource: "*"`를 사용하되 MFA `true`와 서울 `ap-northeast-2` 조건을 명시했다. 서울의 다른 프로젝트 인증서 메타데이터도 검색 가능해지지만 Private CA·인증서 내보내기·Secret 조회 권한은 추가하지 않는다. 기존 인증서 생성의 도메인·DNS 검증·RSA_2048·내보내기 비활성 조건과 다른 정책은 유지한다. [ACM API 권한 매핑](https://docs.aws.amazon.com/acm/latest/userguide/authen-apipermissions.html), [ACM 서비스 권한 참조](https://docs.aws.amazon.com/service-authorization/latest/reference/list_acm.html)

영향 파일은 Edge Template·`iam-deployer-policy.test.mjs`·이 안내다. Test는 검색 문장에 Action 한 개와 MFA·서울 조건이 정확히 있는지, Private CA 권한이 추가되지 않았는지 검사한다. JavaScript 전체 210개·ESLint·`git diff --check`가 통과했다. Java Code·Image·CI는 이번 변경으로 다시 실행하지 않았다.

23:30 KST에 새 기본 Version 2로 저장하고 Version 1을 보존했다. 저장된 JSON 207줄·13개 문장 전체를 읽어 승인안과 정확히 일치함을 확인했으며, 연결 대상은 기존 `helpdesk-deployer` 한 명이다. Root 로그아웃 뒤 해당 사용자의 MFA 로그인으로 서울 ACM에서 `helpdesk.hmyoon.com` 검색을 실행했다. 검색 거부 없이 0개 결과를 확인했다. 인증서 발급·DNS Record 변경·ALB·Service·새 Task 실행은 이 검색 권한 적용에 포함하지 않는다. 다음 단계는 내보내기 불가능한 공개 인증서의 DNS 검증을 준비하고, 발급을 확인한 뒤 승인한 범위에서 ALB·App을 연결하는 것이다.

## ACM 발급의 Project 태그

`Project=helpdesk-learning` 태그를 포함한 공개 인증서 요청은 `acm:AddTagsToCertificate` 권한 부족으로 거부됐다. 인증서 생성 조건은 맞췄지만, 기존 정책에 태그를 포함하는 발급의 추가 권한이 빠져 있었다. ACM의 작업 매핑에는 `RequestCertificate`가 요청과 태그 두 Action을 사용한다고 명시되어 있다. [ACM 작업별 권한](https://docs.aws.amazon.com/service-authorization/latest/reference/list_acm.html)

한 시간 동안만 태그 권한을 허용하는 안과 조건을 유지한 상시 권한을 비교했다. 한 시간은 AWS의 필수 조건이나 인증서의 유효기간이 아니라, 임시 IAM 허용의 만료를 위한 제안이었다. 반복되는 배포 학습과 재생성 때마다 정책을 바꾸는 부담을 줄이기 위해 사용자가 기간 제한 없는 안을 승인했다.

추가 Action은 `acm:AddTagsToCertificate` 한 개이며 대상 ARN은 동일 계정·서울의 인증서다. MFA·서울 조건을 명시하고 요청 태그의 Key는 `Project` 한 개, Value는 `helpdesk-learning`으로 제한한다. 기존 Project 태그가 있으면 그 값도 `helpdesk-learning`이어야 한다. `StringEqualsIfExists`를 사용하므로 Project 태그 없는 인증서에는 이 태그를 붙일 수 있지만, 다른 Project 태그가 있는 인증서를 변경하는 허용은 주지 않는다. Date 조건은 추가하지 않는다. 태그가 없는 인증서를 학습용 조회 허용 대상으로 표시할 수 있다는 범위까지 승인에 포함했다.

기존 단일 도메인·DNS 검증·RSA_2048·내보내기 비활성의 생성 조건과 다른 정책은 유지한다. Private CA·Secret·태그 제거·인증서 내보내기 권한은 추가하지 않는다. 영향 파일은 Edge Template·정책 구조 Test·이 안내다. 관련 Test 26개·JavaScript 전체 211개·ESLint가 통과했다.

2026-10-15에 새 기본 Version 3으로 저장하고 Version 1·2를 보존했다. AWS 편집기의 보안·오류·경고·추천은 모두 0이었다. 저장된 JSON 235줄·14개 문장 전체가 승인한 적용본과 정확히 일치한다. 기간 조건 없이 태그 Action 한 개만 추가했다. Root 로그아웃 뒤 `helpdesk-deployer`의 MFA 로그인으로 공개 인증서 요청에 성공했으며, 단일 도메인·RSA 2048·DNS 검증·내보내기 비활성·Project 태그를 확인했다.

Cloudflare에 AWS가 지정한 검증 CNAME 한 개를 `DNS only`·TTL Auto로 등록했다. 저장된 이름과 대상이 AWS 안내와 일치하며 공개 DNS에서도 CNAME이 조회됐다. 기존 Record는 변경하지 않았다. 01:28:52 KST에 인증서의 `발급됨` 상태를 확인했다. ALB 연결과 실제 HTTPS 동작은 별도 검증 대상으로 남는다.

## ALB 생성 단계의 VPC 보안 그룹 조회

학습용 IP Target Group `helpdesk-learning`을 만들고 HTTP 8080·`/actuator/health`·성공 코드 200·Project 태그를 확인했다. App 실행 전이므로 등록된 Target은 0개다. ALB는 기존 Public Subnet 두 개·전용 ALB Group·HTTPS 443·발급된 ACM 인증서로 준비했으며, WAF·CloudFront·Global Accelerator 등 추가 유료 기능은 선택하지 않았다.

ALB 생성 단계는 `ec2:GetSecurityGroupsForVpc` 권한 부족으로 거부됐다. 저장된 생성 화면의 오류와 재조회한 ALB 목록 0개를 확인했다. 새 Task의 실행 폼은 준비했지만 제출하지 않았으며 기존 IAM·보안 그룹 규칙은 변경하지 않았다. 인증서·Target Group 생성 성공과 ALB 실행 실패를 구분한다.

이 Action은 지정 VPC에 사용할 수 있는 보안 그룹을 조회하며 VPC 단위 Resource 제한을 지원한다. 현 권한 유지·서울의 VPC 전체 조회·학습 VPC 한 개 조회를 비교해, 승인받은 마지막 안을 선택했다. 기존 Edge 정책에 `ec2:GetSecurityGroupsForVpc` 한 개만 추가하고 Resource는 확인한 학습 VPC의 ARN 하나로 제한한다. MFA·서울·`Project=helpdesk-learning` 조건을 명시하며, 다른 VPC의 조회·보안 그룹 규칙 변경·Secret 접근은 추가하지 않는다. [EC2 조회 API](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_GetSecurityGroupsForVpc.html), [EC2 권한·Resource 참조](https://docs.aws.amazon.com/service-authorization/latest/reference/list_ec2.html)

10/15 01:51 KST에 기본 Version 4로 저장하고 Version 1~3을 보존했다. 저장 JSON 252줄·15개 문장·공백 제외 5,164자는 승인한 적용본과 정확히 일치하고 기존 14개 문장은 바뀌지 않았다. AWS 편집기의 보안·오류·경고·추천은 모두 0이었다. 영향 파일은 Edge Template·정책 구조 Test·이 안내다. 정책 Test 27개·JavaScript 전체 212개·ESLint 10.11.0이 통과했다. 다른 IAM 정책·보안 그룹 규칙·Java Code·Image는 변경하지 않았다.

비Root MFA 로그인 뒤 02:05 KST에 ALB와 HTTPS 443 Listener가 생성됐다. Wizard의 추가 `AddListenerCertificates`는 거부됐고 자동 삭제도 실패했지만, 실제 ALB·Listener는 남아 있었다. 상세에서 기본 ACM 인증서·Target Group 전달 규칙을 확인했고, ALB의 활성 상태와 실제 도메인의 HTTPS 응답으로 연결을 검증했다. 기본 인증서가 이미 연결되어 있어 추가 인증서 권한·삭제 권한을 넓히거나 같은 ALB를 다시 만들지 않았다.

기존 명세의 App Task 한 개를 실행해 Private IP의 8080을 Target Group에 등록했고 Healthy를 확인했다. Cloudflare에는 기존 검증 CNAME을 유지한 채 서비스 CNAME 한 개를 DNS-only로 추가했다. HTTPS Health는 200/UP, 로그인 페이지는 200, 익명 Ticket 조회는 401이었다. 실제 로그인·Session·Role·CSRF·접수와 Database Row 대조는 다음 검증이다. App·ALB·RDS는 실행 중이며 과금도 계속된다. 종료·삭제는 별도 승인 범위를 확인한다.

## Secret과 배포 권한

사용자에게 `ssm:GetParameter(s)`·경로별 Secret 조회·Secrets Manager 값 조회·KMS 복호화·ECR Push 권한을 주지 않는다. ECR은 기존 Repository의 Image 메타데이터를, Log는 `/ecs/helpdesk-learning`의 Event를 읽는 범위다. Log 출력 전에 비밀값 노출 여부를 확인한다.

`iam:PassRole`은 `helpdesk-ecs-execution` 한 Role을 ECS Tasks에 전달하는 경우만 허용한다. 다만 이 사용자는 그 Role로 Secret을 전달받는 Task를 배포할 수 있으므로, Image·명세 변경까지 신뢰해야 하는 배포 신원이다. 직접 Secret 조회 API를 막는 것과, 배포자가 실행 Code를 통해 Secret에 접근할 가능성을 분리한다. 승인한 Digest·명세를 유지하고 Secret 값을 출력하지 않는다.

## 서비스 연결 Role

초기 준비 시점의 목록에는 다음 세 Role이 없었다. 첫 서비스 생성 과정에 필요한 Role이며, 이후 생성 요청을 시도했으므로 재개 때 실제 존재 여부를 다시 확인한다.

| 서비스 | 허용하는 Role |
|---|---|
| ECS | `AWSServiceRoleForECS` |
| RDS | `AWSServiceRoleForRDS` |
| ELB | `AWSServiceRoleForElasticLoadBalancing` |

`iam:CreateServiceLinkedRole`을 해당 세 ARN·Service Name에만 허용한다. AWS 서비스가 자신의 인프라 작업을 수행하기 위한 Role이며 사람이 맡는 Administrator Role이 아니다. 임의 Role 생성·기존 Role 정책 수정·사용자 권한 연결은 배포 사용자에게 허용하지 않는다. 실제 첫 생성 때 추가되는 Role도 승인 범위에 포함한다.

## 적용과 확인

1. AWS Console에서 정책 문법·지원 Action·Condition 경고를 검토한다. 실제 Account ID로 채운 적용본도 공백 제외 6,144자 안인지 확인한다.
2. 적용할 사용자 한 명·세 정책·세 서비스 연결 Role의 생성 허용 범위를 보여주고 확인한다. 기존 정책·사용자가 있으면 덮어쓰지 않는다.
3. 사용자 생성과 정책 연결 뒤 Password·MFA는 작성자가 직접 등록한다. 자동 Password 생성·Credential 다운로드·Access Key 생성은 사용하지 않는다. MFA 등록을 마친 뒤 재로그인한다.
4. 연결 정책이 이 세 개뿐인지, Access Key가 없는지와 MFA 설정 여부를 확인한다. MFA 없는 배포 거부, 다른 Region·다른 Project 변경 거부, 지정 대상의 배포 허용은 실제 IAM 평가와 실행으로 검증한다.
5. 예상하지 못한 `AccessDenied`는 실패 작업·대상·조건부터 확인한다. 이를 해결하려고 `AdministratorAccess`나 서비스 전체 권한을 연결하지 않는다. 현재 승인 밖의 권한이 필요하면 별도로 확인한다.

첫 Password는 본인이 직접 정하므로 다른 사람에게 임시 Password를 발급하는 흐름을 사용하지 않는다. MFA 없는 첫 로그인에 강제 Password 변경을 요구하면 현재 거부 정책과 충돌하므로, 본인이 정한 Password와 MFA를 준비한 뒤 배포용 로그인을 시작한다. Password·MFA를 변경하는 최종 단계는 작성자가 수행한다.

### 적용 결과

2026-10-13에 지정 사용자 한 명을 생성하고 위 세 정책만 직접 연결했다. AWS에 저장된 각 정책의 전체 JSON을 다시 읽어 실제 Account ID를 반영한 Template과 일치하는지 확인했다. 기존 Role·다른 사용자 권한은 수정하지 않았다.

본인이 Password와 MFA를 등록했다. Console에서 Password 활성화·MFA 1개·Access Key 0개를 확인한 뒤 Root를 로그아웃하고 `helpdesk-deployer`로 로그인했다. 서울 Region에서 VPC 목록 조회를 확인했으며, 지정 대상의 변경 허용·MFA 없는 배포 거부·다른 Region과 Project 변경 거부는 별도로 검증한다.

VPC Console의 `ec2:DescribeNetworkAcls` 조회는 Allow가 없어 거부됐다. 이 오류를 해결하려고 권한을 확대하지 않았으며 VPC 목록·생성 폼 확인과 구분했다. 이 초기 적용 단계에서는 서비스 연결 Role 세 개의 생성 권한만 허용했고 실제 생성은 하지 않았다. 이후의 RDS 요청 거부와 재개 시점의 Role 존재 여부는 별도로 확인한다. App·RDS·ALB 실행과 DNS·HTTPS 검증은 이 IAM 적용 결과에 포함하지 않는다.

## 로컬 구조 검사

Private RDS의 최초 계정 설정에는 [별도의 임시 관리 통로](./db-bootstrap.md)를 사용한다. 기존 세 정책은 유지하고, CloudShell의 환경 생성·지정 환경 사용과 한정된 정리 권한을 Bootstrap 정책 하나로 분리한다. DB 관리자 Credential을 App에 전달하거나 배포 사용자에게 Secret 조회·자기 권한 수정 권한을 주지 않는다.

```powershell
node --test src/test/js/iam-deployer-policy.test.mjs
```

JSON·정책 Size·MFA/Region 조건, Role·Tag·첫 실행 사양과 금지 Action을 검사한다. 이 Test는 AWS의 실제 IAM 평가나 Console 적용·Cloud 연결을 대신하지 않는다.

Action·Resource·Condition은 [AWS Service Authorization의 JSON 정보](https://docs.aws.amazon.com/service-authorization/latest/reference/service-reference.html)와 [MFA 자기 관리 예제](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_examples_aws_my-sec-creds-self-manage.html)를 확인했다. 공식 MFA 예제의 Access Key·다른 Credential 관리 권한은 가져오지 않았다.
