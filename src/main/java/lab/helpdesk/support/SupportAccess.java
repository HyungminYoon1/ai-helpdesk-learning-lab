package lab.helpdesk.support;

import java.util.List;
import lab.helpdesk.identity.AuthenticatedActor;
import lab.helpdesk.identity.UserIdentityRepository;
import lab.helpdesk.support.SupportRepository.Member;
import lab.helpdesk.support.SupportRepository.TicketScope;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import static lab.helpdesk.support.SupportException.Code.*;

/** Called inside the application's short transaction, never from a Controller. */
@Component
@Profile("postgres")
public class SupportAccess {
    private final SupportRepository repository;
    private final UserIdentityRepository identities;
    public SupportAccess(SupportRepository repository,UserIdentityRepository identities) {
        this.repository=repository;this.identities=identities;
    }
    public long actorId(AuthenticatedActor actor) {
        if (actor==null || !actor.agent()) throw new SupportException(SUPPORT_FORBIDDEN);
        return identities.requireId(actor);
    }
    public TicketScope ticket(long ticketId,boolean lock) {
        if (ticketId<=0) throw new SupportException(INVALID_ASSIGNMENT_REQUEST);
        repository.boundLockWait();
        return repository.ticket(ticketId,lock).orElseThrow(() -> new SupportException(SUPPORT_NOT_FOUND));
    }
    public Member member(Long serviceId,long userId) {
        return optionalMember(serviceId,userId)
                .orElseThrow(() -> new SupportException(SUPPORT_NOT_FOUND));
    }
    public java.util.Optional<Member> optionalMember(Long serviceId,long userId) {
        var members=serviceId==null ? List.<Member>of() : repository.members(serviceId,List.of(userId));
        return members.stream().filter(Member::active).findFirst();
    }
    public Member staff(TicketScope ticket,AuthenticatedActor actor) { return member(ticket.serviceId(),actorId(actor)); }
    public Member service(long serviceId,AuthenticatedActor actor) {
        if (serviceId<=0) throw new SupportException(INVALID_ASSIGNMENT_REQUEST);
        repository.boundLockWait();
        return member(serviceId,actorId(actor));
    }
}
