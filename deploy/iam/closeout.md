# 배포 실습 종료를 위한 최소 권한

> 2026-10-22: 정리 권한 준비 뒤 별도의 최종 승인을 받아 지정 원본 RDS·ALB/리스너·실습 Zone/두 Record·Cloudflare 서비스 CNAME을 삭제했다. App Task·Service는 0개이며 두 수동 Snapshot은 개별 상세에서 Available을 확인했다. Secret·Log·Image·ACM/검증 CNAME·Target Group·네트워크는 보존했다. 아래 권한 준비와 실제 실행 결과를 구분한다.

## 대상과 보존 범위

| 대상 | 새 권한 | 제한 |
|---|---|---|
| 원본 RDS `helpdesk-learning` | `rds:DeleteDBInstance` | 같은 계정의 정확한 DB ARN, Project 태그, MFA, 서울 |
| 기존 ALB `helpdesk-learning` | `elasticloadbalancing:DeleteLoadBalancer` | 이름과 현재 ALB ID가 모두 일치하는 ARN, Project 태그, MFA, 서울 |
| `dns-lab.hmyoon.com` Zone | `route53:GetDNSSEC`, `route53:DeleteHostedZone` | 확인한 Zone ID 하나, MFA, 글로벌 API `us-east-1` |
| `proof.dns-lab.hmyoon.com` TXT | `route53:ChangeResourceRecordSets` | 같은 Zone, 해당 이름·유형의 `DELETE`만 |
| `dns-lab.hmyoon.com` Alias A | `route53:ChangeResourceRecordSets` | 같은 Zone, 해당 이름·유형의 `DELETE`만 |

두 수동 Snapshot `helpdesk-learning-20261016-recovery`·`helpdesk-learning-20261021-final`, 모든 기존 Secret·Log·Image·Role·네트워크·Task Definition·인증서는 그대로 보존한다. Target Group 삭제, 다른 DB·ALB·Zone 삭제, Snapshot 삭제·공유 변경, 자동 백업의 별도 삭제, Secret 조회·수정·삭제, Log 삭제·보존 기간 변경, DNSSEC 변경 권한은 추가하지 않는다. Cloudflare의 부모 NS·서비스 CNAME·인증서 검증 CNAME도 이 권한 준비의 변경 대상이 아니다.

## 결정 기록

- **배경:** App Task는 이미 0개지만 원본 RDS·ALB·실습 Zone은 남아 있다. 기존 복원 정책의 삭제 권한은 두 임시 DB만 대상으로 하며, DNS Record 정책은 `CREATE`만 허용한다. 원본 DB 보존 방침은 실제 삭제 전까지 유효하다.
- **비교한 안:** 기존 큰 정책마다 삭제 권한을 추가, Root로 실제 정리까지 실행, 별도 최소 정책으로 분리.
- **결정:** [정리 전용 Template](./deployer-closeout-policy.template.json)으로 Customer-managed Policy `helpdesk-deployer-closeout` 하나를 준비하고 지정 사용자 `helpdesk-deployer` 한 명에게만 연결한다. 모든 Resource는 정확한 ARN이며 wildcard Action·Resource를 쓰지 않는다.
- **이유:** 기존 실행·복원·DNS 생성 권한과 정리 권한을 구분하고, 정리 후 이 정책만 분리해 회수할 수 있다. 시간 제한을 임의로 추가하지 않으며 회수 시점은 정리 결과와 함께 확인한다.
- **Foundation 변경:** 기존 지역 거부의 `NotAction`에 `route53:GetDNSSEC`·`route53:DeleteHostedZone` 두 개만 추가한다. 이는 Allow가 아니며 새 정책의 정확한 Zone·MFA·`us-east-1` 조건과 함께 사용한다. 기존 MFA 거부·14개 다른 문장과 서울 조건은 유지한다. `route53:*` 또는 Zone 생성 예외는 추가하지 않는다.
- **영향 파일:** 새 정리 Template·이 안내·정책 Test, Foundation Template·DNS 정책 Test·IAM README. Java·JAR·Image·DB Schema는 변경하지 않는다.
- **후속 검토:** 실제 저장 JSON·연결 사용자·기존 Version 보존을 대조한다. 작업 후에는 정리 정책 연결 해제와 두 지역 예외 회수를 별도로 검토한다. 자동 회수나 삭제는 이 문서를 실행하는 것만으로 발생하지 않는다.

