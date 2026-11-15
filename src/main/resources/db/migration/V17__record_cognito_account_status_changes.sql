CREATE TABLE helpdesk_account_status_events (
    target_user_id BIGINT NOT NULL REFERENCES helpdesk_cognito_accounts(user_id),
    administrator_user_id BIGINT NOT NULL REFERENCES helpdesk_cognito_accounts(user_id),
    from_status TEXT NOT NULL CHECK (from_status IN ('ACTIVE', 'DISABLED')),
    to_status TEXT NOT NULL CHECK (to_status IN ('ACTIVE', 'DISABLED')),
    session_version BIGINT NOT NULL CHECK (session_version > 0),
    reason TEXT NOT NULL CHECK (reason IN (
        'OWNER_REQUEST', 'SERVICE_ACCESS_ENDED', 'SECURITY_REVIEW', 'ADMINISTRATIVE_REVIEW'
    )),
    changed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (target_user_id, session_version),
    CHECK (from_status <> to_status),
    CHECK (target_user_id <> administrator_user_id)
);

CREATE INDEX helpdesk_account_status_administrator_idx
    ON helpdesk_account_status_events(administrator_user_id);
