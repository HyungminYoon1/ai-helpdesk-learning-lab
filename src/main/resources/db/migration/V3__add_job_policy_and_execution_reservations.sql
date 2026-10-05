-- V1/V2와 문의 원문은 유지한다. 기존 PENDING Job에는 승인한 초기 정책을 한 번 부여한다.
ALTER TABLE ai_suggestion_jobs
    DROP CONSTRAINT ai_suggestion_jobs_pending_only,
    ADD COLUMN policy_version VARCHAR(64) NOT NULL DEFAULT 'job-policy-v1',
    ADD COLUMN policy_snapshot_source VARCHAR(32) NOT NULL DEFAULT 'V2_MIGRATION',
    ADD COLUMN max_generation_attempts INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN max_output_repair_attempts INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN request_timeout_ms BIGINT NOT NULL DEFAULT 60000,
    ADD COLUMN attempt_lease_ms BIGINT NOT NULL DEFAULT 120000,
    ADD COLUMN retry_backoff_ms BIGINT NOT NULL DEFAULT 5000,
    ADD COLUMN job_processing_timeout_ms BIGINT NOT NULL DEFAULT 300000,
    ADD COLUMN current_attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reserved_generation_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reserved_output_repair_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN first_started_at TIMESTAMPTZ,
    ADD COLUMN processing_deadline_at TIMESTAMPTZ,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN next_request_kind VARCHAR(32) NOT NULL DEFAULT 'INITIAL',
    ADD COLUMN last_failure_code VARCHAR(64),
    ADD COLUMN finished_at TIMESTAMPTZ,
    ADD CONSTRAINT ai_suggestion_jobs_status_allowed
        CHECK (status IN ('PENDING', 'RUNNING', 'FAILED')),
    ADD CONSTRAINT ai_suggestion_jobs_policy_version_valid
        CHECK (policy_version ~ '^[A-Za-z0-9._-]{1,64}$'),
    ADD CONSTRAINT ai_suggestion_jobs_policy_source_allowed
        CHECK (policy_snapshot_source IN ('V2_MIGRATION', 'APPLICATION', 'DATABASE_DEFAULT')),
    ADD CONSTRAINT ai_suggestion_jobs_policy_limits_valid
        CHECK (max_generation_attempts > 0 AND max_output_repair_attempts >= 0
            AND request_timeout_ms > 0 AND attempt_lease_ms > request_timeout_ms
            AND retry_backoff_ms > 0 AND job_processing_timeout_ms > 0),
    ADD CONSTRAINT ai_suggestion_jobs_counts_valid
        CHECK (current_attempt >= 0
            AND reserved_generation_count BETWEEN 0 AND max_generation_attempts
            AND reserved_output_repair_count BETWEEN 0 AND max_output_repair_attempts
            AND reserved_output_repair_count <= reserved_generation_count
            AND current_attempt = reserved_generation_count),
    ADD CONSTRAINT ai_suggestion_jobs_request_kind_allowed
        CHECK (next_request_kind IN ('INITIAL', 'OUTPUT_REPAIR', 'RECOVERY')),
    ADD CONSTRAINT ai_suggestion_jobs_processing_times_valid
        CHECK ((first_started_at IS NULL AND processing_deadline_at IS NULL
                    AND current_attempt = 0)
            OR (first_started_at IS NOT NULL AND processing_deadline_at IS NOT NULL
                    AND processing_deadline_at > first_started_at AND current_attempt > 0)),
    ADD CONSTRAINT ai_suggestion_jobs_lease_valid
        CHECK ((status = 'RUNNING' AND lease_expires_at IS NOT NULL
                    AND first_started_at IS NOT NULL
                    AND lease_expires_at <= processing_deadline_at)
            OR (status <> 'RUNNING' AND lease_expires_at IS NULL)),
    ADD CONSTRAINT ai_suggestion_jobs_finished_valid
        CHECK ((status = 'FAILED' AND finished_at IS NOT NULL AND last_failure_code IS NOT NULL)
            OR (status <> 'FAILED' AND finished_at IS NULL)),
    ADD CONSTRAINT ai_suggestion_jobs_failure_code_allowed
        CHECK (last_failure_code IN (
            'PROVIDER_REFUSED', 'PROVIDER_OUTCOME_UNKNOWN', 'OUTPUT_INVALID',
            'OUTPUT_REQUIRED_FIELD_MISSING', 'OUTPUT_REPAIR_LIMIT_EXHAUSTED',
            'GENERATION_LIMIT_EXHAUSTED', 'JOB_PROCESSING_DEADLINE_EXCEEDED',
            'ADAPTER_CONFIGURATION_ERROR'));

-- Migration으로 값을 채운 기존 Job과 Application에서 새로 등록한 Job을 구분한다.
ALTER TABLE ai_suggestion_jobs
    ALTER COLUMN policy_snapshot_source SET DEFAULT 'DATABASE_DEFAULT';

CREATE TABLE ai_suggestion_attempts (
    job_id BIGINT NOT NULL REFERENCES ai_suggestion_jobs (id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    request_kind VARCHAR(32) NOT NULL
        CHECK (request_kind IN ('INITIAL', 'OUTPUT_REPAIR', 'RECOVERY')),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (job_id, attempt_number)
);

CREATE INDEX ai_suggestion_jobs_unfinished_poll_idx
    ON ai_suggestion_jobs (created_at, id)
    WHERE status IN ('PENDING', 'RUNNING');
