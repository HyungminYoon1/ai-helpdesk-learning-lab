ALTER TABLE helpdesk_account_recoveries
    ADD COLUMN provider_attempt_id UUID,
    ADD COLUMN provider_started_at TIMESTAMPTZ,
    ADD COLUMN provider_mutation_at TIMESTAMPTZ,
    ADD COLUMN provider_ready_at TIMESTAMPTZ,
    ADD COLUMN proof_auth_time TIMESTAMPTZ,
    ADD COLUMN approved_by BIGINT REFERENCES helpdesk_cognito_accounts(user_id),
    ADD CONSTRAINT recovery_attempt_requires_time
        CHECK ((provider_attempt_id IS NULL) = (provider_started_at IS NULL)),
    ADD CONSTRAINT recovery_mutation_requires_attempt
        CHECK (provider_mutation_at IS NULL OR
            (provider_started_at IS NOT NULL AND provider_mutation_at >= provider_started_at)),
    ADD CONSTRAINT recovery_ready_requires_mutation
        CHECK (provider_ready_at IS NULL OR
            (provider_mutation_at IS NOT NULL AND provider_ready_at >= provider_mutation_at)),
    ADD CONSTRAINT recovery_proof_requires_new_authentication
        CHECK (proof_auth_time IS NULL OR (provider_ready_at IS NOT NULL AND proof_auth_time > provider_ready_at)),
    ADD CONSTRAINT recovery_completion_requires_approval
        CHECK ((completed_at IS NULL) = (approved_by IS NULL) AND (completed_at IS NULL OR proof_auth_time IS NOT NULL));

CREATE INDEX helpdesk_recovery_approved_by_idx ON helpdesk_account_recoveries(approved_by);
