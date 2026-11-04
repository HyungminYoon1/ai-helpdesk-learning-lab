package lab.helpdesk.ticket.web;

import java.io.InputStream;
import lab.helpdesk.identity.AuthenticatedActorFactory;
import lab.helpdesk.ticket.TicketStatusChange;
import lab.helpdesk.ticket.application.TicketStatusService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgres")
@RequestMapping("/api/tickets/{ticketId}")
public class TicketStatusController {
    public record Change(TicketStatusChange change,TicketStatusService.StaffCurrent current) { }
    private final TicketStatusService service;
    public TicketStatusController(TicketStatusService service) {this.service=service;}
    @GetMapping("/status")
    public ResponseEntity<TicketStatusService.StaffCurrent> current(@PathVariable long ticketId,Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.readStaff(ticketId,AuthenticatedActorFactory.from(authentication)));
    }
    @GetMapping("/status-events")
    public ResponseEntity<TicketStatusService.Page> events(@PathVariable long ticketId,@RequestParam(defaultValue="0") long afterId,
            @RequestParam(defaultValue="50") int limit,Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.events(ticketId,afterId,limit,AuthenticatedActorFactory.from(authentication)));
    }
    @PostMapping(value="/status-changes",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Change> change(@PathVariable long ticketId,InputStream body,Authentication authentication) {
        var result=service.changeStaff(ticketId,CreateTicketStatusChangeRequest.readStaff(body),AuthenticatedActorFactory.from(authentication));
        return ResponseEntity.status(result.created()?201:200).cacheControl(CacheControl.noStore()).body(new Change(result.change(),result.current()));
    }
}
