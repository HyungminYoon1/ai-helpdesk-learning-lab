-- Keep V1-V5, receipts, policy snapshots, reservations, and stored results unchanged.
ALTER TABLE ai_suggestion_jobs
    DROP CONSTRAINT ai_suggestion_jobs_failure_code_allowed,
    ADD CONSTRAINT ai_suggestion_jobs_failure_code_allowed
        CHECK (last_failure_code IN (
            'PROVIDER_REFUSED', 'PROVIDER_RATE_LIMITED', 'PROVIDER_OUTCOME_UNKNOWN',
            'OUTPUT_INVALID', 'OUTPUT_REQUIRED_FIELD_MISSING', 'OUTPUT_REPAIR_LIMIT_EXHAUSTED',
            'GENERATION_LIMIT_EXHAUSTED', 'JOB_PROCESSING_DEADLINE_EXCEEDED',
            'RESULT_STORAGE_RETRY_EXHAUSTED', 'ADAPTER_CONFIGURATION_ERROR'));
