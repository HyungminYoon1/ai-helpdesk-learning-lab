package lab.helpdesk.support;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportRepository {
    record Service(long id, String code, String displayName) { }
    record Member(long agentId, String displayName, String role, boolean active) { }
    record TicketScope(long ticketId, Long serviceId, Long assigneeAgentId, long version, Long requesterUserId) { }
    record QueueTicket(long id, String title, String status, Instant createdAt, long assignmentVersion) { }
    record Event(long id, long ticketId, long actorId, UUID requestId, String type, long expectedVersion,
            long assignmentVersion, Long fromAgentId, long toAgentId, String reason, Instant recordedAt) { }

    Optional<Service> service(String code);
    Optional<Service> service(long id);
    List<Service> services(long userId);
    List<Member> members(long serviceId, List<Long> orderedIds);
    List<Member> agents(long serviceId, long afterId, int count);
    List<QueueTicket> queue(long serviceId, Long assignee, long afterId, int count);
    Optional<TicketScope> ticket(long ticketId, boolean lock);
    Optional<Event> submission(long ticketId, long actorId, UUID requestId);
    List<Event> events(long ticketId, long afterId, int count);
    void change(TicketScope current, long newAssignee);
    Event append(TicketScope current, long actorId, UUID requestId, String operation, long to, String reason);
    void attachService(long ticketId, long serviceId);
    long registerService(String code, String displayName);
    void registerMember(long serviceId, long userId, String displayName, String role);
    void boundLockWait();
}
