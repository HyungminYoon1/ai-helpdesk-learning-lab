package lab.helpdesk.ticket.repository;

import java.util.Optional;
import java.util.UUID;
import lab.helpdesk.ticket.CustomerMessageConfirmation;

public interface CustomerMessageConfirmationRepository {
    record Snapshot(long assignmentVersion, Long assigneeUserId, Long effectiveThroughMessageId, Long latestCustomerMessageId) { }
    Optional<CustomerMessageConfirmation> submission(long ticketId, long actorId, UUID requestId);
    Optional<CustomerMessageConfirmation> insert(long ticketId, long actorId, UUID requestId, long version, long throughMessageId);
    Optional<Snapshot> current(long ticketId);
}
