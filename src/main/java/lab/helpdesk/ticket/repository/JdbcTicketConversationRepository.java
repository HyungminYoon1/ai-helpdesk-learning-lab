package lab.helpdesk.ticket.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lab.helpdesk.ticket.ConversationMessage;
import lab.helpdesk.ticket.MessageType;
import lab.helpdesk.ticket.TicketStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcTicketConversationRepository implements TicketConversationRepository {
    private static final String FIELDS = "id, message_type, body, created_at, corrects_message_id";
    private final JdbcTemplate jdbc;

    public JdbcTicketConversationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Header> findTicket(long id, boolean lock) {
        return jdbc.query("SELECT id,title,status,requester_user_id FROM tickets WHERE id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (row, index) -> new Header(row.getLong("id"), row.getString("title"),
                        TicketStatus.valueOf(row.getString("status")), row.getObject("requester_user_id", Long.class)), id)
                .stream().findFirst();
    }

    @Override
    public void attachReceiptOwner(long ticket, long message, long user) {
        int tickets = jdbc.update("UPDATE tickets SET requester_user_id=? WHERE id=? AND requester_user_id IS NULL", user, ticket);
        int messages = jdbc.update("UPDATE ticket_messages SET author_user_id=? WHERE id=? AND ticket_id=? AND message_type='INQUIRY'",
                user, message, ticket);
        if (tickets != 1 || messages != 1) throw new IllegalStateException("receipt identity attachment failed");
    }

    @Override
    public List<ConversationMessage> messages(long ticket, long after, int count) {
        return jdbc.query("SELECT " + FIELDS + " FROM ticket_messages WHERE ticket_id=? AND id>? ORDER BY id LIMIT ?",
                this::readMessage, ticket, after, count);
    }

    @Override
    public Optional<ConversationMessage> submission(long ticket, long author, UUID request) {
        return jdbc.query("SELECT " + FIELDS + " FROM ticket_messages WHERE ticket_id=? AND author_user_id=? AND client_request_id=?",
                this::readMessage, ticket, author, request).stream().findFirst();
    }

    @Override
    public Optional<ConversationMessage> message(long ticket, long id) {
        return jdbc.query("SELECT " + FIELDS + " FROM ticket_messages WHERE ticket_id=? AND id=?",
                this::readMessage, ticket, id).stream().findFirst();
    }

    @Override
    public Optional<ConversationMessage> insertReply(long ticket, long author, String authorName,
            String body, UUID request, Long correction) {
        return jdbc.query("""
                INSERT INTO ticket_messages
                    (ticket_id,body,author_username,author_user_id,message_type,client_request_id,corrects_message_id)
                VALUES (?, ?, ?, ?, 'OFFICIAL_REPLY', ?, ?)
                ON CONFLICT (ticket_id,author_user_id,client_request_id) WHERE client_request_id IS NOT NULL
                DO NOTHING RETURNING
                """ + FIELDS, this::readMessage, ticket, body, authorName, author, request, correction)
                .stream().findFirst();
    }

    private ConversationMessage readMessage(ResultSet row, int index) throws SQLException {
        return new ConversationMessage(row.getLong("id"), MessageType.valueOf(row.getString("message_type")),
                row.getString("body"), row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("corrects_message_id", Long.class));
    }
}
