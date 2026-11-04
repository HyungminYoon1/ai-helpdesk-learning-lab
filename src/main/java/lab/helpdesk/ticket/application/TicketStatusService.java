package lab.helpdesk.ticket.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lab.helpdesk.identity.AuthenticatedActor;
import lab.helpdesk.identity.UserIdentityRepository;
import lab.helpdesk.support.SupportAccess;
import lab.helpdesk.support.SupportException;
import lab.helpdesk.ticket.MessageType;
import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketStatus;
import lab.helpdesk.ticket.TicketStatusAction;
import lab.helpdesk.ticket.TicketStatusChange;
import lab.helpdesk.ticket.TicketStatusChange.Scope;
import lab.helpdesk.ticket.repository.TicketConversationRepository;
import lab.helpdesk.ticket.repository.TicketStatusRepository;
import lab.helpdesk.ticket.repository.TicketStatusRepository.Snapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static lab.helpdesk.ticket.application.TicketStatusException.Code.*;

@Service
@Profile("postgres")
public class TicketStatusService {
    public record Command(UUID clientRequestId, TicketStatusAction action, long expectedStatusVersion,
            Long expectedAssignmentVersion, String reason, Long relatedReplyMessageId, Long throughCustomerMessageId) {
        @Override public String toString() { return "TicketStatusCommand[redacted]"; }
    }
    public record StaffCurrent(TicketStatus status, long statusVersion, long assignmentVersion,
            Long latestCustomerMessageId, Long effectiveThroughMessageId, boolean canStart, boolean canResolve, boolean canReopen) { }
    public record CustomerCurrent(TicketStatus status, long statusVersion, boolean canReopen) { }
    public record StaffChange(TicketStatusChange change, StaffCurrent current, boolean created) { }
    public record CustomerChange(TicketStatusChange change, CustomerCurrent current, boolean created) { }
    public record Page(List<TicketStatusChange> items, Long nextAfterId, boolean hasMore) { }
    private record Saved(TicketStatusChange event, Snapshot current, boolean created) { }
    private final TicketStatusRepository repository;
    private final TicketConversationRepository messages;
    private final UserIdentityRepository identities;
    private final SupportAccess access;

    public TicketStatusService(TicketStatusRepository repository, TicketConversationRepository messages,
            UserIdentityRepository identities, SupportAccess access) {
        this.repository=repository;this.messages=messages;this.identities=identities;this.access=access;
    }

    @Transactional
    public StaffCurrent readStaff(long ticketId, AuthenticatedActor actor) {
        validId(ticketId);long user=actorId(actor,Scope.STAFF);
        repository.boundLockWait();var snapshot=required(ticketId);authorize(snapshot,user,Scope.STAFF);
        return staffCurrent(snapshot,user);
    }
    @Transactional
    public CustomerCurrent readRequester(long ticketId, AuthenticatedActor actor) {
        validId(ticketId);long user=actorId(actor,Scope.REQUESTER);
        repository.boundLockWait();var snapshot=required(ticketId);authorize(snapshot,user,Scope.REQUESTER);
        return customerCurrent(snapshot);
    }
    @Transactional
    public Page events(long ticketId,long afterId,int limit,AuthenticatedActor actor) {
        validId(ticketId);
        if(afterId<0 || limit<1 || limit>100)throw new TicketStatusException(INVALID_STATUS_REQUEST);
        long user=actorId(actor,Scope.STAFF);repository.boundLockWait();authorize(required(ticketId),user,Scope.STAFF);
        var rows=repository.events(ticketId,afterId,limit+1);boolean more=rows.size()>limit;
        var items=List.copyOf(more?rows.subList(0,limit):rows);
        return new Page(items,more?items.getLast().id():null,more);
    }
    @Transactional
    public StaffChange changeStaff(long ticketId,Command command,AuthenticatedActor actor) {
        var saved=change(ticketId,command,actor,Scope.STAFF);
        return new StaffChange(saved.event(),staffCurrent(saved.current(),actorId(actor,Scope.STAFF)),saved.created());
    }
    @Transactional
    public CustomerChange reopenRequester(long ticketId,UUID key,long expectedStatusVersion,String reason,AuthenticatedActor actor) {
        var command=new Command(key,TicketStatusAction.REOPEN,expectedStatusVersion,null,reason,null,null);
        var saved=change(ticketId,command,actor,Scope.REQUESTER);
        return new CustomerChange(saved.event(),customerCurrent(saved.current()),saved.created());
    }

