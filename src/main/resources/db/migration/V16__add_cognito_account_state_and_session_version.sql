ALTER TABLE helpdesk_cognito_accounts
    ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0 CHECK (session_version >= 0),
    ADD COLUMN reactivated_at TIMESTAMPTZ,
    ADD CONSTRAINT cognito_disabled_account_has_new_session_version
        CHECK (status = 'ACTIVE' OR session_version > 0),
    ADD CONSTRAINT cognito_reactivation_requires_fresh_login
        CHECK ((status = 'ACTIVE' AND session_version > 0) = (reactivated_at IS NOT NULL));
