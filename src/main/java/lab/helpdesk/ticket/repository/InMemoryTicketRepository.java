package lab.helpdesk.ticket.repository;

import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;
import lab.helpdesk.ticket.Ticket;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository
@Profile("in-memory")
public class InMemoryTicketRepository implements TicketRepository {

    private long nextId = 1L;
    private final Map<Long, Ticket> tickets = new HashMap<>();

    @Override
    public long save(Ticket ticket) {
        long id = nextId;
        tickets.put(id, ticket);
        nextId++;
        return id;
    }

    @Override
    public Optional<Ticket> findById(long id) {
        return Optional.ofNullable(tickets.get(id));
    }
}
