package lab.helpdesk.ticket;

import java.time.Instant;
import java.util.UUID;

public record TicketStatusChange(long id, long ticketId, long actorUserId, UUID clientRequestId,
        Scope requestScope, TicketStatusAction action, long expectedStatusVersion, long statusVersion,
        Long expectedAssignmentVersion, long assignmentVersion, TicketStatus fromStatus, TicketStatus toStatus,
        String reason, Long relatedReplyMessageId, Long throughCustomerMessageId, Instant recordedAt) {
    public enum Scope { STAFF, REQUESTER }
    @Override public String toString() { return "TicketStatusChange[redacted]"; }
}
