package lab.helpdesk.ticket.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lab.helpdesk.ticket.CustomerMessageConfirmation;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcCustomerMessageConfirmationRepository implements CustomerMessageConfirmationRepository {
    private static final String FIELDS = "id,ticket_id,actor_user_id,client_request_id,assignment_version,through_message_id,recorded_at";
    private final JdbcTemplate jdbc;
    public JdbcCustomerMessageConfirmationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<CustomerMessageConfirmation> submission(long ticket, long actor, UUID request) {
        return jdbc.query("SELECT " + FIELDS + " FROM ticket_customer_message_confirmations WHERE ticket_id=? AND actor_user_id=? AND client_request_id=?",
                this::confirmation, ticket, actor, request).stream().findFirst();
    }

    @Override
    public Optional<CustomerMessageConfirmation> insert(long ticket, long actor, UUID request, long version, long through) {
        return jdbc.query("""
                INSERT INTO ticket_customer_message_confirmations
                    (ticket_id,actor_user_id,client_request_id,assignment_version,through_message_id)
                VALUES (?,?,?,?,?) ON CONFLICT (ticket_id,actor_user_id,client_request_id) DO NOTHING RETURNING
                """ + FIELDS, this::confirmation, ticket, actor, request, version, through).stream().findFirst();
    }

    @Override
    public Optional<Snapshot> current(long ticket) {
        // One statement snapshot: a handoff cannot mix a new assignment with an old checkpoint.
        return jdbc.query("""
                SELECT t.assignment_version,t.assignee_user_id,
                    (SELECT MAX(c.through_message_id) FROM ticket_customer_message_confirmations c
                        WHERE c.ticket_id=t.id AND c.assignment_version=t.assignment_version) AS effective_through,
                    (SELECT MAX(m.id) FROM ticket_messages m
                        WHERE m.ticket_id=t.id AND m.message_type IN ('INQUIRY','CUSTOMER_FOLLOWUP')) AS latest_customer
                FROM tickets t WHERE t.id=?
                """, (row, index) -> new Snapshot(row.getLong("assignment_version"), row.getObject("assignee_user_id",Long.class),
                        row.getObject("effective_through",Long.class), row.getObject("latest_customer",Long.class)), ticket)
                .stream().findFirst();
    }

    private CustomerMessageConfirmation confirmation(ResultSet row, int index) throws SQLException {
        return new CustomerMessageConfirmation(row.getLong("id"),row.getLong("ticket_id"),row.getLong("actor_user_id"),
                row.getObject("client_request_id",UUID.class),row.getLong("assignment_version"),row.getLong("through_message_id"),
                row.getObject("recorded_at",OffsetDateTime.class).toInstant());
    }
}
