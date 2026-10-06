-- Preserve V1-V4, stored policy snapshots, receipts, reservations, and results.
ALTER TABLE ai_suggestion_jobs
    DROP CONSTRAINT ai_suggestion_jobs_request_kind_allowed,
    DROP CONSTRAINT ai_suggestion_jobs_failure_code_allowed,
    ADD CONSTRAINT ai_suggestion_jobs_request_kind_allowed
        CHECK (next_request_kind IN ('INITIAL', 'OUTPUT_REPAIR', 'TEMPORARY_RETRY', 'RECOVERY')),
    ADD CONSTRAINT ai_suggestion_jobs_failure_code_allowed
        CHECK (last_failure_code IN (
            'PROVIDER_REFUSED', 'PROVIDER_RATE_LIMITED', 'PROVIDER_OUTCOME_UNKNOWN',
            'OUTPUT_INVALID', 'OUTPUT_REQUIRED_FIELD_MISSING', 'OUTPUT_REPAIR_LIMIT_EXHAUSTED',
            'GENERATION_LIMIT_EXHAUSTED', 'JOB_PROCESSING_DEADLINE_EXCEEDED',
            'ADAPTER_CONFIGURATION_ERROR'));

ALTER TABLE ai_suggestion_attempts
    DROP CONSTRAINT ai_suggestion_attempts_request_kind_check,
    ADD CONSTRAINT ai_suggestion_attempts_request_kind_check
        CHECK (request_kind IN ('INITIAL', 'OUTPUT_REPAIR', 'TEMPORARY_RETRY', 'RECOVERY'));
