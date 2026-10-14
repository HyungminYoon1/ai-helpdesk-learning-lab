# 학습용 배포 사용자 권한

> 상태: 사용자·정책 연결과 비Root 로그인 완료. 2026-10-14 Runtime Version 4 적용, DB Subnet Group 생성·태그 등록·조회 확인. CloudTrail에서 세 추가 조회 거부를 확인해 한정 허용했고, 비Root 생성 화면·생성 조건·연결 검증은 대기.

`helpdesk-deployer`는 사람이 Console에서 배포할 때 사용하는 IAM 사용자다. ECS Agent의 `helpdesk-ecs-execution`, GitHub Image 업로드의 `helpdesk-github-ecr-push`, Helpdesk의 USER·AGENT 로그인 계정과 구분한다. 기존 Role의 정책은 바꾸지 않는다.

이 계정에서는 배포용 비Root 접근이 없어 Console 사용자 한 명을 준비한다. IAM Identity Center나 외부 신원을 사용하는 임시 인증이 AWS의 우선 권고지만, 이번 단계에서 Organizations·새 외부 신원 서비스를 추가하지 않는다. Console Password는 작성자가 직접 정하고 MFA를 등록한다. Access Key는 만들지 않는다. [AWS Root 사용 지침](https://docs.aws.amazon.com/IAM/latest/UserGuide/root-user-best-practices.html)

## 적용할 세 정책

| 정책 이름 | Template | 주요 범위 |
|---|---|---|
| `helpdesk-deployer-foundation` | [Foundation](./deployer-foundation-policy.template.json) | MFA·서울 제한, 학습 Network, 지정 Execution Role 전달, 필요한 서비스 연결 Role 생성 |
| `helpdesk-deployer-runtime` | [Runtime](./deployer-runtime-policy.template.json) | 학습 Cluster·Service·Task, 첫 Private PostgreSQL과 DB Subnet Group |
| `helpdesk-deployer-edge` | [Edge](./deployer-edge-policy.template.json) | 학습 ALB·Target Group·Listener·ACM, 지정 ECR와 Log 조회 |

IAM 사용자 인라인 정책 대신 세 Customer-managed Policy로 나눈다. Managed Policy 하나의 공백 제외 6,144자 한도와 Network·실행·접속 설정의 경계를 함께 유지한다. 더 넓은 관리형 정책은 연결하지 않는다.

공개 Template의 Account ID는 자리표시자다. 실제 계정의 적용본은 Git 제외 경로에서만 관리하며 Password·MFA Seed·Token·환경 변수 값은 포함하지 않는다. 이 JSON은 AWS 리소스를 생성하거나 정책을 자동 연결하는 스크립트가 아니다.

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
