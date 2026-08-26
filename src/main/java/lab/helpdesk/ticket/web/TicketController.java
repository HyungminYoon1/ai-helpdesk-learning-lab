package lab.helpdesk.ticket.web;

import java.net.URI;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import lab.helpdesk.ticket.application.TicketResult;
import lab.helpdesk.ticket.application.TicketApplicationService;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketApplicationService service;

    // 생성자
    public TicketController(TicketApplicationService service) {
        this.service = service;
    }

    // 티켓 생성
    @PostMapping
    public ResponseEntity<TicketResponse> create(
            @RequestBody CreateTicketRequest request) {

        TicketResult result = service.create(request.title());
        TicketResponse response = TicketResponse.from(result);

        URI location = URI.create(
                "/api/tickets/" + result.id());

        return ResponseEntity
                .created(location)
                .body(response);
    }

    // 티켓 단건 조회
    @GetMapping("/{id}")
    public ResponseEntity<TicketResponse> findById(
            @PathVariable long id) {

        Optional<TicketResult> result = service.findById(id);

        if (result.isEmpty()) {
            return ResponseEntity
                    .notFound()
                    .build();
        }

        TicketResponse response = TicketResponse.from(
                result.orElseThrow());

        return ResponseEntity.ok(response);
    }

}