`ChangeResourceRecordSets`는 두 이름·유형을 각각 별도 문장으로 제한한다. `ForAllValues:StringEquals`와 필수 Key의 `Null: false`를 함께 검사하여 이름·유형의 잘못된 조합, `UPSERT`·`CREATE`, NS·SOA 변경을 새 정책이 허용하지 않게 한다. 기존 DNS 생성 정책 자체는 바꾸지 않는다.

## 적용 순서와 중단 조건

1. Root 로그인·MFA는 사용자가 직접 수행한다. 현재 Foundation의 전체 JSON·기본 Version·연결 대상을 읽어 기존 Template과 대조하고 Git 제외 경로에 보관한다. 예상하지 못한 차이가 있으면 덮어쓰지 않는다.
2. 현재 Version은 보존한 채 Foundation의 새 기본 Version에 위 두 예외만 반영한다. Version 5개 한도로 기존 Version 삭제가 필요하면 별도 승인을 받는다.
3. 확인한 Account ID·현재 ALB ID·실습 Zone ID를 자리표시자에 반영한다. 공개 Template에 실제 ID·Secret을 넣지 않는다. 같은 이름의 기존 정리 정책이 있으면 신규 생성이나 덮어쓰기 대신 먼저 내용을 대조한다.
4. AWS 편집기로 새 정책을 검증하고 저장 JSON 전체를 대조한 뒤 지정 사용자 한 명에게만 연결한다. 기존 정책·Role·권한 경계는 변경하지 않는다.
5. Root를 로그아웃하고 `helpdesk-deployer`의 MFA 세션으로 돌아온다. 새 `GetDNSSEC`와 기존 목록 조회로 대상·보존 상태만 재확인한다. `Delete*`를 시험 호출하거나 삭제 화면을 제출하지 않는다.
6. DNSSEC가 활성 상태이거나 대상·태그가 다르면 멈춘다. DNSSEC를 자동으로 해제하거나 조건·대상을 넓히지 않는다.

로컬 Test는 JSON·권한 범위·조건·Size를 검사한다. AWS 편집기 검증·실제 IAM 평가·삭제 성공을 대신하지 않는다. 삭제 API에는 안전한 권한 확인용 호출이라고 가정한 요청을 보내지 않는다.

## 실제 삭제 전에 다시 확인할 사항

- 원본 RDS와 최신 검증 Snapshot 사이에 데이터 차이가 없는지 읽기 전용 SQL로 대조한다. 차이가 있으면 백업 보완·검증 전에 삭제하지 않는다. 원본 DB 삭제는 앞선 임시 복원 DB 삭제와 다른 승인이다.
- 원본 RDS 삭제의 최종 Snapshot 생성 여부와 자동 백업 보존 여부는 별도로 결정한다. `DeleteDBInstance`의 옵션에 따라 원본의 자동 백업도 사라질 수 있으므로 권한을 준비했다는 이유로 옵션을 미리 확정하지 않는다. 두 기존 수동 Snapshot은 보존한다.
- ALB 삭제 전 서비스 CNAME 정리와 TTL 대기를 별도로 확인한다. Cloudflare 서비스 Record 변경·삭제는 이번 권한 준비에 포함하지 않는다. 부모 NS와 ACM 검증 CNAME은 보존한다.
- Zone은 기본 NS·SOA 외 두 실습 Record만 지운 뒤 삭제할 수 있다. 현재 부모 위임 여부·DNSSEC 상태를 확인하고 각 삭제 직전에 정확한 대상과 최종 승인을 다시 받는다.
- 새 권한을 부여하면 사용자는 대상 삭제를 실행할 수 있다. **실행 직전 확인은 작업 절차의 승인 경계이며 IAM이 사람의 확인을 대신 강제하는 기능은 아니다.** 보존 대상에 대한 새 삭제 권한을 주지 않는 것과 별개로 지킨다.

## 검증 명령

```powershell
node --test src/test/js/iam-closeout-policy.test.mjs src/test/js/iam-dns-lab-policy.test.mjs src/test/js/iam-deployer-policy.test.mjs src/test/js/iam-db-recovery-policy.test.mjs
```

## 로컬 준비 검증 결과

