# OpenAI 제안 비교 실험 실행 안내

같은 합성 문의 N01을 `gpt-6-luna`에 보내 프롬프트만으로 JSON을 요청한 결과와 Structured Outputs 결과를 비교한다. Spring Application·실제 고객 문의·Database를 사용하지 않는 독립 실험이다. 추가 패키지 없이 Node.js 22 이상에서 실행한다.

## 호출 조건

- 최대 두 번: Prompt-only 한 번, Structured Outputs 한 번. 첫 방식에서 오류·거부·잘못된 출력이 확인되면 두 번째도 실행하지 않는다.
- 입력과 작업 지시는 같다. `text.format`만 `text`와 `json_schema`로 다르며, Prompt-only에 JSON mode를 사용하지 않는다.
- `reasoning.effort: none`, `max_output_tokens: 600`, `service_tier: default`, `store: false`를 사용한다. Tool·자동 재시도·Model 자동 교체는 없다.
- 승인받은 $1은 10월 2일 Helpdesk 실험 전체의 하루 누적 상한이다. 호출 한 건이나 스크립트 실행마다 새로 부여되는 예산이 아니다. 현재 스크립트는 실행 한 번의 계획용 비용만 검사하며 실행 간 누계를 저장하지 않는다. 이전 호출·실패·재시도의 사용량을 포함해 확인하고, 다른 일자의 유료 호출 예산은 별도로 확인한다.
- 보고서의 사용량 기반 금액도 요금표로 계산한 추정치다. 입력에는 Cache write 단가인 $0.125/1M을 일괄 적용해 여유 있게 계산하고 출력에는 $0.50/1M을 사용한다. 실제 청구액은 OpenAI 사용 내역에서 확인한다.

## Helpdesk 키가 설정된 창에서 실행

기존 논문용 `OPENAI_API_KEY`를 사용하는 창에서는 실행하지 않는다. Helpdesk 전용 PowerShell에서 임시 키를 설정한 뒤 그 창에서 Node를 실행한다. 새 PowerShell을 열거나 PC를 다시 켜면 Helpdesk 키를 다시 설정해야 한다. 키를 Source·명령 인수·파일에 직접 적지 않는다.

`--confirm-helpdesk-key`는 현재 창의 키를 Helpdesk용으로 설정했다는 실행자의 확인이다. 스크립트가 키가 속한 Project를 자동 검증한다는 뜻은 아니다. Windows User·Machine의 환경 변수를 변경하거나 그 값으로 fallback하지 않는다.

Lab 저장소 Root에서 먼저 유료 호출 없는 확인을 실행한다.

```powershell
node .\scripts\week7-openai-pilot.mjs --dry-run
```

호출할 모델·입력·방식과 `reservedCostUsd`를 확인한 뒤 같은 창에서 실행한다.

```powershell
node .\scripts\week7-openai-pilot.mjs --live --confirm-helpdesk-key
```

`OPENAI_API_KEY`가 없으면 전송 전에 종료한다. 키의 값·요청 Authorization Header·Provider 오류 Body·Exception Message는 출력하지 않는다. 보고서에 같은 키가 포함되면 해당 문자열을 제거한다. 키를 포함한 환경 변수 목록이나 전체 HTTP Trace를 공유하지 않는다.

## 결과를 읽는 순서

1. `callsAttempted`는 이 실행에서 시도한 HTTP 전송 수다. 연결 실패 뒤 공급자가 실제로 실행했는지는 이 값만으로 알 수 없다.
2. 각 `results`의 `httpStatus`와 `outcome`을 확인한다. HTTP 오류, Provider 미완료·거부, 응답 Envelope 해석 실패와 Model 출력 실패는 구분한다.
3. `outputText`는 Model이 만든 JSON 문자열이다. `validation.jsonPass`와 `contractPass`는 두 방식에 공통인 검증기 판정이다.
4. 요약·분류·우선순위는 원문과 직접 대조한다. `manualContentReview: NOT_SCORED`는 코드가 사실성 점수를 자동으로 매기지 않았다는 뜻이다.
5. `usage`, `latencyMs`, `estimatedTotalCostUsd`를 확인한다. 사용량이 없거나 실패로 비용을 모르면 `null`이다. 0원으로 처리하지 않는다.

N01의 평가 기준은 별도 WIL 저장소의 `week7/ai-suggestion-evaluation-draft.md`에 있다. 기대 정답을 Model의 Prompt에 넣지 않는다. 이번 두 응답만으로 전체 통과율이나 Structured Outputs의 요약 품질 개선을 주장하지 않는다.

Timeout·연결 실패는 공급자의 실행 여부가 불명확할 수 있다. `TRANSPORT_FAILURE_OUTCOME_UNKNOWN`이면 곧바로 같은 명령을 반복하지 않고 결과와 사용 내역부터 확인한다. 출력 실패도 값을 채우거나 지워서 통과시키지 않는다.

## 검증기와 전송용 Schema

