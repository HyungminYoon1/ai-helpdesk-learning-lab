# 서울 RDS의 공개 CA 인증서

[ap-northeast-2-bundle.pem](./ap-northeast-2-bundle.pem)은 [AWS가 공개한 서울 Region의 RDS CA 묶음](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html#UsingWithRDS.SSL.RegionCertificateBundles)이다. DB Password·API Key·Private Key가 아닌 Root CA 인증서 3개를 포함한다. [Manifest](./bundle-manifest.json)에 공식 취득 위치·확인일·파일 SHA-256과 Image 경로를 기록한다.

## Image에 넣는 이유

App의 PostgreSQL JDBC Client는 접속한 RDS의 인증서를 검증해야 한다. Runtime Image에 `/app/certs/rds-ca-bundle.pem`을 제공하고 다음 설정으로 인증서 사슬과 Hostname을 확인한다. 실제 Endpoint·DB 이름은 실행 설정에서 채운다.

```text
jdbc:postgresql://<RDS_ENDPOINT>:5432/<DB_NAME>?sslmode=verify-full&sslrootcert=/app/certs/rds-ca-bundle.pem
```

`sslmode=require`는 암호화를 요구하지만 pgJDBC에서 서버 인증서 검증을 대신하지 않는다. 인증서 오류를 없애려고 `verify-full`을 낮추거나 `NonValidatingFactory`를 추가하지 않는다. Browser→ALB HTTPS 인증서와 App→RDS의 CA는 서로 다른 연결에서 사용한다. [pgJDBC SSL 안내](https://jdbc.postgresql.org/documentation/ssl/)

공개 CA는 Password와 달리 검토한 정적 파일로 Image에 넣을 수 있다. `.dockerignore`의 PEM·Key 제외 규칙은 유지하고 이 파일 하나만 정확한 경로로 허용했다. Runtime에는 Root 소유의 파일 `0444`·디렉터리 `0555`로 제공한다. App의 비Root 사용자 `10001:10001`은 읽을 수 있지만 인증서 파일을 수정할 소유 권한은 갖지 않는다. JVM 전체 Truststore는 바꾸지 않고 JDBC의 `sslrootcert`로만 참조한다.

Bundle을 변경하면 공식 취득 위치·CA 종류·유효 기간·SHA-256을 다시 확인하고 Manifest·Test·Image를 함께 갱신한다. App 시작 시 자동 다운로드하거나 실패한 인증서 검증을 우회하지 않는다. 공개 Root CA를 확인하는 것이 실제 RDS 서버 인증서 검증 완료를 뜻하지는 않는다.

## 로컬 확인

```powershell
node --test src/test/js/rds-public-ca-bundle.test.mjs src/test/js/ecs-task-definition-template.test.mjs
docker build --platform linux/amd64 --tag helpdesk:week8-rds-tls-preflight .
docker run --pull never --rm --network none --entrypoint sha256sum helpdesk:week8-rds-tls-preflight /app/certs/rds-ca-bundle.pem
docker run --pull never --rm --network none --entrypoint stat helpdesk:week8-rds-tls-preflight -c '%a %u:%g' /app/certs /app/certs/rds-ca-bundle.pem
```

예상 Mode·소유자는 순서대로 `555 0:0`, `444 0:0`이다. 두 임시 Container는 Network 없이 공개 파일을 확인하고 제거하며 Helpdesk JVM이나 DB를 시작하지 않는다. 파일 Hash는 Manifest와 대조한다. 처음 Build에서는 새 부모 디렉터리가 `444`여서 실제 비Root 읽기가 실패했고, 디렉터리 통과 권한을 보완한 재Build에서 통과했다.

이 단계는 공개 CA의 형식·파일 전달·읽기 권한과 실행 설정을 준비하는 것이다. 실제 RDS 연결에서는 선택한 CA, DB Endpoint의 Hostname, DB 계정·Security Group과 실제 TLS 사용을 별도로 확인한다. 기존 로컬 Compose의 비TLS DB 실험을 RDS TLS 근거로 사용하지 않는다.
