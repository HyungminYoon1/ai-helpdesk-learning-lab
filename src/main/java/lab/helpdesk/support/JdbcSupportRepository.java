package lab.helpdesk.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcSupportRepository implements SupportRepository {
    private final JdbcTemplate jdbc;
    public JdbcSupportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static final String EVENT_FIELDS = "id,ticket_id,actor_user_id,client_request_id,operation,expected_version,"
            + "resulting_version,from_user_id,to_user_id,reason,recorded_at";

    public Optional<Service> service(String code) {
        return jdbc.query("SELECT id,code,display_name FROM support_services WHERE code=?",
                (r,i) -> new Service(r.getLong(1),r.getString(2),r.getString(3)),code).stream().findFirst();
    }
    public Optional<Service> service(long id) {
        return jdbc.query("SELECT id,code,display_name FROM support_services WHERE id=?",
                (r,i) -> new Service(r.getLong(1),r.getString(2),r.getString(3)),id).stream().findFirst();
    }
    public List<Service> services(long user) {
        return jdbc.query("""
                SELECT s.id,s.code,s.display_name FROM support_services s
                JOIN support_service_memberships m ON m.service_id=s.id
                WHERE m.user_id=? AND m.active ORDER BY s.id FOR SHARE OF m
                """, (r,i) -> new Service(r.getLong(1),r.getString(2),r.getString(3)),user);
    }
    public List<Member> members(long service, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(),"?"));
        var args = new java.util.ArrayList<Object>(); args.add(service); args.addAll(ids);
        return jdbc.query("SELECT user_id,display_name,support_role,active FROM support_service_memberships"
                + " WHERE service_id=? AND user_id IN (" + marks + ") ORDER BY user_id FOR SHARE",
                (r,i) -> new Member(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4)),args.toArray());
    }
    public List<Member> agents(long service, long after, int count) {
        return jdbc.query("""
                SELECT user_id,display_name,support_role,active FROM support_service_memberships
                WHERE service_id=? AND active AND user_id>? ORDER BY user_id LIMIT ?
                """, (r,i) -> new Member(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4)),service,after,count);
    }
    public List<QueueTicket> queue(long service, Long assignee, long after, int count) {
        return jdbc.query("SELECT t.id,t.title,t.status,(SELECT min(m.created_at) FROM ticket_messages m WHERE m.ticket_id=t.id),"
                + "t.assignment_version FROM tickets t WHERE service_id=? AND "
                + (assignee == null ? "assignee_user_id IS NULL" : "assignee_user_id=?") + " AND id>? ORDER BY id LIMIT ?",
                (r,i) -> new QueueTicket(r.getLong(1),r.getString(2),r.getString(3),
                        r.getObject(4,OffsetDateTime.class) == null ? null : r.getObject(4,OffsetDateTime.class).toInstant(),r.getLong(5)),
                assignee == null ? new Object[]{service,after,count} : new Object[]{service,assignee,after,count});
    }
    public Optional<TicketScope> ticket(long id, boolean lock) {
        return jdbc.query("SELECT id,service_id,assignee_user_id,assignment_version FROM tickets WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), (r,i) -> new TicketScope(r.getLong(1),
                        r.getObject(2,Long.class),r.getObject(3,Long.class),r.getLong(4)),id).stream().findFirst();
    }
    public Optional<Event> submission(long ticket, long actor, UUID request) {
        return jdbc.query("SELECT " + EVENT_FIELDS + " FROM ticket_assignment_events WHERE ticket_id=? AND actor_user_id=? AND client_request_id=?",
                this::event,ticket,actor,request).stream().findFirst();
    }
    public List<Event> events(long ticket,long after,int count) {
        return jdbc.query("SELECT " + EVENT_FIELDS + " FROM ticket_assignment_events WHERE ticket_id=? AND id>? ORDER BY id LIMIT ?",
                this::event,ticket,after,count);
    }
    public void change(TicketScope current,long assignee) {
        int rows = jdbc.update("UPDATE tickets SET assignee_user_id=?,assignment_version=assignment_version+1 WHERE id=? AND assignment_version=?",
                assignee,current.ticketId(),current.version());
        if (rows != 1) throw new IllegalStateException("assignment update failed");
    }
    public Event append(TicketScope current,long actor,UUID request,String operation,long to,String reason) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_assignment_events(service_id,ticket_id,actor_user_id,client_request_id,operation,
                    expected_version,resulting_version,from_user_id,to_user_id,reason)
                VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING
                """ + EVENT_FIELDS,this::event,current.serviceId(),current.ticketId(),actor,request,operation,
                current.version(),Math.addExact(current.version(),1),current.assigneeAgentId(),to,reason);
    }
    public void attachService(long ticket,long service) {
        if (jdbc.update("UPDATE tickets SET service_id=? WHERE id=? AND service_id IS NULL",service,ticket) != 1)
            throw new IllegalStateException("receipt service attachment failed");
    }
    public long registerService(String code,String display) {
        jdbc.update("INSERT INTO support_services(code,display_name) VALUES (?,?) ON CONFLICT(code) DO NOTHING",code,display);
        return service(code).orElseThrow().id();
    }
    public void registerMember(long service,long user,String display,String role) {
        jdbc.update("""
                INSERT INTO support_service_memberships(service_id,user_id,display_name,support_role)
                VALUES (?,?,?,?) ON CONFLICT(service_id,user_id) DO NOTHING
                """,service,user,display,role);
    }
    public void boundLockWait() { jdbc.execute("SET LOCAL lock_timeout='2s'"); }
    private Event event(ResultSet r,int i) throws SQLException {
        return new Event(r.getLong("id"),r.getLong("ticket_id"),r.getLong("actor_user_id"),
                r.getObject("client_request_id",UUID.class),r.getString("operation"),r.getLong("expected_version"),
                r.getLong("resulting_version"),r.getObject("from_user_id",Long.class),r.getLong("to_user_id"),
                r.getString("reason"),r.getObject("recorded_at",OffsetDateTime.class).toInstant());
    }
}
