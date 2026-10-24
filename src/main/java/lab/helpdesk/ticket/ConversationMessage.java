package lab.helpdesk.ticket;

import java.time.Instant;

/** Public conversation fields only; internal author identity is intentionally excluded. */
public record ConversationMessage(long id, MessageType type, String body,
        Instant createdAt, Long correctsMessageId, Long replyAssignmentVersion) { }
