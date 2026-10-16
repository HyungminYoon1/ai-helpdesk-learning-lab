-- Isolated Testcontainer only. Do not put the generated password in SQL or command arguments.
\getenv prepare_app_password HELPDESK_ROLLBACK_APP_PASSWORD
CREATE ROLE rollback_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS PASSWORD :'prepare_app_password';
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE rollback_compat TO rollback_app;
GRANT USAGE, CREATE ON SCHEMA public TO rollback_app;
