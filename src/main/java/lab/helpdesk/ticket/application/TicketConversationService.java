package lab.helpdesk.ticket.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lab.helpdesk.identity.AuthenticatedActor;
import lab.helpdesk.identity.UserIdentityRepository;
import lab.helpdesk.ticket.ConversationMessage;
import lab.helpdesk.ticket.MessageType;
import lab.helpdesk.ticket.TicketMessage;
import lab.helpdesk.ticket.repository.TicketConversationRepository;
import lab.helpdesk.ticket.repository.TicketConversationRepository.Header;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static lab.helpdesk.ticket.application.ConversationException.Code.*;

@Service
@Profile("postgres")
public class TicketConversationService {
    public record Conversation(TicketResult ticket, boolean canReply, List<ConversationMessage> messages,
            Long nextAfterId, boolean hasMore) { }
    public record ReplyResult(ConversationMessage message, boolean created) { }
    private final TicketConversationRepository repository;
    private final UserIdentityRepository identities;

    public TicketConversationService(TicketConversationRepository repository, UserIdentityRepository identities) {
        this.repository = repository;
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public Conversation read(long ticketId, long afterId, int limit, AuthenticatedActor actor) {
        if (ticketId <= 0 || afterId < 0 || limit < 1 || limit > 100) throw new ConversationException(INVALID_REQUEST);
        if (actor == null) throw new ConversationException(FORBIDDEN);
        Header ticket = repository.findTicket(ticketId, false).orElseThrow(() -> new ConversationException(NOT_FOUND));
        if (!actor.agent() && !Objects.equals(ticket.requesterUserId(), identities.requireId(actor))) {
            throw new ConversationException(NOT_FOUND);
        }
        var fetched = repository.messages(ticketId, afterId, limit + 1);
        boolean more = fetched.size() > limit;
        var messages = List.copyOf(more ? fetched.subList(0, limit) : fetched);
        boolean canReply = actor.agent() && ticket.requesterUserId() != null && identities.findId(actor.accountKey()).isPresent();
        return new Conversation(new TicketResult(ticket.id(), ticket.title(), ticket.status()), canReply, messages,
                more ? messages.getLast().id() : null, more);
    }

    @Transactional
    public ReplyResult reply(long ticketId, String body, UUID requestId, Long correction, AuthenticatedActor actor) {
        if (ticketId <= 0 || requestId == null || correction != null && correction <= 0) throw new ConversationException(INVALID_REQUEST);
        if (actor == null || !actor.agent()) throw new ConversationException(FORBIDDEN);
        try { new TicketMessage(body, actor.username()); }
        catch (IllegalArgumentException invalid) { throw new ConversationException(INVALID_REQUEST); }
        long author = identities.requireId(actor);
        Header ticket = repository.findTicket(ticketId, true).orElseThrow(() -> new ConversationException(NOT_FOUND));
        if (ticket.requesterUserId() == null) throw new ConversationException(REQUESTER_UNCONFIRMED);
        var existing = repository.submission(ticketId, author, requestId);
        if (existing.isPresent()) return replay(existing.get(), body, correction);
        if (correction != null && repository.message(ticketId, correction)
                .filter(message -> message.type() == MessageType.OFFICIAL_REPLY).isEmpty()) {
            throw new ConversationException(INVALID_CORRECTION_TARGET);
        }
        var inserted = repository.insertReply(ticketId, author, actor.username(), body, requestId, correction);
        if (inserted.isPresent()) return new ReplyResult(inserted.get(), true);
        return replay(repository.submission(ticketId, author, requestId)
                .orElseThrow(() -> new IllegalStateException("submission result unavailable")), body, correction);
    }

    private ReplyResult replay(ConversationMessage stored, String body, Long correction) {
        if (!stored.body().equals(body) || !Objects.equals(stored.correctsMessageId(), correction)) {
            throw new ConversationException(REPLY_REQUEST_CONFLICT);
        }
        return new ReplyResult(stored, false);
    }
}
