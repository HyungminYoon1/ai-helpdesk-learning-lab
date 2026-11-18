# 첫 파일럿 개인 계정 설계

[개발 로드맵](./roadmap.md#2-개인별-계정과-서비스-권한-운영)의 계정 운영 계약이다. 2026-11-15에 OIDC 사용, 초대·복구·비활성화 정책과 첫 계정 관리자를 확정했다. 서울 검증을 마친 뒤 Cognito Lite를 첫 파일럿 OIDC 제공자로 확정했다.

## 인증과 서비스 권한

- 현재 [배포 인증](../src/main/java/lab/helpdesk/security/DeploymentSecurityConfiguration.java)은 설정 기반 학습 계정을 생성한다. 파일럿은 개인별 OIDC 로그인으로 전환한다. Spring Security에서 Authorization Code + PKCE로 처리하고, 서명·발급자·대상·만료 및 로그인 요청의 상태값과 nonce를 검증한다. 브라우저는 기존 서버 세션과 CSRF 보호를 사용한다. OIDC 토큰은 필요한 경우 서버에서만 다룬다.
- 신원 연결 키는 발급자 `iss`와 해당 발급자가 부여한 사용자 식별자 `sub`의 조합이다. Helpdesk 내부의 영속 사용자 ID로 연결한다. 이메일 검증은 초대 대상 확인에 사용하며, 이메일이나 표시 이름으로 기존 사용자를 자동 연결하지 않는다. 문의 소유권과 작성·배정 이력은 내부 ID를 계속 참조한다.
- 계정 사용 여부, 계정 관리 역할과 서비스별 직원 권한은 Helpdesk DB에서 판단한다. 제공자 그룹이나 토큰 속성만으로 앱 권한을 부여하지 않는다. 직원과 계정 관리자의 다중 인증(MFA)은 OIDC 제공자에서 강제한다.
- **첫 계정 관리자는 프로젝트 운영자 본인**이다. 새 파일럿 설치에서 본인의 OIDC 신원을 확인하고 승인한 `iss`·`sub`에 초기 계정 관리 역할을 한 번 부여한다. 계정 관리 역할은 직원 역할, 서비스 지원관리자 권한 및 AWS 권한과 별개로 부여한다. 초기 설정은 감사 기록에 남기고 공개 등록 경로에서 사용할 수 없게 한다. 식별자 값은 운영 기록에서만 관리한다.

## 초대와 회수

| 작업 | 처리 계약 |
|---|---|
| 초대 발급 | 계정 관리자가 받을 주소와 부여할 역할·서비스 범위를 정한다. 앱 초대 값은 충분한 엔트로피를 가진 무작위 값으로 생성해 해시만 저장한다. 유효 기간은 발급 후 24시간이며, 재발급 시 해당 초대의 이전 미사용 토큰을 폐기한다. |
| 초대 수락 | 인증된 신원의 `email_verified=true`와 초대 주소가 일치하고 초대가 유효할 때 수락한다. 초대 사용 처리와 사용자 신원 연결 및 권한 부여를 하나의 트랜잭션으로 저장한다. 다른 신원에 이미 연결된 사용자나 재사용·만료한 초대에는 권한을 주지 않는다. Cognito는 이메일 확인 뒤의 재로그인 조건도 적용한다. |
| 복구 | 비밀번호 재설정과 분실한 MFA 수단의 복구는 제공자의 검증 절차를 이용한다. 앱에서 접근을 다시 열기 전 계정 관리자가 신원과 사유를 확인하고 감사 이력을 남긴다. 제공자 계정을 새로 만들어 `iss`·`sub`가 달라지는 경우 기존 문의와 이력을 다른 사람에게 넘기지 않도록 별도의 신원 재검증과 명시적 승인을 거친다. |
| 계정 비활성화와 재활성화 | 계정 관리자가 상태, 세션 버전과 변경 이력을 함께 커밋한다. 이후 시작하는 보호 요청은 서버가 현재 상태와 세션 버전을 확인한다. 비활성 계정의 기존 세션과 로그인을 거부한다. 재활성화한 계정은 새 인증을 거쳐야 새 세션을 받는다. |
| 서비스 역할 회수 | 커밋 이후 해당 서비스에서의 직원 조회·쓰기·저장 결과 재확인을 기존 세션에서도 막는다. 계정 자체가 활성이고 해당 문의를 접수한 본인이라면 고객 권한은 유지한다. |
| 감사 기록 | 누가 어느 계정에 어떤 초대·복구·회수를 언제 시도했는지와 결과를 추적한다. 비밀번호, MFA 코드, 초대 원문, 세션 값과 OIDC 토큰은 기록하지 않는다. |

OIDC 제공자가 사용자를 생성해 보내는 안내와 Helpdesk 초대는 각자 유효 기간과 성공 조건을 가진다. Helpdesk는 **앱 초대 수락이 커밋된 뒤** 서비스 권한을 부여한다. 사용자 생성이나 이메일만 확인된 상태에서 보호 API에 접근할 수 있는지도 검증한다. [서비스 권한 계약](./ticket-assignment.md), [OWASP 세션 관리 지침](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)

## 제공자 비교

한 조직의 서비스 한 개에 고객 최대 10명, 담당 직원 2명, 지원관리자 1명이 참여하는 계획을 기준으로 비교했다. 첫 계정 관리자가 별도 계정이면 최대 14명이다. 비용은 2026-11-15 확인한 공개 USD 요금 기준이다. MAU는 월간 활성 사용자다. 서비스 운영 인프라, 발송비와 세금은 별도다.

| 후보 | 외부 고객 초대 | 직원 MFA | 파일럿 인증 비용 |
|---|---|---|---|
| Amazon Cognito User Pools Lite | 관리자가 직접 사용자 생성, 이메일로 임시 비밀번호 안내. 공개 가입을 끄고 검증된 이메일로 Helpdesk 초대를 수락한다. | User Pool에서 인증 앱의 일회용 코드(TOTP)를 필수로 설정한다. 단일 Pool의 직접 로그인 사용자 모두에게 적용되므로 고객도 등록해야 한다. | 계정 또는 AWS 조직의 직접 로그인 무료 사용량 10,000 MAU에 여유가 있으면 월 $0 예상. 기본 사용료 기준이며 이메일 비용은 발송 구성에 따라 별도다. [요금](https://aws.amazon.com/cognito/pricing/), [관리자 초대](https://docs.aws.amazon.com/cognito/latest/developerguide/how-to-create-user-accounts.html), [MFA 설정](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-settings-mfa.html) |
| Auth0 B2C Essentials | 개인 이메일 계정을 프로비저닝하고 검증할 수 있다. 누구에게 서비스 권한을 줄지는 Helpdesk 초대로 결정한다. | Pro MFA가 포함된다. Post-Login Action을 사용해 직원만 추가 인증하는 구성을 검토할 수 있다. 직원 구분 근거와 요금제에서 실제 동작하는지 확인해야 한다. | 500 MAU 기준 월 $35부터. [요금](https://auth0.com/pricing), [이메일 검증](https://auth0.com/docs/manage-users/user-accounts/verify-emails), [조건부 MFA](https://developer.auth0.com/resources/labs/actions/secure-user-accounts-with-auth0-actions-and-custom-mfa) |
| Microsoft Entra Workforce + External ID B2B | 외부 주소를 조직 테넌트의 게스트로 초대할 수 있다. 개인 주소로 로그인했을 때 앱의 이메일 검증 계약을 충족하는지 확인해야 한다. | Conditional Access로 직원 및 관리자 그룹에 MFA 정책을 적용할 수 있다. | 신규 Entra ID P1 구매 시 $7/인/월(연간 약정). 직원·관리자 3~4석 기준 월 환산 $21~28, 이미 P1이 포함된 조직은 추가 구독료가 없을 수 있다. 게스트 과금은 External ID 조건을 별도로 확인한다. [요금](https://www.microsoft.com/en-us/security/microsoft-entra-pricing), [게스트 초대](https://learn.microsoft.com/en-us/entra/external-id/add-users-administrator), [Conditional Access](https://learn.microsoft.com/en-us/entra/identity/conditional-access/overview), [게스트 과금](https://learn.microsoft.com/en-us/entra/external-id/external-identities-pricing) |

## 채택 구성과 출시 전 확인

**첫 파일럿은 서울 리전의 Cognito Lite User Pool 한 개로 진행한다.** Cognito에 생성한 계정과 기본 Hosted UI를 사용한다. 직원과 계정 관리자뿐 아니라 고객도 TOTP를 사용한다. [Cognito 요금제 기능](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-sign-in-feature-plans.html), [서울 엔드포인트](https://docs.aws.amazon.com/general/latest/gr/cognito.html)

1. 관리자 초대로 받은 임시 비밀번호를 변경한다. 첫 OIDC 로그인으로 시작한 세션은 온보딩 범위로 제한하고, 이메일 확인 코드 발송과 검증에 사용한다. Cognito는 MFA가 필수여도 새 계정의 첫 로그인에서 토큰을 발급할 수 있다. [Cognito 첫 로그인과 MFA](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-settings-mfa.html), [이메일 검증 코드 API](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_GetUserAttributeVerificationCode.html)
2. 이메일 확인 뒤 기존 Hosted UI 세션을 끝내고 서버가 시작한 새 로그인에서 TOTP 등록 또는 인증을 완료한다. 초대 수락은 이 로그인에 대응하는 새 토큰의 `iss`·`sub`, `email_verified=true`와 초대 주소를 확인한 뒤 처리한다. G2에서는 최초 로그인 토큰이나 같은 Hosted UI 세션을 재사용해도 보호 기능에 들어갈 수 없는지 검증한다.
3. MFA 기기 분실 시 운영자가 신원과 요청 사유를 확인한 뒤 Cognito 세션과 기존 TOTP 등록을 회수한다. 같은 계정에 새 TOTP를 등록해 복구한다. 인증 앱에 같은 이름이 표시될 수 있으므로 등록 즉시 구분 가능한 이름을 사용하도록 안내한다. 운영 App의 세션 회수, 역할 및 감사 검사는 G2에서 검증한다. [기존 TOTP 등록 제거 API](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_AdminDeleteSoftwareToken.html)
4. G2에서는 다른 이메일, 만료·재발급 초대, 동시 수락, 고객 및 서비스 간 접근, 역할 회수 뒤 본인 문의 조회, 비활성화 뒤 기존 세션 차단을 검증한다. 첫 관리자의 역할 범위와 분실 복구 절차도 점검한다. G0에서는 실제 파일럿 참여 규모의 비용과 기본 발송 한도를 확인한다. [출시 승인표](./roadmap.md#출시-승인표), [Cognito 발송 한도](https://docs.aws.amazon.com/cognito/latest/developerguide/quotas.html)

## 서울 Cognito 검증 기록

2026-11-15에 서울의 임시 Lite User Pool 한 개, 공개 PKCE 앱 클라이언트 한 개, 기본 Hosted UI 도메인 한 개에서 검증했다. 공개 가입 차단, 필수 TOTP, Loopback 콜백과 OIDC S256을 확인했다. 처음 로그인한 사람이 TOTP 등록 전에 토큰을 받을 수 있는 동작을 확인해 위의 온보딩 제한을 설계에 반영했다.

| 확인 항목 | 관찰 결과 |
|---|---|
| 초대 수락 | Cognito 기본 메일을 사용해 한 Gmail 받은편지함의 두 별칭으로 합성 고객·직원을 초대했다. 두 계정이 임시 비밀번호를 바꾸고 첫 로그인에 성공했다. 직원의 최신 초대는 같은 계정에 한 번 재발송한 뒤 수신을 확인했다. |
| 이메일 검증 | 두 계정에서 본인이 메일로 받은 인증 코드를 제출해 Cognito 검증을 완료했다. 새 OIDC 토큰에서 두 계정의 `email_verified=true`를 확인했다. 이미 성공한 고객 코드의 후속 제출은 HTTP 400이었다. |
| TOTP 등록 | 고객과 직원의 기존 TOTP 등록을 제거하는 API가 두 계정 모두에서 성공했다. 고객 재로그인과 직원의 MFA 화면에서 인증 앱 코드 흐름을 확인했다. |
| 기기 분실 복구 | 지정한 합성 계정 두 개에서 기존 Cognito 세션과 TOTP 등록을 회수했다. Pool의 필수 MFA를 유지하며 새 TOTP 등록 후 각 계정의 복구 로그인에 성공했다. 인가 코드를 PKCE로 교환하고 ID 토큰의 서명·발급자·대상·nonce를 검증했다. 각 토큰의 `iss`·`sub`가 해당 계정의 최초 토큰과 같았다. |

기존 배포 사용자의 Cognito 목록 요청이 거부됐고 사람이 맡을 수 있는 기존 검증 역할은 확인되지 않았다. 사용자가 승인한 임시 IAM 역할로 Pool을 만들고, 생성 권한을 회수한 뒤 해당 Pool에만 Cognito 권한을 부여했다. 역할에는 MFA, 서울 리전과 유효시간을 적용했다. 비용은 무료 사용량에 여유가 있을 때 인증 기본료 $0으로 추정했다. [Cognito 요금](https://aws.amazon.com/cognito/pricing/)

실험 결과로 제공자를 확정했다. 실행 근거의 범위는 Cognito와 로컬 OIDC 콜백이며 Helpdesk 초대 수락 트랜잭션, 세션 무효화와 서비스 역할 검사는 실제 App을 연결한 G2에서 확인한다. 주소와 인증 정보는 검증 기록에서 제외했다.

## Helpdesk 연동

`postgres,cognito` Profile을 사용하면 `/login`에서 Cognito 로그인을 시작한다. 처음 초대받은 사람은 Helpdesk 초대 코드를 입력한다. 첫 OIDC 콜백은 서버 세션에 이메일 검증용 상태만 남긴다. Cognito 토큰이나 그룹 Claim에서 Helpdesk 역할을 부여하지 않는다.

이메일 검증 뒤 App에서 Cognito 로그아웃을 거쳐 두 번째 Authorization Code 요청을 시작한다. 이 요청의 `state`와 Spring Security가 검증한 `nonce`에 대응하는 토큰에서 같은 발급자, 사용자 ID, 이메일 및 검증 상태를 확인한다. 서명된 `auth_time`은 첫 로그인보다 늦고 두 번째 인가 요청이 시작된 초 이후여야 한다. 토큰 갱신이나 공유된 Hosted UI 세션에서 인증 시각이 유지되면 재로그인으로 인정하지 않는다. [Cognito ID 토큰](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-id-token.html)

재로그인 전과 콜백 및 초대 수락에서 Pool의 TOTP 필수 설정과 App Client의 직접 Cognito 로그인 및 Authorization Code 구성을 다시 읽는다. 다른 MFA 방식, 연동 제공자나 설정 조회 실패가 있으면 인증을 진행할 수 없다. 새 로그인 확인 뒤 CSRF가 붙은 `POST /onboarding/accept`에서 초대 사용과 DB 신원, 서비스 역할을 함께 저장한다. Commit 뒤 브라우저 세션 ID와 CSRF 표식을 교체하고 Helpdesk 인증을 설정한다.

초대는 발급 시각부터 24시간 유효하다. 같은 수신 주소에 재발급하면 미사용 초대를 회수한다. 첫 등록 절차의 세션 상태는 15분 동안 유지된다. 이미 연결된 사용자는 가입 코드 없이 직접 로그인한다. 보호 API와 화면에서 현재 활성 멤버십을 다시 읽으므로, 직원 권한이 회수된 뒤에도 활성 계정은 고객 기능을 이용할 수 있다. 직원 화면에는 활성 직원 권한이 필요하고, 개별 문의와 서비스의 접근 범위는 각 API에서 확인한다.

### 계정 상태와 세션 버전

V16은 기존에 연결된 계정과 새 계정을 `ACTIVE`, 세션 버전 `0`으로 시작한다. 보호 화면과 API에서 기존 계정·권한 조회 결과를 사용해 활성 상태와 세션 발급 때 저장한 버전을 함께 대조한다. 비활성화 뒤 시작한 요청과 버전이 지난 세션은 인증을 잃는다. 이미 실행 중인 요청에는 해당 요청이 권한을 검사한 시점이 적용된다.

로그인 콜백에서도 현재 계정 상태를 읽는다. 재활성화한 계정은 서명된 ID 토큰의 `auth_time`이 `reactivated_at`보다 늦어야 한다. 인증 시각이 그 기준과 같거나 더 이르면 Cognito 로그아웃을 거쳐 다음 로그인에서 새 인증을 받도록 안내한다. [Cognito ID 토큰](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-id-token.html) [Cognito 로그아웃](https://docs.aws.amazon.com/cognito/latest/developerguide/logout-endpoint.html)

### 설정과 첫 운영자

| 설정 | 값의 범위 |
|---|---|
| `helpdesk.cognito.issuer` | 서울 Pool의 HTTPS OIDC Issuer. |
| `helpdesk.cognito.domain` | 해당 Pool의 기본 Cognito Hosted UI HTTPS Origin. |
| `helpdesk.cognito.app-origin` | Helpdesk의 HTTPS Origin. 로컬 콜백 검증에는 Loopback HTTP를 사용할 수 있다. |
| `helpdesk.cognito.client-id` | 해당 Pool의 App Client ID. |
| `helpdesk.cognito.client-secret` | 비밀값을 사용하는 Client일 때 서버에만 전달하는 선택 값. |
| `helpdesk.cognito.bootstrap-subject` | 확인한 첫 계정 관리자의 Cognito `sub`. 초기 연결 뒤 제거할 수 있다. |

Cognito App Client의 콜백은 `/login/oauth2/code/cognito`다. Allowed sign-out URL에는 App Origin의 `/onboarding/resume`과 `/login`을 등록한다. `openid`, `email`, `aws.cognito.signin.user.admin` Scope를 허용한다. App 실행 Role에는 지정한 Pool에서 `cognito-idp:GetUserPoolMfaConfig`와 `cognito-idp:DescribeUserPoolClient`만 필요하다. 후자는 해당 Pool의 App Client 메타데이터에 접근할 수 있으므로 전용 Pool ARN에 제한한다. 서버의 Cognito 앱 클라이언트 인증 정보와 사용자 Token을 기록하지 않는다. [Cognito 권한 범위](https://docs.aws.amazon.com/service-authorization/latest/reference/list_cognito-idp.html)

첫 운영자는 신원을 확인한 뒤 코드 없이 같은 이메일 확인과 TOTP 재로그인 절차를 밟는다. DB가 처음 연결한 관리자 한 명을 기억한다. [지원 서비스 준비](../README.md#로컬-서비스-준비) 후 운영자는 인증과 CSRF가 필요한 `POST /api/account-invitations`에 `email`과 `role`을 보낸다. `CUSTOMER`는 두 필드로 발급한다. `AGENT` 또는 `SUPERVISOR`는 `serviceId`와 `displayName`도 지정한다. 운영자가 Cognito에서 사용자를 초대하고, Helpdesk에서 받은 코드를 해당 수신자에게 전달한다. 응답은 코드와 만료 시각을 한 번 반환하며 DB는 해시를 보관한다.

### 계정 관리자 API

`GET /api/accounts`는 계정 관리자에게 연결된 계정의 `accountKey`, 수락한 초대 주소 `invitedEmail`, 관리자 여부, 상태와 세션 버전을 반환한다. 초대 주소는 초대 당시 값이며 첫 관리자에게는 이 필드가 비어 있다. 계정 응답에는 `Cache-Control: no-store`를 적용한다.

관리자는 CSRF 표식과 함께 `PUT /api/accounts/{accountKey}/status`에 바꿀 상태, 방금 조회한 세션 버전과 사유 코드를 보낸다. 예를 들어 보안 검토를 위해 버전 `0`의 계정을 비활성화할 때는 다음과 같이 요청한다.

```json
{"status":"DISABLED","expectedSessionVersion":0,"reason":"SECURITY_REVIEW"}
```

이 API가 받는 상태는 `ACTIVE`와 `DISABLED`다. 사유는 계정 소유자 요청 `OWNER_REQUEST`, 서비스 이용 종료 `SERVICE_ACCESS_ENDED`, 보안 검토 `SECURITY_REVIEW`, 운영 검토 `ADMINISTRATIVE_REVIEW` 중 하나를 쓴다. 정해진 코드로 인해 감사 이력에 자유 텍스트가 저장되지 않는다.

서버는 대상 계정 행을 잠그고 예상 버전을 비교한다. 성공하면 상태와 세션 버전, 변경 이력을 함께 커밋한 뒤 `200`으로 반환한다. 같은 버전과 같은 상태를 다시 보내면 현재 결과를 반환한다. 버전이 다르면 `409 ACCOUNT_STATUS_VERSION_CONFLICT`를 돌려준다. 응답을 잃었거나 충돌이 발생하면 목록에서 현재 상태와 버전을 다시 읽고, 필요할 때 새 버전으로 변경을 요청한다. 첫 계정 관리자를 비활성화하는 요청은 `409 FIRST_ADMINISTRATOR_PROTECTED`로 거부한다.

V17 감사 이력은 계정 ID, 관리자 ID, 이전·이후 상태, 새 버전, 사유 코드와 변경 시각을 저장한다. 계정과 문의의 연결은 내부 사용자 ID로 보존한다. 재활성화 시각은 DB가 상태 변경을 기록한 시각을 사용한다.

### 로컬 검증

로컬 통합 검증은 서명된 토큰을 발급하는 Loopback OIDC 서버와 PostgreSQL 17을 사용했다. PKCE, 서명과 nonce, 첫 세션의 API 차단, 이전 인증 시각과 다른 신원 거절, TOTP 정책 오류 차단, Commit 실패 및 동시 초대 수락을 확인했다. 직원 권한을 회수한 뒤 같은 세션에서 직원 화면과 API는 차단되고 고객 화면과 API는 유지되는 것도 확인한다. 실제 Cognito에 연결한 프로덕션 설정과 사용자 흐름은 출시 후보에서 다시 검증한다.

관리자 테스트에서는 두 세션을 만든 뒤 비활성화해 기존 요청과 새 로그인이 거부되는 것을 확인했다. 비활성화 동안 쓰지 않았던 세션도 재활성화 후 차단됐고, 이전 Cognito 인증 시각을 가진 토큰으로도 접근이 열리지 않았다. 새 서명 토큰으로 로그인한 뒤에는 동일 계정으로 서비스를 조회했다. DB가 커밋을 거부한 경우에는 상태와 버전, 이력이 이전 상태로 돌아가고 해당 세션은 계속 사용할 수 있었다. [HTTP 통합 테스트](../src/test/java/lab/helpdesk/cognito/CognitoOnboardingHttpIntegrationTest.java)

격리 PostgreSQL 17에서 합성 계정과 문의가 있는 V15 스키마에 V16과 V17을 적용했다. 계정은 `ACTIVE`와 버전 `0`으로 시작했고 문의의 기존 소유권을 확인했다.

2026-11-16에 `366c2aa`와 `6afe150`의 계정 상태 및 세션 검사를 최종 검토했다. 관련 로컬 테스트 42건을 재실행해 모두 통과했다. 두 커밋을 포함한 Lab `main`의 `6afe150`에서 Java 및 PostgreSQL 테스트, JavaScript 테스트, ESLint와 검증용 Docker 이미지 빌드가 통과했다. [GitHub Actions 실행 기록](https://github.com/HyungminYoon1/ai-helpdesk-learning-lab/actions/runs/43727098874)

## Cognito 계정 복구 구현 계획

2026-11-16에 정리한 첫 파일럿의 후속 구현안이다. 2026-11-18에는 V18 복구 접수와 접근 차단까지 구현했다. 복구 대상은 이미 Helpdesk 내부 ID와 Cognito `iss` 및 `sub`가 연결된 계정이다. 제공자 계정을 새로 만들어 `sub`가 달라진 경우에는 별도의 신원 재검증과 연결 승인을 거친다.

1. 계정 관리자가 본인과 사유를 확인한다. 복구 요청을 만들고 계정을 `RECOVERY_PENDING`으로 바꾸면서 세션 버전과 감사 이력을 한 트랜잭션으로 저장한다. 커밋 뒤 기존 세션과 일반 로그인을 차단하고, 그 복구 요청에 연결된 인증만 허용한다. 복구 대기는 별도의 완료 승인으로만 해제한다. AWS 호출은 DB 커밋 뒤 실행하며, 호출에 실패하면 복구 대기를 유지한다.
2. 비밀번호를 잃은 경우 Cognito의 `admin_only` 설정을 사용하는 안을 권장한다. `AdminSetUserPassword(Permanent=false)`로 임시 비밀번호를 설정하고 본인을 확인한 비공개 경로로 전달한다. 사용자는 Cognito 화면에서 새 비밀번호를 정하고 기존 TOTP로 인증한다. [Cognito 복구 설정](https://docs.aws.amazon.com/cognito/latest/developerguide/managing-users-passwords.html) [임시 비밀번호 API](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_AdminSetUserPassword.html)
3. TOTP 기기를 잃은 경우 `AdminUserGlobalSignOut`으로 Cognito 세션을 회수하고 `AdminDeleteSoftwareToken`으로 기존 등록을 제거한다. 사용자는 새 인증기를 등록한 다음 새 코드로 다시 로그인한다. AWS 응답이 유실되면 복구 대기를 유지하고 현재 등록 상태를 확인한다. 삭제 재시도는 새 등록을 열기 전 단계로 제한한다. [전역 로그아웃](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_AdminUserGlobalSignOut.html) [TOTP 등록 제거](https://docs.aws.amazon.com/cognito-user-identity-pools/latest/APIReference/API_AdminDeleteSoftwareToken.html)
4. Hosted UI 로그아웃을 거쳐 새 인가 요청을 시작한다. 서명된 ID 토큰의 `auth_time`으로 복구 처리 이후의 인증인지 확인하고, `iss`와 `sub`를 기존 계정 연결과 대조한다. 접근 재개 승인 뒤 새 정상 로그인에서 Helpdesk 세션을 발급한다. 감사 이력에는 요청자, 대상, 사유, 시각과 결과를 남긴다. [Hosted UI 로그아웃](https://docs.aws.amazon.com/cognito/latest/developerguide/logout-endpoint.html) [ID 토큰의 인증 시각](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-id-token.html)

### V18 복구 요청 API

활성 계정 관리자는 CSRF 표식과 함께 `POST /api/accounts/{accountKey}/recoveries`에 `clientRequestId`, `expectedSessionVersion`, `kind`, `reason`을 보낸다. `kind`는 `PASSWORD_RESET` 또는 `TOTP_RESET`이며, `reason`은 계정 상태 변경과 같은 사유 코드를 사용한다. 대상 계정의 상태, 증가한 세션 버전과 요청 이력이 함께 커밋된 뒤 `201`과 `recoveryId`를 반환한다.

같은 관리자와 대상에 대해 같은 UUID와 내용으로 다시 요청하면 기존 접수 결과를 `200`으로 반환한다. 같은 UUID에 다른 내용을 담으면 `409 ACCOUNT_RECOVERY_REQUEST_CONFLICT`다. 진행 중인 복구가 있는 대상에 새 요청을 보내거나 일반 상태 변경으로 접근을 재개하려고 하면 `409 ACCOUNT_RECOVERY_PENDING`이다. 첫 계정 관리자에 대한 일반 복구 요청은 `409 FIRST_ADMINISTRATOR_PROTECTED`로 거부한다.

이력은 요청한 관리자, 대상, 복구 유형, 사유, 접수 시각과 이전 상태를 저장한다. 이미 `DISABLED`인 계정도 그 상태에서 바로 복구 대기로 전환할 수 있다. 기존 비활성화 해제에는 별도의 재활성화 승인 기준을 적용한다. `201`은 Helpdesk 접수와 접근 차단이 커밋됐다는 뜻이다. Cognito 처리, 새 인증 검증과 접근 재개는 다음 구현 단계다.

로컬 OIDC와 PostgreSQL에서 세션 차단, 정상 로그인 거부, 관리자 권한과 CSRF, 재요청과 동시 접수, 커밋 실패 시 계정 상태와 이력의 롤백을 검증했다. [HTTP 통합 테스트](../src/test/java/lab/helpdesk/cognito/CognitoOnboardingHttpIntegrationTest.java)

합성 데이터가 있는 V17 DB에 V18을 적용해 첫 계정 관리자, 비활성 상태와 기존 감사 이력, 문의 소유권이 보존되는 것을 확인했다. 전체 로컬 검증은 Maven 1,254건, Node 421건과 ESLint를 통과했다.

### 첫 계정 관리자의 인증기 분실

파일럿 전에 Helpdesk 로그인과 독립된 AWS 운영자 경로를 준비한다. Cognito 인증기와 별도로 보관한 AWS MFA 수단으로 로그인하고 복구 작업을 실행할 수 있는지 연습한다. 필요한 Cognito 권한은 지정 Pool로 제한하고, 복구 작업에서 DB의 첫 관리자와 연결된 신원인지 확인한다. [AWS MFA 관리](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_mfa_enable.html) [Cognito IAM 권한 범위](https://docs.aws.amazon.com/service-authorization/latest/reference/list_cognito-idp.html)

분실 시 AWS 운영자 경로에서 복구를 시작한다. Helpdesk 접근 잠금부터 Cognito 세션 회수, TOTP 재등록, 새 인증 검증까지 같은 복구 흐름을 사용한다. AWS 실행 주체와 내부 복구 기록을 연결한다. 기존 첫 관리자 신원으로 인증을 마친 뒤 그 계정의 접근을 재개한다.

[계정 정책의 결정과 대안](./pilot-decision-log.md#d02-운영자-초대와-권한-회수)
