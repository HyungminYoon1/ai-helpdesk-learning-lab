package lab.helpdesk.ticket.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lab.helpdesk.ticket.ConversationMessage;
import lab.helpdesk.ticket.TicketStatus;

public interface TicketConversationRepository {
    record Header(long id, String title, TicketStatus status, Long requesterUserId) { }
    Optional<Header> findTicket(long ticketId, boolean lock);
    void attachReceiptOwner(long ticketId, long messageId, long userId);
    List<ConversationMessage> messages(long ticketId, long afterId, int count);
    Optional<ConversationMessage> submission(long ticketId, long authorId, UUID requestId);
    Optional<ConversationMessage> message(long ticketId, long messageId);
    Optional<ConversationMessage> insertReply(long ticketId, long authorId, String authorName,
            String body, UUID requestId, Long correction, long assignmentVersion);
}
