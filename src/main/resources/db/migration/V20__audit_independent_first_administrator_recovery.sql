ALTER TABLE helpdesk_account_recoveries
    ALTER COLUMN administrator_user_id DROP NOT NULL,
    ADD COLUMN aws_requested_by TEXT,
    ADD COLUMN aws_approved_by TEXT,
    DROP CONSTRAINT recovery_completion_requires_approval,
    ADD CONSTRAINT recovery_requires_exactly_one_requester
        CHECK ((administrator_user_id IS NULL) <> (aws_requested_by IS NULL)),
    ADD CONSTRAINT aws_recovery_is_totp_only
        CHECK (aws_requested_by IS NULL OR (kind = 'TOTP_RESET' AND btrim(aws_requested_by) <> '')),
    ADD CONSTRAINT recovery_completion_requires_approval
        CHECK ((completed_at IS NULL AND approved_by IS NULL AND aws_approved_by IS NULL) OR
            (completed_at IS NOT NULL AND proof_auth_time IS NOT NULL AND
                ((administrator_user_id IS NOT NULL AND approved_by IS NOT NULL AND aws_approved_by IS NULL) OR
                 (aws_requested_by IS NOT NULL AND approved_by IS NULL AND aws_approved_by IS NOT NULL
                     AND btrim(aws_approved_by) <> ''))));

CREATE UNIQUE INDEX helpdesk_aws_recovery_request_once
    ON helpdesk_account_recoveries(target_user_id, client_request_id) WHERE aws_requested_by IS NOT NULL;
