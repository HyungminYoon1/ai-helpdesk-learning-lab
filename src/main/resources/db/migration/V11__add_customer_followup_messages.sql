-- Preserve all V1-V10 data and the shared message submission key.
ALTER TABLE ticket_messages DROP CONSTRAINT message_kind_fields;
ALTER TABLE ticket_messages ADD CONSTRAINT message_kind_fields CHECK (
    (message_type = 'INQUIRY' AND client_request_id IS NULL AND corrects_message_id IS NULL)
    OR (message_type = 'OFFICIAL_REPLY' AND author_user_id IS NOT NULL AND client_request_id IS NOT NULL)
    OR (message_type = 'CUSTOMER_FOLLOWUP' AND author_user_id IS NOT NULL
        AND client_request_id IS NOT NULL AND corrects_message_id IS NULL)
);

ALTER TABLE ticket_messages DROP CONSTRAINT inquiry_without_reply_assignment_version;
ALTER TABLE ticket_messages ADD CONSTRAINT customer_message_without_reply_assignment_version CHECK (
    message_type NOT IN ('INQUIRY', 'CUSTOMER_FOLLOWUP') OR reply_assignment_version IS NULL
);
