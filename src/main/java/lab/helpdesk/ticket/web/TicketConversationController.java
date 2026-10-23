package lab.helpdesk.ticket.web;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;
import lab.helpdesk.identity.AuthenticatedActorFactory;
import lab.helpdesk.ticket.ConversationMessage;
import lab.helpdesk.ticket.MessageType;
import lab.helpdesk.ticket.application.TicketConversationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgres")
@RequestMapping("/api/tickets/{ticketId}")
public class TicketConversationController {
    public record Capabilities(boolean canReply) { }
    public record Page(Long nextAfterId, boolean hasMore) { }
    public record MessageResponse(long id, MessageType type, String body, Instant createdAt, Long correctsMessageId) {
        static MessageResponse from(ConversationMessage message) {
            return new MessageResponse(message.id(), message.type(), message.body(), message.createdAt(), message.correctsMessageId());
        }
    }
    public record ConversationResponse(TicketResponse ticket, Capabilities capabilities,
            List<MessageResponse> messages, Page page) { }
    private final TicketConversationService service;
    public TicketConversationController(TicketConversationService service) { this.service = service; }

    @GetMapping("/messages")
    public ResponseEntity<ConversationResponse> messages(@PathVariable long ticketId,
            @RequestParam(defaultValue = "0") long afterId, @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        var conversation = service.read(ticketId, afterId, limit, AuthenticatedActorFactory.from(authentication));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ConversationResponse(
                TicketResponse.from(conversation.ticket()), new Capabilities(conversation.canReply()),
                conversation.messages().stream().map(MessageResponse::from).toList(),
                new Page(conversation.nextAfterId(), conversation.hasMore())));
    }

    @PostMapping("/replies")
    public ResponseEntity<MessageResponse> reply(@PathVariable long ticketId,
            @RequestBody JsonNode json, Authentication authentication) {
        var request = CreateOfficialReplyRequest.parse(json);
        var result = service.reply(ticketId, request.body(), request.clientRequestId(), request.correctsMessageId(),
                AuthenticatedActorFactory.from(authentication));
        return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore())
                .body(MessageResponse.from(result.message()));
    }
}
