package lab.helpdesk.ticket.repository;

import java.time.Instant;
import java.util.List;
import lab.helpdesk.ticket.TicketStatus;

public interface MyTicketsRepository {
    record Item(long ticketId, String title, TicketStatus status, Instant lastActivityAt) { }
    record Cursor(Instant lastActivityAt, long ticketId) { }
    List<Item> find(long requesterId, Cursor cursor, int count);
}
