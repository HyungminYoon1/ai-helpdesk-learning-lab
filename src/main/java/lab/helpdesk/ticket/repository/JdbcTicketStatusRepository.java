package lab.helpdesk.ticket.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lab.helpdesk.support.SupportRepository.TicketScope;
import lab.helpdesk.ticket.TicketStatus;
import lab.helpdesk.ticket.TicketStatusAction;
import lab.helpdesk.ticket.TicketStatusChange;
import lab.helpdesk.ticket.TicketStatusChange.Scope;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcTicketStatusRepository implements TicketStatusRepository {
    private static final String FIELDS="id,ticket_id,actor_user_id,client_request_id,request_scope,operation,"
            + "expected_status_version,resulting_status_version,expected_assignment_version,assignment_version,"
            + "from_status,to_status,reason,related_reply_message_id,through_customer_message_id,recorded_at";
    private final JdbcTemplate jdbc;
    public JdbcTicketStatusRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public void boundLockWait() { jdbc.execute("SET LOCAL lock_timeout='2s'"); }
    @Override public boolean lock(long ticketId) {
        return !jdbc.query("SELECT id FROM tickets WHERE id=? FOR UPDATE",(r,i)->r.getLong(1),ticketId).isEmpty();
    }
    @Override public Optional<Snapshot> snapshot(long ticketId) {
        // Write callers execute this fresh statement AFTER obtaining the parent lock.
        return jdbc.query("""
                SELECT t.id,t.title,t.status,t.status_version,t.service_id,t.assignee_user_id,t.assignment_version,t.requester_user_id,
                    (SELECT MAX(m.id) FROM ticket_messages m WHERE m.ticket_id=t.id
                        AND m.message_type IN ('INQUIRY','CUSTOMER_FOLLOWUP')) AS latest_customer,
                    (SELECT MAX(c.through_message_id) FROM ticket_customer_message_confirmations c
                        WHERE c.ticket_id=t.id AND c.assignment_version=t.assignment_version) AS effective_through
                FROM tickets t WHERE t.id=?
                """,(r,i)->new Snapshot(new TicketScope(r.getLong("id"),r.getObject("service_id",Long.class),
                        r.getObject("assignee_user_id",Long.class),r.getLong("assignment_version"),r.getObject("requester_user_id",Long.class)),
                        r.getString("title"),TicketStatus.valueOf(r.getString("status")),r.getLong("status_version"),
                        r.getObject("latest_customer",Long.class),r.getObject("effective_through",Long.class)),ticketId).stream().findFirst();
    }
    @Override public Optional<TicketStatusChange> submission(long ticket,long actor,UUID key) {
        return jdbc.query("SELECT "+FIELDS+" FROM ticket_status_events WHERE ticket_id=? AND actor_user_id=? AND client_request_id=?",
                this::event,ticket,actor,key).stream().findFirst();
    }
    @Override public List<TicketStatusChange> events(long ticket,long after,int count) {
        return jdbc.query("SELECT "+FIELDS+" FROM ticket_status_events WHERE ticket_id=? AND id>? ORDER BY id LIMIT ?",
                this::event,ticket,after,count);
    }
    @Override public void change(Snapshot before,TicketStatus next) {
        if (jdbc.update("UPDATE tickets SET status=?,status_version=status_version+1 WHERE id=? AND status_version=? AND status=?",
                next.name(),before.scope().ticketId(),before.statusVersion(),before.status().name())!=1)
            throw new IllegalStateException("status update failed");
    }
    @Override public TicketStatusChange append(Snapshot before,long actor,UUID key,Scope scope,TicketStatusAction action,TicketStatus next,
            Long expectedAssignment,String reason,Long reply,Long through) {
        return jdbc.queryForObject("""
                INSERT INTO ticket_status_events(ticket_id,actor_user_id,client_request_id,request_scope,operation,
                    expected_status_version,resulting_status_version,expected_assignment_version,assignment_version,
                    from_status,to_status,reason,related_reply_message_id,through_customer_message_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING
                """+FIELDS,this::event,before.scope().ticketId(),actor,key,scope.name(),action.name(),before.statusVersion(),
                Math.addExact(before.statusVersion(),1),expectedAssignment,before.scope().version(),before.status().name(),next.name(),reason,reply,through);
    }
    private TicketStatusChange event(ResultSet r,int i)throws SQLException {
        return new TicketStatusChange(r.getLong("id"),r.getLong("ticket_id"),r.getLong("actor_user_id"),
                r.getObject("client_request_id",UUID.class),Scope.valueOf(r.getString("request_scope")),TicketStatusAction.valueOf(r.getString("operation")),
                r.getLong("expected_status_version"),r.getLong("resulting_status_version"),r.getObject("expected_assignment_version",Long.class),
                r.getLong("assignment_version"),TicketStatus.valueOf(r.getString("from_status")),TicketStatus.valueOf(r.getString("to_status")),
                r.getString("reason"),r.getObject("related_reply_message_id",Long.class),r.getObject("through_customer_message_id",Long.class),
                r.getObject("recorded_at",OffsetDateTime.class).toInstant());
    }
}
