-- Synthetic storage/read-compatibility data, NOT observed scanner/Provider outcomes.
-- Execute only in the approved helpdesk learning DB after B migrated it to V8.
-- Worker and Provider must remain disabled. Existing business rows are not changed.
-- A second execution is rejected; keep the first fixture for the P read-back check.
BEGIN READ WRITE;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '2s';

DO $fixture$
DECLARE
    probe_prefix CONSTANT TEXT := '[SYNTHETIC B-P 20261016] ';
    failure_code TEXT;
    ticket_key BIGINT;
    message_key BIGINT;
    job_key BIGINT;
    recorded_at TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF current_database() <> 'helpdesk'
       OR (SELECT MAX(version::INTEGER) FROM public.flyway_schema_history WHERE success) <> 8
       OR (SELECT COUNT(*) FROM public.flyway_schema_history WHERE version = '8' AND success) <> 1 THEN
        RAISE EXCEPTION 'ROLLBACK_FIXTURE_V8_DATABASE_REQUIRED';
    END IF;
    IF EXISTS (SELECT 1 FROM public.tickets
               WHERE left(title, length(probe_prefix)) = probe_prefix) THEN
        RAISE EXCEPTION 'ROLLBACK_FIXTURE_ALREADY_EXISTS';
    END IF;

    FOREACH failure_code IN ARRAY ARRAY['PRIVACY_SCAN_TIMEOUT', 'PRIVACY_SCAN_INVALID_RESULT'] LOOP
        INSERT INTO public.tickets (title, status)
        VALUES (probe_prefix || failure_code, 'OPEN')
        RETURNING id INTO ticket_key;

        INSERT INTO public.ticket_messages (ticket_id, body, author_username)
        VALUES (ticket_key,
            'Synthetic SQL compatibility fixture, not a customer message or a real scanner/AI execution. '
                || failure_code,
            'synthetic-sql-rollback-probe')
        RETURNING id INTO message_key;

        -- This reservation is part of the labelled fixture, not an actual API usage record.
        INSERT INTO public.ai_suggestion_jobs (
            input_message_id, status, policy_version, policy_snapshot_source,
            current_attempt, reserved_generation_count, first_started_at,
            processing_deadline_at, last_failure_code, finished_at)
        VALUES (message_key, 'FAILED', 'rollback-fixture-v1', 'DATABASE_DEFAULT',
            1, 1, recorded_at, recorded_at + INTERVAL '5 minutes', failure_code, recorded_at)
        RETURNING id INTO job_key;

        INSERT INTO public.ai_suggestion_attempts (job_id, attempt_number, request_kind, result_code)
        VALUES (job_key, 1, 'INITIAL', 'AUTO_RETRY_BLOCKED');
    END LOOP;
END
$fixture$;
COMMIT;

-- Report only the labelled rows. No message body, username, credential or raw AI output.
SELECT t.id AS ticket_id, t.status AS ticket_status, j.id AS job_id,
       j.status AS job_status, j.last_failure_code, j.current_attempt,
       j.reserved_generation_count, a.result_code,
       (SELECT COUNT(*) FROM public.ticket_suggestions s WHERE s.job_id = j.id) AS suggestions
FROM public.tickets t
JOIN public.ticket_messages m ON m.ticket_id = t.id
JOIN public.ai_suggestion_jobs j ON j.input_message_id = m.id
JOIN public.ai_suggestion_attempts a ON a.job_id = j.id AND a.attempt_number = 1
WHERE t.title IN (
    '[SYNTHETIC B-P 20261016] PRIVACY_SCAN_TIMEOUT',
    '[SYNTHETIC B-P 20261016] PRIVACY_SCAN_INVALID_RESULT')
ORDER BY t.id;
