package lab.helpdesk.ticket.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lab.helpdesk.support.SupportRepository.TicketScope;
import lab.helpdesk.ticket.TicketStatus;
import lab.helpdesk.ticket.TicketStatusAction;
import lab.helpdesk.ticket.TicketStatusChange;
import lab.helpdesk.ticket.TicketStatusChange.Scope;

public interface TicketStatusRepository {
    record Snapshot(TicketScope scope, String title, TicketStatus status, long statusVersion,
            Long latestCustomerMessageId, Long effectiveThroughMessageId) { }
    void boundLockWait();
    boolean lock(long ticketId);
    Optional<Snapshot> snapshot(long ticketId);
    Optional<TicketStatusChange> submission(long ticketId, long actorId, UUID key);
    List<TicketStatusChange> events(long ticketId, long afterId, int count);
    void change(Snapshot previous, TicketStatus next);
    TicketStatusChange append(Snapshot previous, long actorId, UUID key, Scope scope, TicketStatusAction action, TicketStatus next,
            Long expectedAssignmentVersion, String reason, Long replyMessageId, Long throughCustomerMessageId);
}
