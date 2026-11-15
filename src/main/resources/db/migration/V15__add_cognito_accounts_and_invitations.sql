CREATE TABLE helpdesk_cognito_accounts (
    user_id BIGINT PRIMARY KEY REFERENCES helpdesk_user_identities(id),
    issuer VARCHAR(255) NOT NULL CHECK (issuer <> ''),
    subject VARCHAR(128) NOT NULL CHECK (subject <> ''),
    first_administrator BOOLEAN NOT NULL DEFAULT FALSE,
    bound_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (issuer, subject)
);

CREATE UNIQUE INDEX cognito_single_first_administrator
    ON helpdesk_cognito_accounts(first_administrator) WHERE first_administrator;

CREATE TABLE helpdesk_account_invitations (
    token_hash CHAR(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    email VARCHAR(254) NOT NULL CHECK (email <> ''),
    service_id BIGINT REFERENCES support_services(id),
    support_role TEXT CHECK (support_role IN ('AGENT', 'SUPERVISOR')),
    display_name VARCHAR(120),
    issued_by BIGINT NOT NULL REFERENCES helpdesk_cognito_accounts(user_id),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    accepted_by BIGINT REFERENCES helpdesk_cognito_accounts(user_id),
    CHECK (expires_at > issued_at),
    CHECK (
        (service_id IS NULL AND support_role IS NULL AND display_name IS NULL)
        OR (service_id IS NOT NULL AND support_role IS NOT NULL
            AND display_name IS NOT NULL AND btrim(display_name) <> '')
    ),
    CHECK ((accepted_at IS NULL) = (accepted_by IS NULL)),
    CHECK (accepted_at IS NULL OR (revoked_at IS NULL AND accepted_at < expires_at))
);

CREATE UNIQUE INDEX cognito_one_pending_invitation_per_email
    ON helpdesk_account_invitations(email) WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX cognito_invitation_service_idx ON helpdesk_account_invitations(service_id);
CREATE INDEX cognito_invitation_issuer_idx ON helpdesk_account_invitations(issued_by);
CREATE INDEX cognito_invitation_recipient_idx ON helpdesk_account_invitations(accepted_by);