- 정리·DNS·기존 배포·복원 정책 Focused Test **55개 통과**.
- JavaScript 전체 **303개 통과**, 실패·취소·건너뜀 0. 변경한 Test의 ESLint·`git diff --check` 통과.
- 계정·리소스 ID를 예시 길이로 반영한 공백 제외 Size: Foundation **5,509자**, 정리 정책 **2,567자**로 각각 6,144자 이내.
- 기존 Foundation JSON과 비교해 두 `NotAction` 예외 외에는 변경 없음. 기존 MFA 거부·서울 조건·다른 14개 문장 보존.
- 여섯 변경 파일의 UTF-8 BOM 없음·LF 확인. Java·PostgreSQL 회귀, Image Build·Push·CI·AWS IAM 평가는 이번 준비 검증에 포함하지 않는다.
- 비Root 세션은 로그아웃하고 Root 로그인 화면을 준비했다. 사용자 로그인·MFA 완료 후에만 승인한 AWS 정책 변경으로 이어간다.

## 2026-10-22 권한 준비 적용 결과

- Root 로그인·MFA는 사용자가 직접 완료했다. 기존 Foundation **Version 3**의 화면에 표시된 전체 JSON을 읽어 기존 기준과 일치함을 확인했고, 지정 사용자 한 명의 연결·기존 Version 1~3도 확인했다. 변경 전 JSON은 Git 제외 경로에 보관했다.
- Foundation의 **새 기본 Version 4**를 저장했다. 저장된 전체 JSON은 두 예외만 추가한 승인안과 일치하며 **15문장·5,509자**다. Version 1~3을 삭제하지 않았고 다른 14개 문장·MFA 거부·서울 조건을 보존했다.
- 같은 이름의 기존 정리 정책이 없음을 확인한 뒤 **`helpdesk-deployer-closeout` Version 1**을 생성했다. 현재 ALB ID·확인한 Zone ID를 채운 **6문장·5개 고유 Action·2,583자**이며 AWS 편집기의 보안·오류·경고·추천은 모두 0이었다. 저장된 전체 JSON도 Template의 적용본과 정확히 일치했다.
- 선택 화면에서 **`helpdesk-deployer` 한 명만** 선택하고 연결했다. 적용 뒤 연결된 엔터티 1개·IAM 사용자 이름·권한 경계 연결 0개를 확인했다. Execution Role·GitHub Role·다른 Role에는 연결하지 않았다.
- 정리 정책과 Foundation 적용본 JSON은 Git 제외 경로에만 보관했다. 공개 Template에는 자리표시자를 유지한다. 별도 정책으로 분리했으므로 기존 Runtime·Edge·복원·DNS 생성 정책을 편집하지 않았다.
- Root를 로그아웃한 뒤 사용자가 `helpdesk-deployer` 로그인·MFA를 직접 완료했다. 비Root 신원을 확인하고 지정 Zone의 DNSSEC 조회에 성공했다. 상태는 **서명하지 않음**, KSK는 없었다. DNSSEC 설정은 변경하지 않았다.
- 같은 비Root 세션에서 원본 RDS와 두 수동 Snapshot이 계속 `사용 가능`이며 자동 백업은 7일임을 확인했다. 정책 저장·연결·읽기 성공을 실제 삭제 Action의 실행 검증으로 기록하지 않는다.
- 이 권한 준비 단계의 DB·ALB·Zone·Record 삭제 요청은 **0회**였다. 두 수동 Snapshot·Secret·Log·실행 설정·DB Row·Cloudflare DNS는 권한 준비 중 변경하지 않았다. 이후 별도로 승인한 실제 삭제 결과는 다음 절에 기록한다.

## 2026-10-22 실제 정리 결과

사용자가 지정 대상·삭제 옵션·보존 범위를 확인하고 실제 정리를 승인했다. `helpdesk-deployer`의 기존 MFA 세션으로 실행했으며 삭제 중 IAM·보안 조건·Secret은 변경하지 않았다. 마지막 실행 상태는 Task 실행/보류 0개·활성 Service 0개다.

### 삭제 전 데이터 확인

psql 17.11·`verify-full`·TLS 1.3으로 원본에 접속해 `deploy/rds/recovery-verification.sql`을 읽기 전용 Repeatable Read Transaction에서 실행하고 COMMIT했다. 10/21 최신 Snapshot의 복원 검증 기준과 일곱 Table의 건수/전체 Row Hash, Job 집계·참조·V1~V8·Sequence가 모두 일치했다. Ticket·Message·Job·Attempt는 각각 5건, Suggestion·Category는 각각 1건이었다. 추가 재접속 시도는 CloudShell 유휴 종료로 SQL을 실행하지 못했으며, 이미 완료한 대조 결과를 사용했다. 원문·작성자·암호 값은 기록하지 않았다.

