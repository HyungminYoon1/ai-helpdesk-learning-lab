package lab.helpdesk.ticket.repository;

import java.util.Optional;

import lab.helpdesk.ticket.Ticket;

public interface TicketRepository {

    long save(Ticket ticket);

    Optional<Ticket> findById(long id);
}
