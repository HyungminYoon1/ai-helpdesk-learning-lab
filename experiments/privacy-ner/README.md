# 한국어 개인정보 탐지의 로컬 합성 실험

운영 검사기나 HTTP 서비스가 아니다. 합성 문의 12건만 사용해 한국어 NER와 제한적인 이메일·한국 휴대전화 규칙의 탐지 누락·과도한 마스킹·실행 자원을 확인한다. 인명은 예시 이름, 이메일은 `.invalid`, 전화번호는 합성 값이다. 원문·개체 문자열·Credential을 결과 Log에 출력하지 않는다. 기대값은 모델 입력과 분리하며 모델에는 각 Case의 제목·본문만 전달한다.

후속 [KoELECTRA 후보 비교](./koelectra/README.md)도 같은 12건을 사용한다. spaCy 실험 Source·기대값은 유지하며, 두 실행을 운영 검사기나 독립적인 최종 평가로 취급하지 않는다.

## 실행과 해석

Repository Root에서 실행한다. Build Context는 이 디렉터리 한 개이며 App Source·환경 파일·Secret·Docker Socket을 복사하거나 Mount하지 않는다.

```powershell
docker build --platform linux/amd64 -t helpdesk-privacy-ner-probe:local experiments/privacy-ner
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges --pids-limit 64 --cpus 0.25 --memory 512m --memory-swap 512m helpdesk-privacy-ner-probe:local
```

Container는 비특권 사용자로 실행한다. 실행 중 외부 통신을 막으며 전체 실험의 제한 시간은 60초, 종료 유예는 5초다. 이는 Job별 Timeout·취소·자원 회수를 구현했다는 뜻이 아니다. App과 JVM을 함께 실행하지 않으므로 단독 실험의 메모리 통과를 같은 크기의 ECS Task 통과로 해석하지 않는다.

`PS`만 승인된 `PERSON`으로 옮긴다. 일반 NER의 날짜·시각·수량·기관·장소 Label을 일괄 마스킹하지 않는다. 주소·생년월일·비밀번호·비표준 연락처 전부를 탐지하는 정책은 아니다. EMAIL·PHONE 규칙도 제한적이며 P12는 비표준 이메일을 포함해 누락을 확인하도록 만든 Case다. 입력에 맞춰 기대값을 바꾸거나 모델 결과를 정답으로 채택하지 않는다.

Python의 문자열 위치는 Unicode Code Point 기준이다. Java 연결 시 기존 JSON·Code Point·범위·중복/겹침 검증을 그대로 적용해야 한다. 이 실험의 Python 치환 결과만으로 Java Provider의 실제 전송이나 DB 보존까지 확인한 것은 아니다. 충돌 범위는 합치거나 버리지 않고 실패한다.

출력은 Case ID·위치·종류·집계·자원 지표다. `missed`와 `extra`는 정확한 위치·종류를 기준으로 계산하므로, 이름 뒤 조사를 함께 잡으면 정확한 이름 범위의 누락과 추가 범위가 각각 집계될 수 있다. 보호 성공과 단어 경계 정확도도 함께 해석해야 한다. `pass`는 이 고정 합성 Case의 결과이지 실제 고객 문의 전반의 정확도가 아니다.

## 모델과 라이선스 출처

- 모델: Explosion의 `ko_core_news_sm` 3.8.0. 원본 Weight를 수정하거나 재학습하지 않는다.
- 출처: <https://github.com/explosion/spacy-models/releases/tag/ko_core_news_sm-3.8.0>
- 모델 라이선스: CC BY-SA 4.0. 원본 Wheel에 포함된 License·출처 표기를 유지한다. <https://creativecommons.org/licenses/by-sa/4.0/>
- 라이브러리: spaCy 3.8.16, MIT. 모델 라이선스와 같은 것으로 취급하지 않는다.
- 원본 Wheel SHA-256: `15486b93a22ec8ee43f787390afdb4e2086851573eb12b69dd2c4f2a95b3e0a4`.

SHA-256은 개발사 HTTPS Release에서 받은 Wheel을 계산해 고정한 값이다. 개발사 API의 해당 Asset에는 Digest가 없어 별도의 서명 검증을 완료했다고 주장하지 않는다. Python Base Image는 Dockerfile의 Digest로 고정하며 직접 의존성 Version·모델 Hash를 고정했다. 전이 의존성은 결과의 설치 Version 목록과 실험 Image ID로 식별하며, 배포 전 전체 Lock과 공급망 검토는 별도 단계다.
