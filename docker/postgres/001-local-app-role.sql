-- 빈 데이터 디렉터리의 최초 초기화에서만 실행한다.
-- Password를 SQL 파일이나 명령 인자에 적지 않고 실행 환경에서 읽는다.
\getenv helpdesk_app_password HELPDESK_DB_APP_PASSWORD

CREATE ROLE helpdesk_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    PASSWORD :'helpdesk_app_password';
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE helpdesk_local TO helpdesk_app;
-- 이 학습 환경은 App이 Flyway를 실행하므로 Schema 생성 권한도 필요하다.
GRANT USAGE, CREATE ON SCHEMA public TO helpdesk_app;
