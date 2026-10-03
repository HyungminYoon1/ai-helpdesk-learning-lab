package lab.helpdesk.ticket.repository;

import java.util.Objects;

import lab.helpdesk.ticket.TicketMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcTicketMessageRepository implements TicketMessageRepository {

    private static final String INSERT_SQL = """
            INSERT INTO ticket_messages (ticket_id, body, author_username)
            VALUES (?, ?, ?)
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTicketMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long save(long ticketId, TicketMessage message) {
        if (ticketId <= 0) {
            throw new IllegalArgumentException("ticket id must be positive");
        }
        Objects.requireNonNull(message, "message must not be null");

        Long id = jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                ticketId,
                message.body(),
                message.authorUsername());

        if (id == null) {
            throw new IllegalStateException("database did not return a message id");
        }
        return id;
    }
}
