-- Existing tickets have no submission key to recover.
ALTER TABLE tickets
    ADD COLUMN receipt_request_id UUID,
    ADD CONSTRAINT ticket_receipt_requires_owner_and_service CHECK (
        receipt_request_id IS NULL OR (requester_user_id IS NOT NULL AND service_id IS NOT NULL)
    );

CREATE UNIQUE INDEX ticket_receipt_request_unique
    ON tickets (requester_user_id, receipt_request_id)
    WHERE receipt_request_id IS NOT NULL;
