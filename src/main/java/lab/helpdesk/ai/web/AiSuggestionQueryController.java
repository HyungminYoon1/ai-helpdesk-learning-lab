package lab.helpdesk.ai.web;

import lab.helpdesk.support.ScopedTicketQueryService;
import lab.helpdesk.identity.AuthenticatedActorFactory;
import org.springframework.security.core.Authentication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("postgres")
public class AiSuggestionQueryController {

    private final ScopedTicketQueryService service;

    public AiSuggestionQueryController(ScopedTicketQueryService service) {
        this.service = service;
    }

    @GetMapping("/api/tickets/{id}/ai-suggestion")
    public ResponseEntity<AiSuggestionQueryResponse> findByTicketId(@PathVariable long id, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(AiSuggestionQueryResponse.from(service.suggestion(id,AuthenticatedActorFactory.from(authentication))));
    }
}
