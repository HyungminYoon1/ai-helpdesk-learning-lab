package lab.helpdesk.ticket.web;

import java.io.InputStream;
import java.time.Instant;
import lab.helpdesk.identity.AuthenticatedActorFactory;
import lab.helpdesk.ticket.CustomerMessageConfirmation;
import lab.helpdesk.ticket.application.CustomerMessageConfirmationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgres")
@RequestMapping("/api/tickets/{ticketId}/customer-message-confirmations")
public class CustomerMessageConfirmationController {
    public record Confirmation(long id, long assignmentVersion, long throughMessageId, Instant recordedAt) {
        static Confirmation from(CustomerMessageConfirmation stored) {
            return new Confirmation(stored.id(),stored.assignmentVersion(),stored.throughMessageId(),stored.recordedAt());
        }
    }
    public record Change(Confirmation confirmation, CustomerMessageConfirmationService.Current currentConfirmation) { }
    private final CustomerMessageConfirmationService service;
    public CustomerMessageConfirmationController(CustomerMessageConfirmationService service) { this.service = service; }

    @GetMapping("/current")
    public ResponseEntity<CustomerMessageConfirmationService.Current> current(@PathVariable long ticketId, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.read(ticketId,AuthenticatedActorFactory.from(authentication)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Change> confirm(@PathVariable long ticketId, InputStream requestBody, Authentication authentication) {
        var request = CreateCustomerMessageConfirmationRequest.read(requestBody);
        var result = service.confirm(ticketId,request.clientRequestId(),request.expectedAssignmentVersion(),request.throughMessageId(),
                AuthenticatedActorFactory.from(authentication));
        return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore())
                .body(new Change(Confirmation.from(result.confirmation()),result.currentConfirmation()));
    }
}
