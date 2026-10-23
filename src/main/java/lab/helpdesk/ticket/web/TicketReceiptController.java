package lab.helpdesk.ticket.web;

import java.net.URI;

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
        var result = service.receive(
                request.title(), request.body(), AuthenticatedActorFactory.from(authentication));
        // In this HTTP flow the transactional Service proxy has already committed.
        // This is receipt metadata, not AI completion or proof the Browser received the response.
        LOGGER.info("event=TICKET_RECEIPT_COMMITTED requestId={} ticketId={} messageId={} jobId={}",
                RequestIdFilter.requestIdForLog(servletRequest), result.ticket().id(),
                result.messageId(), result.jobId());
        return ResponseEntity.created(URI.create("/api/tickets/" + result.ticket().id()))
                .body(TicketResponse.from(result.ticket()));
    }
}