    private Saved change(long ticketId,Command cmd,AuthenticatedActor actor,Scope scope) {
        validId(ticketId);validate(cmd,scope);long user=actorId(actor,scope);
        repository.boundLockWait();
        if(!repository.lock(ticketId))throw new TicketStatusException(STATUS_NOT_FOUND);
        // A new statement after a possible lock wait sees the preceding writer's committed messages/status.
        var before=required(ticketId);authorize(before,user,scope);
        var existing=repository.submission(ticketId,user,cmd.clientRequestId());
        if(existing.isPresent()) {
            var stored=existing.get();
            if(stored.requestScope()!=scope || stored.action()!=cmd.action() || stored.expectedStatusVersion()!=cmd.expectedStatusVersion()
                    || !Objects.equals(stored.expectedAssignmentVersion(),cmd.expectedAssignmentVersion())
                    || !stored.reason().equals(cmd.reason()) || !Objects.equals(stored.relatedReplyMessageId(),cmd.relatedReplyMessageId())
                    || !Objects.equals(stored.throughCustomerMessageId(),cmd.throughCustomerMessageId()))
                throw new TicketStatusException(STATUS_REQUEST_CONFLICT);
            return new Saved(stored,before,false);
        }
        if(scope==Scope.STAFF) {
            if(!Objects.equals(before.scope().assigneeAgentId(),user))throw new SupportException(SupportException.Code.NOT_ASSIGNEE);
            if(before.scope().version()!=cmd.expectedAssignmentVersion())throw new SupportException(SupportException.Code.ASSIGNMENT_VERSION_CONFLICT);
        }
        if(before.statusVersion()!=cmd.expectedStatusVersion())throw new TicketStatusException(STATUS_VERSION_CONFLICT);
        var ticket=Ticket.restore(before.title(),before.status());
        try { cmd.action().apply(ticket); }
        catch(IllegalStateException invalid) { throw new TicketStatusException(INVALID_STATUS_TRANSITION); }
        if(cmd.action()==TicketStatusAction.RESOLVE)validateResolution(ticketId,before,cmd);
        repository.change(before,ticket.status());
        var event=repository.append(before,user,cmd.clientRequestId(),scope,cmd.action(),ticket.status(),
                cmd.expectedAssignmentVersion(),cmd.reason(),cmd.relatedReplyMessageId(),cmd.throughCustomerMessageId());
        return new Saved(event,required(ticketId),true);
    }
    private void validateResolution(long ticketId,Snapshot before,Command cmd) {
        if(before.scope().requesterUserId()==null)throw new TicketStatusException(REQUESTER_UNCONFIRMED);
        if(messages.message(ticketId,cmd.relatedReplyMessageId()).filter(m->m.type()==MessageType.OFFICIAL_REPLY).isEmpty()
                || messages.message(ticketId,cmd.throughCustomerMessageId())
                    .filter(m->m.type()==MessageType.INQUIRY || m.type()==MessageType.CUSTOMER_FOLLOWUP).isEmpty())
            throw new TicketStatusException(INVALID_STATUS_TARGET);
        if(!Objects.equals(before.latestCustomerMessageId(),cmd.throughCustomerMessageId()))throw new TicketStatusException(NEW_CUSTOMER_MESSAGE);
        if(before.effectiveThroughMessageId()==null || before.effectiveThroughMessageId()<cmd.throughCustomerMessageId())
            throw new TicketStatusException(CUSTOMER_CONFIRMATION_REQUIRED);
    }
    private void authorize(Snapshot snapshot,long user,Scope scope) {
        if(scope==Scope.STAFF)access.member(snapshot.scope().serviceId(),user);
        else if(!Objects.equals(snapshot.scope().requesterUserId(),user))throw new TicketStatusException(STATUS_NOT_FOUND);
    }
    private long actorId(AuthenticatedActor actor,Scope scope) {
        if(actor==null || scope==Scope.STAFF && !actor.agent())throw new TicketStatusException(STATUS_FORBIDDEN);
        return identities.requireId(actor);
    }
    private Snapshot required(long ticketId) {return repository.snapshot(ticketId).orElseThrow(()->new TicketStatusException(STATUS_NOT_FOUND));}
    private static void validId(long ticketId) {if(ticketId<=0)throw new TicketStatusException(INVALID_STATUS_REQUEST);}
    private static void validate(Command c,Scope scope) {
        if(c==null || c.clientRequestId()==null || c.action()==null || c.expectedStatusVersion()<0 || c.expectedStatusVersion()==Long.MAX_VALUE
                || c.reason()==null || c.reason().isBlank() || c.reason().codePointCount(0,c.reason().length())>500)
            throw new TicketStatusException(INVALID_STATUS_REQUEST);
        if(scope==Scope.STAFF && (c.expectedAssignmentVersion()==null || c.expectedAssignmentVersion()<=0 || c.expectedAssignmentVersion()==Long.MAX_VALUE))
            throw new TicketStatusException(INVALID_STATUS_REQUEST);
        if(scope==Scope.REQUESTER && (c.action()!=TicketStatusAction.REOPEN || c.expectedAssignmentVersion()!=null))
            throw new TicketStatusException(INVALID_STATUS_REQUEST);
        if(c.action()==TicketStatusAction.RESOLVE) {
            if(c.relatedReplyMessageId()==null || c.relatedReplyMessageId()<=0 || c.throughCustomerMessageId()==null || c.throughCustomerMessageId()<=0)
                throw new TicketStatusException(INVALID_STATUS_REQUEST);
        } else if(c.relatedReplyMessageId()!=null || c.throughCustomerMessageId()!=null)throw new TicketStatusException(INVALID_STATUS_REQUEST);
    }
    private static StaffCurrent staffCurrent(Snapshot s,long user) {
        boolean own=Objects.equals(s.scope().assigneeAgentId(),user);
        boolean confirmed=s.latestCustomerMessageId()!=null && s.effectiveThroughMessageId()!=null
                && s.effectiveThroughMessageId()>=s.latestCustomerMessageId();
        return new StaffCurrent(s.status(),s.statusVersion(),s.scope().version(),s.latestCustomerMessageId(),s.effectiveThroughMessageId(),
                own && s.status()==TicketStatus.OPEN,own && s.status()==TicketStatus.IN_PROGRESS && confirmed && s.scope().requesterUserId()!=null,
                own && s.status()==TicketStatus.RESOLVED);
    }
    private static CustomerCurrent customerCurrent(Snapshot s) {return new CustomerCurrent(s.status(),s.statusVersion(),s.status()==TicketStatus.RESOLVED);}
}