### 삭제와 보존 확인

| 대상 | 실행과 최종 확인 |
|---|---|
| 원본 RDS `helpdesk-learning` | 추가 최종 Snapshot·자동 Backup 보존을 선택하지 않고 삭제. 삭제 요청은 한 번만 제출했고 상세의 Not Found·DB 목록 0개 확인 |
| Cloudflare 서비스 CNAME `helpdesk.hmyoon.com` | 해당 Record 한 개만 삭제. 권한 DNS에서 CNAME 부재를 확인한 뒤 기존 TTL 300초 이상 대기 |
| ALB `helpdesk-learning` | CNAME 부재 확인 후 560초 이상 지난 시점에 삭제 절차 시작. 삭제 완료 안내·ALB 목록 0개 확인. 두 Listener도 함께 삭제 |
| Route 53 실습 Record·Zone | TXT와 Alias A를 한 건씩 삭제한 뒤 기본 NS·SOA만 남았음을 확인하고 Zone 삭제. 호스팅 영역 목록 0개 확인 |
| 두 수동 Snapshot | `helpdesk-learning-20261016-recovery`·`helpdesk-learning-20261021-final`의 개별 상세에서 manual·Available 확인 |

자동 Backup 미보존은 RDS 삭제 옵션으로 확인했으며 보존 Backup 목록을 별도로 조회하지는 않았다. Snapshot 목록은 0개로 표시됐지만 두 개별 상세는 모두 Available이었다. 목록과 상세의 차이 원인은 확정하지 않았으며, 이를 해결하려고 권한을 넓히지 않았다. CloudShell의 직접 조회도 임시 인증정보 발급 오류로 완료하지 못했으므로 보존 확인 근거는 두 Snapshot의 개별 상세다.

Secret·CloudWatch Log와 365일 보존 설정·ECR Image·ACM 인증서/검증 CNAME·Target Group·VPC/보안 그룹·Task Definition·Role은 변경하지 않았다. 부모 Cloudflare NS와 다른 서비스 Record도 유지했다. 실행 리소스 정리 뒤에도 Snapshot·Image·Log 보관 비용은 남을 수 있으며 실제 청구액은 조회하지 않았다.

### 재개와 후속 점검

다음 개발·Test는 로컬에서 진행한다. 삭제한 DB에 Start를 요청하거나 기존 Task Definition을 그대로 실행하지 않는다. Cloud 배포를 다시 할 때는 최신 검증 Snapshot에서 새 Private DB를 복원하고 TLS·계정·데이터를 확인한 뒤 JDBC Endpoint·ALB·서비스 DNS·새 Task/Target을 맞춘다. Target Group은 보존했으므로 재사용 조건부터 확인한다. 처음에는 Provider·Worker를 끄고 기존 Row·재로그인·Role·CSRF를 검증한다. [ECS 재배포 순서](../ecs/README.md#2026-10-22-장기-휴지기와-다음-배포)

정리 정책 연결 해제와 Foundation의 두 Route 53 예외 회수는 후속 보안 점검으로 남긴다. 이번 실행에서는 IAM을 변경하지 않았다. 실행 근거 JSON·화면은 WIL 저장소의 Git 제외 `local/evidence/2026-10-22-closeout-inventory/`에 보관했다.

## 공식 근거

- [RDS 삭제 Action의 Resource·태그 조건](https://docs.aws.amazon.com/service-authorization/latest/reference/list_rds.html)
- [ALB 삭제 Action의 Resource·태그 조건](https://docs.aws.amazon.com/service-authorization/latest/reference/list_elbv2.html)
- [Route 53 권한·Resource·조건](https://docs.aws.amazon.com/service-authorization/latest/reference/list_route53.html)
- [글로벌 서비스와 지역 거부 예외](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_examples_aws_deny-requested-region.html)
- [RDS 삭제와 백업 보존](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_DeleteInstance.html)
- [ALB 삭제와 DNS 정리](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-delete.html)
- [Public Hosted Zone 삭제 조건](https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/DeleteHostedZone.html)
