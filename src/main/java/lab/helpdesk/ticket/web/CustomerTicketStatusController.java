package lab.helpdesk.ticket.web;

import java.io.InputStream;
import java.time.Instant;
import lab.helpdesk.identity.AuthenticatedActorFactory;
import lab.helpdesk.ticket.TicketStatus;
import lab.helpdesk.ticket.application.TicketStatusService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgres")
@RequestMapping("/api/tickets/{ticketId}/customer-status")
public class CustomerTicketStatusController {
    public record PublicChange(long id,TicketStatus fromStatus,TicketStatus toStatus,long statusVersion,String reason,Instant recordedAt) { }
    public record Change(PublicChange change,TicketStatusService.CustomerCurrent current) { }
    private final TicketStatusService service;
    public CustomerTicketStatusController(TicketStatusService service) {this.service=service;}
    @GetMapping
    public ResponseEntity<TicketStatusService.CustomerCurrent> current(@PathVariable long ticketId,Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.readRequester(ticketId,AuthenticatedActorFactory.from(authentication)));
    }
    @PostMapping(value="/reopen",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Change> reopen(@PathVariable long ticketId,InputStream body,Authentication authentication) {
        var command=CreateTicketStatusChangeRequest.readRequester(body);
        var result=service.reopenRequester(ticketId,command.clientRequestId(),command.expectedStatusVersion(),command.reason(),AuthenticatedActorFactory.from(authentication));
        var c=result.change();
        return ResponseEntity.status(result.created()?201:200).cacheControl(CacheControl.noStore()).body(new Change(
                new PublicChange(c.id(),c.fromStatus(),c.toStatus(),c.statusVersion(),c.reason(),c.recordedAt()),result.current()));
    }
}
