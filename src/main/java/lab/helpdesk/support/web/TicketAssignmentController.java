package lab.helpdesk.support.web;

import java.time.Instant;
import java.util.List;
import lab.helpdesk.identity.AuthenticatedActorFactory;
import lab.helpdesk.support.TicketAssignmentService;
import lab.helpdesk.support.SupportRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@Profile("postgres")
public class TicketAssignmentController {
    public record Agent(long agentId,String displayName,String role) { }
    public record Event(long id,long ticketId,String type,long actorAgentId,Long fromAgentId,long toAgentId,
            long assignmentVersion,String reason,Instant recordedAt) {
        static Event from(SupportRepository.Event event) {
            return new Event(event.id(),event.ticketId(),event.type(),event.actorId(),event.fromAgentId(),
                    event.toAgentId(),event.assignmentVersion(),event.reason(),event.recordedAt());
        }
    }
    public record Change(Event event,TicketAssignmentService.Assignment assignment) { }
    private final TicketAssignmentService service;
    public TicketAssignmentController(TicketAssignmentService service) { this.service=service; }
    @GetMapping("/api/services")
    public ResponseEntity<List<SupportRepository.Service>> services(Authentication authentication) {
        return ok(service.services(AuthenticatedActorFactory.from(authentication)));
    }
    @GetMapping("/api/services/{id}/agents")
    public ResponseEntity<TicketAssignmentService.Page<Agent>> agents(@PathVariable long id,
            @RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="50") int limit,Authentication authentication) {
        var page=service.agents(id,afterId,limit,AuthenticatedActorFactory.from(authentication));
        return ok(new TicketAssignmentService.Page<>(page.items().stream().map(m -> new Agent(m.agentId(),m.displayName(),m.role())).toList(),page.nextAfterId(),page.hasMore()));
    }
    @GetMapping("/api/services/{id}/assignment-queue")
    public ResponseEntity<TicketAssignmentService.Page<SupportRepository.QueueTicket>> queue(@PathVariable long id,
            @RequestParam(defaultValue="UNASSIGNED") String view,@RequestParam(defaultValue="0") long afterId,
            @RequestParam(defaultValue="50") int limit,Authentication authentication) {
        return ok(service.queue(id,view,afterId,limit,AuthenticatedActorFactory.from(authentication)));
    }
    @GetMapping("/api/tickets/{id}/assignment")
    public ResponseEntity<TicketAssignmentService.Assignment> assignment(@PathVariable long id,Authentication authentication) {
        return ok(service.read(id,AuthenticatedActorFactory.from(authentication)));
    }
    @GetMapping("/api/tickets/{id}/assignment-events")
    public ResponseEntity<TicketAssignmentService.Page<Event>> events(@PathVariable long id,
            @RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="50") int limit,Authentication authentication) {
        var page=service.events(id,afterId,limit,AuthenticatedActorFactory.from(authentication));
        return ok(new TicketAssignmentService.Page<>(page.items().stream().map(Event::from).toList(),page.nextAfterId(),page.hasMore()));
    }
    @PostMapping("/api/tickets/{id}/assignment/claim")
    public ResponseEntity<Change> claim(@PathVariable long id,@RequestBody JsonNode json,Authentication authentication) {
        var request=AssignmentRequest.parse(json,false);
        return changed(service.claim(id,request.clientRequestId(),request.expectedVersion(),AuthenticatedActorFactory.from(authentication)));
    }
    @PostMapping("/api/tickets/{id}/assignment/transfers")
    public ResponseEntity<Change> transfer(@PathVariable long id,@RequestBody JsonNode json,Authentication authentication) {
        var request=AssignmentRequest.parse(json,true);
        return changed(service.transfer(id,request.clientRequestId(),request.expectedVersion(),request.toAgentId(),request.reason(),AuthenticatedActorFactory.from(authentication)));
    }
    private ResponseEntity<Change> changed(TicketAssignmentService.Change change) {
        return ResponseEntity.status(change.created()?201:200).cacheControl(CacheControl.noStore())
                .body(new Change(Event.from(change.event()),change.assignment()));
    }
    private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
