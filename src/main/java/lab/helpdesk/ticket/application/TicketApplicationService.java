package lab.helpdesk.ticket.application;

import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.repository.TicketRepository;
import org.springframework.stereotype.Service;

@Service
public class TicketApplicationService {

    private final TicketRepository repository;

    public TicketApplicationService(TicketRepository repository) {
        this.repository = repository;
    }

    public TicketResult create(String title) {

        // 1. Ticket 생성
        Ticket ticket = new Ticket(title);

        // 2. Repository 저장 및 ID 수신
        long ticketId = repository.save(ticket);

        // 3. TicketResult 생성·반환
        TicketResult result = new TicketResult(ticketId, ticket.title(), ticket.status());
        return result;
    }

    // Ticket이 없으면 TicketNotFoundException 발생
    public TicketResult findById(long id) {
        return repository.findById(id)
                .map(ticket -> new TicketResult(
                        id,
                        ticket.title(),
                        ticket.status()))
                .orElseThrow(
                        () -> new TicketNotFoundException(id));
    }
}
