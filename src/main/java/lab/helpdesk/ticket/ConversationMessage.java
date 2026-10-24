package lab.helpdesk.ticket;

import java.time.Instant;

/** Stored projection; HTTP DTOs exclude the internal assignment version and author identity. */
public record ConversationMessage(long id, MessageType type, String body,
        Instant createdAt, Long correctsMessageId, Long replyAssignmentVersion) { }
