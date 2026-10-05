package lab.helpdesk.ticket.web;

import java.net.URI;

import jakarta.validation.Valid;
import lab.helpdesk.ticket.application.TicketApplicationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Week 2의 제목 전용 생성 실험. Message와 AI Job을 접수한 것으로 표시하지 않는다. */
@RestController
@Profile("in-memory")
@RequestMapping("/api/tickets")
public class InMemoryTicketCreationController {

    private final TicketApplicationService service;

    public InMemoryTicketCreationController(TicketApplicationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> create(
            @Valid @RequestBody CreateTicketRequest request) {
        var result = service.create(request.title());
        return ResponseEntity.created(URI.create("/api/tickets/" + result.id()))
                .body(TicketResponse.from(result));
    }
}
