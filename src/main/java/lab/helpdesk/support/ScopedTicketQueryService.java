package lab.helpdesk.support;

import lab.helpdesk.ai.query.AiSuggestionQueryService;
import lab.helpdesk.ai.query.AiSuggestionQueryResult;
import lab.helpdesk.ai.query.AiSuggestionQueryException;
import lab.helpdesk.identity.AuthenticatedActor;
import lab.helpdesk.ticket.application.TicketApplicationService;
import lab.helpdesk.ticket.application.TicketResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class ScopedTicketQueryService {
    private final SupportAccess access;
    private final TicketApplicationService tickets;
    private final AiSuggestionQueryService suggestions;
    public ScopedTicketQueryService(SupportAccess access,TicketApplicationService tickets,AiSuggestionQueryService suggestions) {
        this.access=access;this.tickets=tickets;this.suggestions=suggestions;
    }
    @Transactional
    public TicketResult ticket(long id,AuthenticatedActor actor) {
        access.staff(access.ticket(id,false),actor);return tickets.findById(id);
    }
    @Transactional
    public AiSuggestionQueryResult suggestion(long id,AuthenticatedActor actor) {
        if (id<=0) throw new AiSuggestionQueryException(AiSuggestionQueryException.Code.INVALID_TICKET_ID);
        access.staff(access.ticket(id,false),actor);return suggestions.findByTicketId(id);
    }
}
