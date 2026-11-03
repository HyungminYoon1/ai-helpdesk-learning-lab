package lab.helpdesk.ticket.application;

import java.util.List;
import lab.helpdesk.identity.AuthenticatedActor;
import lab.helpdesk.identity.UserIdentityRepository;
import lab.helpdesk.ticket.repository.MyTicketsRepository;
import lab.helpdesk.ticket.repository.MyTicketsRepository.Cursor;
import lab.helpdesk.ticket.repository.MyTicketsRepository.Item;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class MyTicketsQueryService {
    public record Page(List<Item> items, Cursor nextCursor, boolean hasMore) { }
    private final MyTicketsRepository repository;
    private final UserIdentityRepository identities;
    public MyTicketsQueryService(MyTicketsRepository repository, UserIdentityRepository identities) {
        this.repository = repository;
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public Page read(int limit, Cursor cursor, AuthenticatedActor actor) {
        if (limit < 1 || limit > 100) throw new ConversationException(ConversationException.Code.INVALID_REQUEST);
        long requesterId = identities.requireId(actor);
        var fetched = repository.find(requesterId, cursor, limit + 1);
        boolean more = fetched.size() > limit;
        var items = List.copyOf(more ? fetched.subList(0, limit) : fetched);
        var last = more ? items.getLast() : null;
        return new Page(items, last == null ? null : new Cursor(last.lastActivityAt(), last.ticketId()), more);
    }
}
