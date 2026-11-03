package lab.helpdesk.ticket;

import java.time.Instant;
import java.util.UUID;

/** Stored audit record. Its ID is not a conversation checkpoint or an expiry time. */
public record CustomerMessageConfirmation(long id, long ticketId, long actorUserId, UUID clientRequestId,
        long assignmentVersion, long throughMessageId, Instant recordedAt) { }
