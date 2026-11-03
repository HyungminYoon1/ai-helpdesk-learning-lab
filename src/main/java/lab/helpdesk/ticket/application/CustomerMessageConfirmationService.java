package lab.helpdesk.ticket.application;

import java.util.Objects;
import java.util.UUID;
import lab.helpdesk.identity.AuthenticatedActor;
import lab.helpdesk.support.SupportAccess;
import lab.helpdesk.support.SupportException;
import lab.helpdesk.ticket.CustomerMessageConfirmation;
import lab.helpdesk.ticket.MessageType;
import lab.helpdesk.ticket.repository.CustomerMessageConfirmationRepository;
import lab.helpdesk.ticket.repository.TicketConversationRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static lab.helpdesk.ticket.application.ConversationException.Code.*;

@Service
@Profile("postgres")
public class CustomerMessageConfirmationService {
    public record Current(long assignmentVersion, Long effectiveThroughMessageId, Long latestCustomerMessageId,
            boolean needsConfirmation, boolean canConfirm) { }
    public record Change(CustomerMessageConfirmation confirmation, Current currentConfirmation, boolean created) { }
    private final CustomerMessageConfirmationRepository repository;
    private final TicketConversationRepository messages;
    private final SupportAccess access;

    public CustomerMessageConfirmationService(CustomerMessageConfirmationRepository repository,
            TicketConversationRepository messages, SupportAccess access) {
        this.repository = repository; this.messages = messages; this.access = access;
    }

    // Membership FOR SHARE preserves authorization through the transaction; no confirmation is written by GET.
    @Transactional
    public Current read(long ticketId, AuthenticatedActor actor) {
        if (ticketId <= 0) throw new ConversationException(INVALID_REQUEST);
        long author = access.actorId(actor);
        var ticket = access.ticket(ticketId,false);
        access.staff(ticket,actor);
        return current(ticketId,author);
    }

    @Transactional
    public Change confirm(long ticketId, UUID requestId, long expectedVersion, long throughMessageId, AuthenticatedActor actor) {
        if (ticketId <= 0 || requestId == null || expectedVersion <= 0 || throughMessageId <= 0)
            throw new ConversationException(INVALID_REQUEST);
        long author = access.actorId(actor);
        var ticket = access.ticket(ticketId,true);
        access.staff(ticket,actor);
        var existing = repository.submission(ticketId,author,requestId);
        if (existing.isPresent()) return replay(existing.get(),expectedVersion,throughMessageId,author);
        if (!Objects.equals(ticket.assigneeAgentId(),author)) throw new SupportException(SupportException.Code.NOT_ASSIGNEE);
        if (ticket.version() != expectedVersion) throw new SupportException(SupportException.Code.ASSIGNMENT_VERSION_CONFLICT);
        if (messages.message(ticketId,throughMessageId)
                .filter(message -> message.type() == MessageType.INQUIRY || message.type() == MessageType.CUSTOMER_FOLLOWUP).isEmpty())
            throw new ConversationException(INVALID_CONFIRMATION_TARGET);
        var inserted = repository.insert(ticketId,author,requestId,expectedVersion,throughMessageId);
        if (inserted.isPresent()) return new Change(inserted.get(),current(ticketId,author),true);
        return replay(repository.submission(ticketId,author,requestId)
                .orElseThrow(() -> new IllegalStateException("confirmation result unavailable")),expectedVersion,throughMessageId,author);
    }

    private Change replay(CustomerMessageConfirmation stored, long version, long through, long author) {
        if (stored.assignmentVersion() != version || stored.throughMessageId() != through)
            throw new ConversationException(CONFIRMATION_REQUEST_CONFLICT);
        return new Change(stored,current(stored.ticketId(),author),false);
    }

    private Current current(long ticketId, long actorId) {
        var snapshot = repository.current(ticketId).orElseThrow(() -> new ConversationException(NOT_FOUND));
        Long latest = snapshot.latestCustomerMessageId(), effective = snapshot.effectiveThroughMessageId();
        return new Current(snapshot.assignmentVersion(),effective,latest,
                latest != null && (effective == null || effective < latest),
                latest != null && Objects.equals(snapshot.assigneeUserId(),actorId));
    }
}
