package lab.helpdesk.support;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lab.helpdesk.identity.AuthenticatedActor;
import lab.helpdesk.support.SupportRepository.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static lab.helpdesk.support.SupportException.Code.*;

@Service
@Profile("postgres")
public class TicketAssignmentService {
    public record Assignment(long ticketId,long serviceId,Long assigneeAgentId,long version,
            boolean canClaim,boolean canTransfer,boolean canReply) { }
    public record Change(Event event,Assignment assignment,boolean created) { }
    public record Page<T>(List<T> items,Long nextAfterId,boolean hasMore) { }
    private final SupportRepository repository;
    private final SupportAccess access;
    public TicketAssignmentService(SupportRepository repository,SupportAccess access) {
        this.repository=repository;this.access=access;
    }
    @Transactional
    public List<SupportRepository.Service> services(AuthenticatedActor actor) {
        repository.boundLockWait();return repository.services(access.actorId(actor));
    }
    @Transactional
    public Page<Member> agents(long service,long after,int limit,AuthenticatedActor actor) {
        page(after,limit);access.service(service,actor);
        return pageOf(repository.agents(service,after,limit+1),limit,Member::agentId);
    }
    @Transactional
    public Page<QueueTicket> queue(long service,String view,long after,int limit,AuthenticatedActor actor) {
        page(after,limit);
        if (!List.of("UNASSIGNED","MINE").contains(view)) throw new SupportException(INVALID_ASSIGNMENT_REQUEST);
        access.service(service,actor);
        return pageOf(repository.queue(service,view.equals("MINE") ? access.actorId(actor) : null,after,limit+1),limit,QueueTicket::id);
    }
    @Transactional
    public Assignment read(long ticketId,AuthenticatedActor actor) {
        var ticket=access.ticket(ticketId,false);return assignment(ticket,access.staff(ticket,actor));
    }
    @Transactional
    public Page<Event> events(long ticketId,long after,int limit,AuthenticatedActor actor) {
        page(after,limit);var ticket=access.ticket(ticketId,false);access.staff(ticket,actor);
        return pageOf(repository.events(ticketId,after,limit+1),limit,Event::id);
    }
    @Transactional
    public Change claim(long ticket,UUID request,long expected,AuthenticatedActor actor) {
        return change(ticket,request,expected,null,null,actor);
    }
    @Transactional
    public Change transfer(long ticket,UUID request,long expected,long target,String reason,AuthenticatedActor actor) {
        if (target<=0 || reason==null || reason.isBlank() || reason.codePointCount(0,reason.length())>500)
            throw new SupportException(INVALID_ASSIGNMENT_REQUEST);
        return change(ticket,request,expected,target,reason,actor);
    }
    private Change change(long ticketId,UUID request,long expected,Long target,String reason,AuthenticatedActor actor) {
        if (request==null || expected<0 || expected==Long.MAX_VALUE) throw new SupportException(INVALID_ASSIGNMENT_REQUEST);
        long actorId=access.actorId(actor);
        var ticket=access.ticket(ticketId,true);
        if (ticket.serviceId()==null) throw new SupportException(SUPPORT_NOT_FOUND);
        // Ticket first, then distinct member IDs in ascending order, including target.
        var ids=target==null ? List.of(actorId) : java.util.stream.Stream.of(actorId,target).distinct().sorted().toList();
        var members=repository.members(ticket.serviceId(),ids);
        var member=members.stream().filter(m -> m.agentId()==actorId && m.active()).findFirst()
                .orElseThrow(() -> new SupportException(SUPPORT_NOT_FOUND));
        String type=target==null ? "CLAIM" : "TRANSFER";
        long to=target==null ? actorId : target;
        var previous=repository.submission(ticketId,actorId,request);
        if (previous.isPresent()) {
            var event=previous.get();
            if (!event.type().equals(type) || event.expectedVersion()!=expected || event.toAgentId()!=to
                    || !Objects.equals(event.reason(),reason)) throw new SupportException(ASSIGNMENT_REQUEST_CONFLICT);
            return new Change(event,assignment(ticket,member),false);
        }
        if (expected!=ticket.version()) throw new SupportException(ASSIGNMENT_VERSION_CONFLICT);
        if (target==null) {
            if (ticket.assigneeAgentId()!=null) throw new SupportException(ASSIGNMENT_NOT_UNASSIGNED);
        } else {
            if (ticket.assigneeAgentId()==null) throw new SupportException(ASSIGNMENT_REQUIRED);
            if (!Objects.equals(ticket.assigneeAgentId(),actorId) && !member.role().equals("SUPERVISOR"))
                throw new SupportException(ASSIGNMENT_FORBIDDEN);
            if (Objects.equals(ticket.assigneeAgentId(),to) || members.stream().noneMatch(m -> m.agentId()==to && m.active()))
                throw new SupportException(INVALID_ASSIGNEE);
        }
        repository.change(ticket,to);
        var event=repository.append(ticket,actorId,request,type,to,reason);
        return new Change(event,assignment(new TicketScope(ticketId,ticket.serviceId(),to,event.assignmentVersion(),ticket.requesterUserId()),member),true);
    }
    private Assignment assignment(TicketScope ticket,Member actor) {
        boolean own=Objects.equals(ticket.assigneeAgentId(),actor.agentId());
        return new Assignment(ticket.ticketId(),ticket.serviceId(),ticket.assigneeAgentId(),ticket.version(),
                ticket.assigneeAgentId()==null,ticket.assigneeAgentId()!=null && (own || actor.role().equals("SUPERVISOR")),own && ticket.requesterUserId()!=null);
    }
    private static void page(long after,int limit) {
        if (after<0 || limit<1 || limit>100) throw new SupportException(INVALID_ASSIGNMENT_REQUEST);
    }
    private static <T> Page<T> pageOf(List<T> values,int limit,java.util.function.ToLongFunction<T> id) {
        boolean more=values.size()>limit;
        var items=List.copyOf(more ? values.subList(0,limit) : values);
        return new Page<>(items,more ? id.applyAsLong(items.getLast()) : null,more);
    }
}
