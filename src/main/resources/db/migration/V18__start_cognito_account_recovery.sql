ALTER TABLE helpdesk_cognito_accounts
    DROP CONSTRAINT helpdesk_cognito_accounts_status_check,
    ADD CONSTRAINT helpdesk_cognito_accounts_status_check
        CHECK (status IN ('ACTIVE', 'DISABLED', 'RECOVERY_PENDING'));

CREATE TABLE helpdesk_account_recoveries (
    id UUID PRIMARY KEY,
    target_user_id BIGINT NOT NULL REFERENCES helpdesk_cognito_accounts(user_id),
    administrator_user_id BIGINT NOT NULL REFERENCES helpdesk_cognito_accounts(user_id),
    client_request_id UUID NOT NULL,
    previous_status TEXT NOT NULL CHECK (previous_status IN ('ACTIVE', 'DISABLED')),
    session_version BIGINT NOT NULL CHECK (session_version > 0),
    kind TEXT NOT NULL CHECK (kind IN ('PASSWORD_RESET', 'TOTP_RESET')),
    reason TEXT NOT NULL CHECK (reason IN (
        'OWNER_REQUEST', 'SERVICE_ACCESS_ENDED', 'SECURITY_REVIEW', 'ADMINISTRATIVE_REVIEW'
    )),
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (target_user_id, administrator_user_id, client_request_id),
    UNIQUE (target_user_id, session_version),
    CHECK (target_user_id <> administrator_user_id),
    CHECK (completed_at IS NULL OR completed_at >= requested_at)
);

CREATE UNIQUE INDEX helpdesk_one_pending_account_recovery
    ON helpdesk_account_recoveries(target_user_id) WHERE completed_at IS NULL;
CREATE INDEX helpdesk_account_recovery_administrator_idx
    ON helpdesk_account_recoveries(administrator_user_id);