논리적 계약 v2의 `oneOf`·`const`·`uniqueItems`를 Provider가 모두 지원한다고 가정하지 않는다. 전송용 Schema는 필수 네 Field·Type·Enum·배열을 표현하고, 공통 검증기는 정확한 Field 집합, `decision`별 조합, 공백 제거 후 Unicode Code Point 길이와 분류 중복을 다시 검사한다.

`ABSTAIN`의 null 조합은 초안의 형식을 검사하는 것이며 업무상 허용 조건이나 DB 저장 계약을 확정하지 않는다. 이 실험은 Suggestion을 저장하거나 Ticket을 변경하지 않는다.

유료 호출 없는 Test:

```powershell
node --test .\src\test\js\week7-openai-pilot.test.mjs
```

Test는 합성 응답과 가짜 HTTP 함수를 사용한다. API 접속·실제 생성 결과·Spring Integration·PostgreSQL 저장의 근거와 다르다. 별도 출력 파일·전체 HTTP Log는 자동으로 만들지 않는다.

## 전체 평가의 준비 코드

예비 실험의 입력·Prompt·Version은 위 Pilot에 그대로 남긴다. 새 `week7-ai-evaluation-dataset.mjs`에는 WIL의 합성 문의 13건과 평가자용 기대값을 옮겼고, `week7-ai-evaluation.mjs`는 두 방식·두 반복의 52회 계획과 공통 검증 결과의 분리 집계를 준비한다. 현재 실행 경로는 `--dry-run`뿐이며 `--live`는 거부한다.

```powershell
node scripts/week7-ai-evaluation.mjs --dry-run
node --test src/test/js/week7-ai-evaluation.test.mjs
```

두 방식에는 핵심 사실 보존과 전체 영향에 따른 Priority 규칙을 동일하게 추가했다. 요청 Body에는 제목·본문만 포함하고 `expectedCategories`·`expectedPriority`·`coreFacts`는 보내지 않는다. 반복 순서는 두 번째 반복에서 반대로 배치하며 무작위 배정이라고 부르지 않는다. 모든 계획 항목은 최초 응답을 평가하는 독립 반복이고 실패 복구 요청이 아니다.

분류·우선순위 비교는 `CANDIDATE_LABEL_COMPARISON`이다. 미확정 Label을 승인된 정답으로 바꾸지 않는다. 요약과 Injection의 내용 평가는 `NOT_SCORED`로 남기며, 합성 민감 정보 검사는 정확히 지정한 자리표시자의 복사 여부만 확인한다. 본 평가·Spring 저장 흐름의 실행 결과는 아니다.

예산 계산 함수는 이전 비용을 포함하고 사용량을 모르는 경우 예약 추정치를 유지한 채 후속 예약을 막는 규칙을 Test한다. 현재는 메모리 객체를 대상으로 한 순수 함수다. 실행 간 누계 저장·동시 Process 잠금·실제 하루 지출 제한에는 아직 연결하지 않았다. 실제 전송을 추가하기 전에 평가 기준, 유료 호출 한도와 누계 보존 방식을 확정해야 한다.

구현 선택은 예비 코드를 수정하는 안 대신 별도 준비 모듈을 두는 안이다. 이전 비교 조건을 보존하고, 미확정 계약이 실제 과금으로 이어지지 않게 하기 위해서다. 영향 파일은 새 Dataset·평가 모듈·Test와 ESLint 대상이다. 다음 검토에서는 Label 고정, 실제 전송과 결과 보존, 누적 예산 관리를 함께 확인한다.

## 선택한 실행 경계

- 기존 영속성·인증 흐름을 건드리지 않고 공급자 형식부터 이해하기 위해 독립 스크립트를 선택했다. Spring에 바로 연결하는 방식은 Provider 응답과 Application 실패를 동시에 다뤄야 하므로 다음 단계로 둔다.
- 논문용 전역 키를 변경하는 대신 전용 PowerShell의 임시 키를 사용한다. 실행 책임은 그 창에 남기며 Codex의 다른 Process가 가진 키로 대신 호출하지 않는다.
- 합성 입력만 전송하고 `store: false`를 설정한다. API의 응답 재조회 저장을 끄는 옵션이지 Provider의 모든 보관을 없앤다는 보장은 아니다.
- 적용 파일은 `scripts/week7-openai-pilot.mjs`와 독립 JavaScript Test다. 후속 검토에서는 Prompt·Schema·설정이 고정 평가와 같은지, 실제 실패·사용량·내용 결과가 어떤지 확인한다.

## 공식 문서

- [GPT 6 Luna 기능과 요금](https://developers.openai.com/api/docs/models/gpt-6-luna)
- [Structured Outputs의 지원 범위와 응답 처리](https://developers.openai.com/api/docs/guides/structured-outputs)
- [Responses API 요청과 응답](https://developers.openai.com/api/reference/cli/resources/responses/methods/create)
- [Responses의 저장 설정](https://developers.openai.com/api/docs/guides/migrate-to-responses)
