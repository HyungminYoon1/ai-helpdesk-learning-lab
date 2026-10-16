-- Execute unchanged on the source before the snapshot and on the restored DB.
-- Output contains counts, hashes and fixed states, not raw message/author values.
-- This script does not start a Worker or change a Job/reservation/sequence.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '2s';
SET LOCAL TIME ZONE 'UTC';
SET LOCAL DateStyle = 'ISO, YMD';

SELECT current_setting('transaction_read_only') AS transaction_read_only,
       current_setting('transaction_isolation') AS transaction_isolation;

SELECT ssl, version AS tls_version
FROM pg_stat_ssl
WHERE pid = pg_backend_pid();

WITH table_fingerprints AS (
    SELECT 'tickets' AS table_name,
           COUNT(*) AS row_count,
           encode(sha256(convert_to(COALESCE(
               jsonb_agg(to_jsonb(row_data) ORDER BY (to_jsonb(row_data)::text) COLLATE "C"),
               '[]'::jsonb)::text, 'UTF8')), 'hex') AS content_sha256
    FROM public.tickets AS row_data
    UNION ALL
    SELECT 'ticket_messages' AS table_name,
           COUNT(*) AS row_count,
           encode(sha256(convert_to(COALESCE(
               jsonb_agg(to_jsonb(row_data) ORDER BY (to_jsonb(row_data)::text) COLLATE "C"),
               '[]'::jsonb)::text, 'UTF8')), 'hex') AS content_sha256
    FROM public.ticket_messages AS row_data
    UNION ALL
    SELECT 'ai_suggestion_jobs' AS table_name,
           COUNT(*) AS row_count,
           encode(sha256(convert_to(COALESCE(
               jsonb_agg(to_jsonb(row_data) ORDER BY (to_jsonb(row_data)::text) COLLATE "C"),
               '[]'::jsonb)::text, 'UTF8')), 'hex') AS content_sha256
    FROM public.ai_suggestion_jobs AS row_data
    UNION ALL
    SELECT 'ai_suggestion_attempts' AS table_name,
           COUNT(*) AS row_count,
           encode(sha256(convert_to(COALESCE(
               jsonb_agg(to_jsonb(row_data) ORDER BY (to_jsonb(row_data)::text) COLLATE "C"),
               '[]'::jsonb)::text, 'UTF8')), 'hex') AS content_sha256
    FROM public.ai_suggestion_attempts AS row_data
    UNION ALL
    SELECT 'ticket_suggestions' AS table_name,
           COUNT(*) AS row_count,
           encode(sha256(convert_to(COALESCE(
               jsonb_agg(to_jsonb(row_data) ORDER BY (to_jsonb(row_data)::text) COLLATE "C"),
               '[]'::jsonb)::text, 'UTF8')), 'hex') AS content_sha256
    FROM public.ticket_suggestions AS row_data
    UNION ALL
    SELECT 'ticket_suggestion_categories' AS table_name,
           COUNT(*) AS row_count,
           encode(sha256(convert_to(COALESCE(
               jsonb_agg(to_jsonb(row_data) ORDER BY (to_jsonb(row_data)::text) COLLATE "C"),
               '[]'::jsonb)::text, 'UTF8')), 'hex') AS content_sha256
    FROM public.ticket_suggestion_categories AS row_data
    UNION ALL
    SELECT 'flyway_schema_history' AS table_name,
           COUNT(*) AS row_count,
           encode(sha256(convert_to(COALESCE(
               jsonb_agg(to_jsonb(row_data) ORDER BY (to_jsonb(row_data)::text) COLLATE "C"),
               '[]'::jsonb)::text, 'UTF8')), 'hex') AS content_sha256
    FROM public.flyway_schema_history AS row_data
)
SELECT table_name, row_count, content_sha256
FROM table_fingerprints
ORDER BY table_name;

SELECT COUNT(*) AS message_count,
       COUNT(*) FILTER (WHERE NULLIF(btrim(body), '') IS NOT NULL) AS with_body,
       COUNT(*) FILTER (WHERE NULLIF(btrim(author_username), '') IS NOT NULL) AS with_author
FROM public.ticket_messages;

SELECT status, current_attempt, reserved_generation_count,
       reserved_output_repair_count, COUNT(*) AS job_count
FROM public.ai_suggestion_jobs
GROUP BY status, current_attempt, reserved_generation_count, reserved_output_repair_count
ORDER BY status, current_attempt, reserved_generation_count, reserved_output_repair_count;

SELECT
    (SELECT COUNT(*) FROM public.ticket_messages m
     LEFT JOIN public.tickets t ON t.id = m.ticket_id WHERE t.id IS NULL) AS orphan_messages,
    (SELECT COUNT(*) FROM public.ai_suggestion_jobs j
     LEFT JOIN public.ticket_messages m ON m.id = j.input_message_id WHERE m.id IS NULL) AS orphan_jobs,
    (SELECT COUNT(*) FROM public.ai_suggestion_attempts a
     LEFT JOIN public.ai_suggestion_jobs j ON j.id = a.job_id WHERE j.id IS NULL) AS orphan_attempts,
    (SELECT COUNT(*) FROM public.ticket_suggestions s
     LEFT JOIN public.ai_suggestion_jobs j ON j.id = s.job_id WHERE j.id IS NULL) AS orphan_suggestions,
    (SELECT COUNT(*) FROM public.ticket_suggestion_categories c
     LEFT JOIN public.ticket_suggestions s ON s.id = c.suggestion_id WHERE s.id IS NULL) AS orphan_categories;

SELECT version, success
FROM public.flyway_schema_history
ORDER BY installed_rank;

SELECT 'tickets_id_seq' AS sequence_name, last_value, is_called FROM public.tickets_id_seq
UNION ALL
SELECT 'ticket_messages_id_seq', last_value, is_called FROM public.ticket_messages_id_seq
UNION ALL
SELECT 'ticket_suggestions_id_seq', last_value, is_called FROM public.ticket_suggestions_id_seq
ORDER BY sequence_name;

COMMIT;
