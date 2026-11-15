package lab.helpdesk.ticket.application;

import java.util.Objects;
import java.util.UUID;

import lab.helpdesk.ai.job.AiSuggestionJobRepository;
import lab.helpdesk.identity.AuthenticatedActor;
import lab.helpdesk.identity.UserIdentityRepository;
import lab.helpdesk.support.SupportReceiptRouting;
import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketMessage;
import lab.helpdesk.ticket.repository.TicketConversationRepository;
import lab.helpdesk.ticket.repository.TicketMessageRepository;
import lab.helpdesk.ticket.repository.TicketReceiptRepository;
import lab.helpdesk.ticket.repository.TicketReceiptRepository.StoredReceipt;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TicketReceiptApplicationService {

    private final TicketReceiptRepository tickets;
    private final TicketMessageRepository messages;
    private final AiSuggestionJobRepository jobs;
    private final UserIdentityRepository identities;
    private final TicketConversationRepository conversations;
    private final SupportReceiptRouting routing;

    public TicketReceiptApplicationService(
            TicketReceiptRepository tickets,
            TicketMessageRepository messages,
            AiSuggestionJobRepository jobs,
            UserIdentityRepository identities,
            TicketConversationRepository conversations,
            SupportReceiptRouting routing) {
        this.tickets = tickets;
        this.messages = messages;
        this.jobs = jobs;
        this.identities = identities;
        this.conversations = conversations;
        this.routing = routing;
    }

    // In-process callers that start a new inquiry use a fresh submission ID.
    public TicketReceiptResult receive(String title, String body, AuthenticatedActor actor) {
        return receive(title, body, UUID.randomUUID(), actor);
    }

    public TicketReceiptResult receive(String title, String body, UUID clientRequestId, AuthenticatedActor actor) {
        Objects.requireNonNull(clientRequestId, "client request ID is required");
        Ticket ticket = new Ticket(title);
        long requesterUserId = identities.requireId(actor);
        TicketMessage message = new TicketMessage(body, actor.username());

        var existing = tickets.findReceipt(requesterUserId, clientRequestId);
        if (existing.isPresent()) {
            return replay(existing.get(), ticket, message);
        }

        long serviceId = routing.serviceId();
        var inserted = tickets.insertReceipt(ticket, requesterUserId, serviceId, clientRequestId);
        if (inserted.isEmpty()) {
            // A separate READ COMMITTED statement sees the receipt that won the unique index conflict.
            var stored = tickets.findReceipt(requesterUserId, clientRequestId)
                    .orElseThrow(() -> new IllegalStateException("committed receipt could not be read"));
            return replay(stored, ticket, message);
        }

        long ticketId = inserted.getAsLong();
        long messageId = messages.save(ticketId, message);
        conversations.attachInquiryAuthor(ticketId, messageId, requesterUserId);
        long jobId = jobs.enqueue(messageId);

        return new TicketReceiptResult(
                new TicketResult(ticketId, ticket.title(), ticket.status()),
                messageId, jobId, true);
    }

    private TicketReceiptResult replay(StoredReceipt stored, Ticket ticket, TicketMessage message) {
        if (!stored.ticket().title().equals(ticket.title()) || !stored.body().equals(message.body())) {
            throw new TicketReceiptConflictException();
        }
        return new TicketReceiptResult(
                new TicketResult(stored.ticketId(), stored.ticket().title(), stored.ticket().status()),
                stored.messageId(), stored.jobId(), false);
    }
}
