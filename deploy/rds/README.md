# Snapshot 복원 데이터 대조

> 2026-10-22: 원본 RDS는 승인 후 삭제했고 두 수동 Snapshot은 Available로 보존했다. 다음 Cloud 배포는 최신 검증 Snapshot에서 새 DB를 복원한 뒤 아래 SQL로 확인한다. 기존 Endpoint에 접속하거나 삭제한 DB에 Start를 요청하지 않는다. [정리 결과](../iam/closeout.md#2026-10-22-실제-정리-결과) · [재배포 순서](../ecs/README.md#2026-10-22-장기-휴지기와-다음-배포)

[recovery-verification.sql](./recovery-verification.sql)은 원본과 복원 DB에서 같은 기준으로 실행하는 읽기 전용 조회다. Snapshot을 만들기 직전의 원본 결과와 복원 뒤 결과를 비교한다. 실제 실행 단계는 [복원 절차](../iam/db-recovery.md)를 따른다.

한 Repeatable Read·Read-only Transaction 안에서 여섯 업무 Table과 Flyway 이력의 건수·SHA-256, 본문/작성자 존재 건수·Job 상태/누적 예약·참조 누락·Identity Sequence 상태를 조회한다. 원문·작성자 이름·AI 요약·Credential 전체를 출력하지 않는다. Sequence를 조회할 뿐 nextval/setval을 호출하거나 Job 상태를 초기화하지 않는다.

행 전체를 UTC·고정 DateStyle·C 정렬로 JSON 직렬화한 뒤 [PostgreSQL 기본 SHA-256](https://www.postgresql.org/docs/17/functions-binarystring.html)을 계산한다. 같은 건수라도 본문·작성자·상태 등이 달라지면 Hash 비교로 차이를 찾을 수 있다. Hash는 내용 대조용이며 익명화나 개인정보 탐지의 증거가 아니다. 결과 파일은 Git 제외 local/deployment에만 보관한다.

CloudShell에서는 기존 CA 검증 접속 도구로 psql에 들어간 뒤 내용을 실행하거나, 검토한 SQL 파일을 직접 업로드해 다음처럼 실행한다. Password는 psql의 직접 입력창에만 넣고 History·명령 인자·문서에 저장하지 않는다.

```text
\i recovery-verification.sql
```

실행 후 일곱 Table의 건수·Hash, TLS 사용, Job 상태/예약, 참조 누락과 Migration·Sequence를 대조한다. 빈 Table의 같은 Hash는 빈 상태의 일치만 증명하며 비어 있지 않은 Suggestion 데이터의 복원 근거로 확대하지 않는다. 새 App이 연결한 뒤에도 Worker는 꺼 두고, Migration이 원래 검증 Image와 같은 범위인지 확인한다.

Source 보호 Test는 읽기 전용·출력 범위·조회 대상의 형태를 확인할 뿐, PostgreSQL에서 SQL이 실행됐거나 실제 복원이 성공했다는 증거가 아니다. 실제 SQL 결과와 Snapshot·RDS 상태는 실행 보고서에 별도로 남긴다.
