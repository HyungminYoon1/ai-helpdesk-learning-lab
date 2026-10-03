package lab.helpdesk.ticket.repository;

import lab.helpdesk.ticket.TicketMessage;

public interface TicketMessageRepository {

    long save(long ticketId, TicketMessage message);
}
