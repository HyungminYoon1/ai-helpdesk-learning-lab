package lab.helpdesk.ticket.application;

import lab.helpdesk.ai.job.AiSuggestionJobRepository;
import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketMessage;
import lab.helpdesk.ticket.repository.TicketMessageRepository;
import lab.helpdesk.ticket.repository.TicketRepository;
import lab.helpdesk.ticket.repository.TicketConversationRepository;
import lab.helpdesk.identity.AuthenticatedActor;
import lab.helpdesk.identity.UserIdentityRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class TicketReceiptApplicationService {

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final AiSuggestionJobRepository jobs;
    private final UserIdentityRepository identities;
    private final TicketConversationRepository conversations;
    private final lab.helpdesk.support.SupportReceiptRouting routing;

    public TicketReceiptApplicationService(
            TicketRepository tickets,
            TicketMessageRepository messages,
            AiSuggestionJobRepository jobs, UserIdentityRepository identities,
            TicketConversationRepository conversations, lab.helpdesk.support.SupportReceiptRouting routing) {
        this.tickets = tickets;
        this.messages = messages;
        this.jobs = jobs;
        this.identities = identities;
        this.conversations = conversations;
        this.routing = routing;
    }

    // HTTP 연결 단계에서는 인증 결과에서 얻은 작성자만 전달해야 한다.
    @Transactional
    public TicketReceiptResult receive(
            String title,
            String body,
            AuthenticatedActor actor) {

        Ticket ticket = new Ticket(title);
        if (actor == null) throw new lab.helpdesk.identity.IdentityNotLinkedException();
        TicketMessage message = new TicketMessage(body, actor.username());
        long authorId = identities.requireId(actor);
        long serviceId = routing.serviceId();

        long ticketId = tickets.save(ticket);
        long messageId = messages.save(ticketId, message);
        conversations.attachReceiptOwner(ticketId, messageId, authorId);
        routing.attach(ticketId,serviceId);
        long jobId = jobs.enqueue(messageId);

        return new TicketReceiptResult(
                new TicketResult(ticketId, ticket.title(), ticket.status()),
                messageId,
                jobId);
    }
}
