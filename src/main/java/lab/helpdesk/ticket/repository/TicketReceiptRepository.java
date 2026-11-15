package lab.helpdesk.ticket.repository;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import lab.helpdesk.ticket.Ticket;

public interface TicketReceiptRepository {

    record StoredReceipt(long ticketId, Ticket ticket, long messageId, String body, long jobId) { }

    OptionalLong insertReceipt(Ticket ticket, long requesterUserId, long serviceId, UUID clientRequestId);

    Optional<StoredReceipt> findReceipt(long requesterUserId, UUID clientRequestId);
}
