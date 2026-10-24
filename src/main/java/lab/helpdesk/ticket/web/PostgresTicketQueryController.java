package lab.helpdesk.ticket.web;

import lab.helpdesk.identity.AuthenticatedActorFactory;
import lab.helpdesk.support.ScopedTicketQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgres")
public class PostgresTicketQueryController {
    private final ScopedTicketQueryService service;
    public PostgresTicketQueryController(ScopedTicketQueryService service) { this.service=service; }
    @GetMapping("/api/tickets/{id}")
    public ResponseEntity<TicketResponse> ticket(@PathVariable long id,Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(TicketResponse.from(service.ticket(id,AuthenticatedActorFactory.from(authentication))));
    }
}
