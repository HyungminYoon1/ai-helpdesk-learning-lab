package lab.helpdesk.ticket.web;

import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.identity.AuthenticatedActorFactory;
import lab.helpdesk.web.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("postgres")
@RequestMapping("/api/tickets")
public class TicketReceiptController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TicketReceiptController.class);
    private final TicketReceiptApplicationService service;

    public TicketReceiptController(TicketReceiptApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(
            @Valid @RequestBody CreateTicketReceiptRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest) {
        // Service의 접수 Transaction이 Commit된 뒤에만 정상 반환을 받는다.
        var result = service.receive(request.title(), request.body(), UUID.fromString(request.clientRequestId()),
                AuthenticatedActorFactory.from(authentication));
        var location = URI.create("/api/tickets/" + result.ticket().id());
        var body = TicketResponse.from(result.ticket());

        if (!result.created()) {
            return ResponseEntity.ok().location(location).body(body);
        }
        LOGGER.info("event=TICKET_RECEIPT_COMMITTED requestId={} ticketId={} messageId={} jobId={}",
                RequestIdFilter.requestIdForLog(servletRequest), result.ticket().id(),
                result.messageId(), result.jobId());
        return ResponseEntity.created(location).body(body);
    }
}
