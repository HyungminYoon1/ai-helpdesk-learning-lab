-- Extend the failure vocabulary without rewriting original messages, job policies,
-- reservations, or suggestions. Keep the checksums and behavior of V1-V7 intact.
ALTER TABLE ai_suggestion_jobs
    DROP CONSTRAINT ai_suggestion_jobs_failure_code_allowed,
    ADD CONSTRAINT ai_suggestion_jobs_failure_code_allowed
        CHECK (last_failure_code IN (
            'PROVIDER_REFUSED', 'PROVIDER_RATE_LIMITED', 'PROVIDER_OUTCOME_UNKNOWN',
            'OUTPUT_INVALID', 'OUTPUT_REQUIRED_FIELD_MISSING', 'OUTPUT_REPAIR_LIMIT_EXHAUSTED',
            'GENERATION_LIMIT_EXHAUSTED', 'JOB_PROCESSING_DEADLINE_EXCEEDED',
            'RESULT_STORAGE_RETRY_EXHAUSTED', 'ADAPTER_CONFIGURATION_ERROR',
            'PRIVACY_SCAN_TIMEOUT', 'PRIVACY_SCAN_INVALID_RESULT'));
