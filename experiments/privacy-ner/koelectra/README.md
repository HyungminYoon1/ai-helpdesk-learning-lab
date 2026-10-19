# KoELECTRA 개인정보 탐지 후보 비교

기존 spaCy 실험의 합성 12건·정답·보존 문구를 바꾸지 않고 작은 한국어 개인정보 NER를 비교한다. 운영 검사기 채택이나 Java Main 연결이 아니다. 같은 Dataset을 이미 확인한 뒤 고른 후보이므로 독립적인 최종 평가 Set이라고 부르지 않는다.

## 실행

Repository Root에서 실행한다. Build Context는 상위 `experiments/privacy-ner`이며 허용 목록의 실험 Source와 합성 Dataset만 복사한다. App Source·Secret·Docker Socket·Host 환경을 전달하지 않는다. Build 단계에서 공개 라이브러리와 고정 모델을 내려받고, 추론 단계에서는 Network를 차단한다.

```powershell
python -m unittest discover -s experiments/privacy-ner/koelectra -p "test_*.py" -q
docker build --platform linux/amd64 -t helpdesk-privacy-koelectra-probe:local -f experiments/privacy-ner/koelectra/Dockerfile experiments/privacy-ner
docker run --rm --network none --read-only --cap-drop ALL --security-opt no-new-privileges --pids-limit 64 --cpus 0.25 --memory 512m --memory-swap 512m --tmpfs /tmp:rw,noexec,nosuid,size=16m helpdesk-privacy-koelectra-probe:local
```

Container는 비Root 사용자로 실행한다. 실험 전체의 상한은 180초, 종료 유예는 5초이며 운영 Job별 Timeout은 아니다.

라이브러리가 OS 사용자 이름을 조회하므로 Image에 비로그인 전용 `privacy-probe` 사용자·Group도 만든다. UID만 지정한 첫 실행은 초기화 중 실패했다. 사용자 이름을 등록한 뒤에도 UID/GID 10001·읽기 전용·권한 제한은 그대로 유지한다.

## 모델과 디코딩의 경계

`model-lock.json`의 Revision·SHA-256을 Build 시 대조한다. Model Card도 보존하고 `model.safetensors`만 읽는다. `pytorch_model.bin`·학습 때 사용한 Pickle·모델 Repository의 Python Code는 받지 않는다. 표준 Electra Class와 Fast Tokenizer를 로컬 파일만으로 사용하며 Remote Code나 실행 중 다운로드를 사용하지 않는다.

모델은 `PER-B`·`PER-I`처럼 끝에 BIO를 붙인 Label을 반환한다. `B-PER`를 전제로 한 집계에 맡기지 않고 별도 Decoder에서 위치와 Label 순서를 검사한다. 앞선 B가 없는 I·종류가 다른 I·잘못된 위치는 실패로 처리하며 범위를 임의로 복구하거나 빈 정상 결과로 바꾸지 않는다. 512 Token을 넘는 입력도 자르지 않고 거부한다.

비교 대상은 기존과 같은 `PERSON`·`EMAIL`·`PHONE`뿐이다. 제한적인 기존 Regex는 유지하고 모델의 `PER`·`EMA`·`PHN`을 추가한다. 완전히 일치한 중복만 제거하며 부분 중복은 기존 검증으로 거부한다. 다른 모델 Label은 건수를 표시하되 일괄 마스킹하지 않는다. 주소·인증 정보 등 모든 종류의 보호를 구현한 것은 아니다.

모델에는 제목·본문만 전달한다. 정답은 추론 뒤 평가할 때 참조하며 원문·탐지한 문자열·예외 전체를 결과 Log에 출력하지 않는다. 인물 관계나 모호한 일반명사 판정도 구현하지 않았다. `[PERSON_REDACTED]`로 비교했다는 이유만으로 관계 보존 정책을 충족했다고 보지 않는다.

## 출력과 종료 코드

결과에는 Case ID·건수·위치·종류·보존 판정·시간·메모리를 담는다. 실행 종료 코드 0은 실험을 마치고 보고할 수 있었다는 뜻이지, 12건 전체 합격이나 운영 채택을 뜻하지 않는다. 개별 `pass`·`blockedCases`·누락과 과잉 탐지를 확인한다. 거부된 Case는 완료 Scan의 위치 정확도 집계에 섞지 않고 실패 건수로 별도 기록한다.

Tokenizer/Decoder의 Unit Test는 고정 출력을 처리하는 검증이며 실제 모델의 탐지 정확도 평가는 아니다. 모델 단독의 메모리 측정도 JVM·Worker와 함께 실행하는 512 MiB 구성의 적합성을 보장하지 않는다. Windows의 Process Transport, Linux의 후보 모델, Java→PostgreSQL의 검증을 구분한다.

## 출처와 라이선스

- 모델: [amoeba04/koelectra-small-v3-privacy-ner](https://huggingface.co/amoeba04/koelectra-small-v3-privacy-ner/tree/9f4e2fd9e35b12bcdb5fc334ac31be4399cb4281). Model Card의 표시는 Apache-2.0이며 자체 합성 Dataset의 평가값을 이번 성적으로 인용하지 않는다.
- 基盤: [monologg/KoELECTRA](https://github.com/monologg/KoELECTRA)、Apache-2.0。
- [Safetensors 형식](https://huggingface.co/docs/safetensors/index). 안전한 파일 형식의 사용이 모델 정확도나 완전한 Sandbox를 보장하지는 않는다.

직접 의존성을 고정하고 실행 결과에 실제 전체 의존성 Version을 남긴다. 모델 Hash는 고정 Revision의 공개 HTTPS 파일과 Hub의 Weight SHA를 대조한 것이며 작성자의 별도 서명을 검증한 것은 아니다. 전체 의존성 Hash Lock과 배포할 때의 License/Notice 정리는 후보가 합격한 뒤의 별도 단계로 남긴다.
