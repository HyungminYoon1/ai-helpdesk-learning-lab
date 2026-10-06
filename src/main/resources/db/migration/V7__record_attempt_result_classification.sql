-- A legacy reservation does not identify its observed result. Hold it conservatively.
-- This migration does not assert that a Provider rejection actually occurred.
ALTER TABLE ai_suggestion_attempts
    ADD COLUMN result_code VARCHAR(32) NOT NULL DEFAULT 'AUTO_RETRY_BLOCKED',
    ADD CONSTRAINT ai_suggestion_attempts_result_code_allowed
        CHECK (result_code IN ('UNCONFIRMED', 'OUTCOME_UNKNOWN', 'AUTO_RETRY_BLOCKED'));

-- New reservations start without a durable result observation.
ALTER TABLE ai_suggestion_attempts
    ALTER COLUMN result_code SET DEFAULT 'UNCONFIRMED';
