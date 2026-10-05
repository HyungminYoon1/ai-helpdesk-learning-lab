package lab.helpdesk.ticket.web;

import java.net.URI;

import jakarta.validation.Valid;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
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

    private final TicketReceiptApplicationService service;

    public TicketReceiptController(TicketReceiptApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(
            @Valid @RequestBody CreateTicketReceiptRequest request,
            Authentication authentication) {
        // Service의 접수 Transaction이 Commit된 뒤에만 정상 반환을 받는다.
        var result = service.receive(
                request.title(), request.body(), authentication.getName());
        return ResponseEntity.created(URI.create("/api/tickets/" + result.ticket().id()))
                .body(TicketResponse.from(result.ticket()));
    }
}
