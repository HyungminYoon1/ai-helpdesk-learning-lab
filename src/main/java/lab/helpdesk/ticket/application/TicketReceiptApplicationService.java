package lab.helpdesk.ticket.application;

import lab.helpdesk.ai.job.AiSuggestionJobRepository;
import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketMessage;
import lab.helpdesk.ticket.repository.TicketMessageRepository;
import lab.helpdesk.ticket.repository.TicketRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class TicketReceiptApplicationService {

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final AiSuggestionJobRepository jobs;

    public TicketReceiptApplicationService(
            TicketRepository tickets,
            TicketMessageRepository messages,
            AiSuggestionJobRepository jobs) {
        this.tickets = tickets;
        this.messages = messages;
        this.jobs = jobs;
    }

    // HTTP 연결 단계에서는 인증 결과에서 얻은 작성자만 전달해야 한다.
    @Transactional
    public TicketReceiptResult receive(
            String title,
            String body,
            String authenticatedAuthorUsername) {

        Ticket ticket = new Ticket(title);
        TicketMessage message = new TicketMessage(body, authenticatedAuthorUsername);

        long ticketId = tickets.save(ticket);
        long messageId = messages.save(ticketId, message);
        long jobId = jobs.enqueue(messageId);

        return new TicketReceiptResult(
                new TicketResult(ticketId, ticket.title(), ticket.status()),
                messageId,
                jobId);
    }
}
