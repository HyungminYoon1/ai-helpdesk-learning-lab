package lab.helpdesk.ticket.repository;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcTicketRepository implements TicketRepository, TicketReceiptRepository {

    private static final String INSERT_SQL = """
            INSERT INTO tickets (title, status)
            VALUES (?, ?)
            RETURNING id
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT title, status
            FROM tickets
            WHERE id = ?
            """;

    private static final String INSERT_RECEIPT_SQL = """
            INSERT INTO tickets (title, status, requester_user_id, service_id, receipt_request_id)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (requester_user_id, receipt_request_id) WHERE receipt_request_id IS NOT NULL
            DO NOTHING
            RETURNING id
            """;

    private static final String FIND_RECEIPT_SQL = """
            SELECT t.id AS ticket_id, t.title, t.status, m.id AS message_id, m.body, j.id AS job_id
            FROM tickets t
            JOIN ticket_messages m ON m.ticket_id = t.id AND m.message_type = 'INQUIRY'
                AND m.author_user_id = t.requester_user_id
            JOIN ai_suggestion_jobs j ON j.input_message_id = m.id
            WHERE t.requester_user_id = ? AND t.receipt_request_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long save(Ticket ticket) {
        Long id = jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                ticket.title(),
                ticket.status().name());

        if (id == null) {
            throw new IllegalStateException(
                    "database did not return a ticket id");
        }

        return id;
    }

    @Override
    public OptionalLong insertReceipt(Ticket ticket, long requesterUserId, long serviceId, UUID clientRequestId) {
        return jdbcTemplate.query(INSERT_RECEIPT_SQL, (row, index) -> row.getLong(1),
                ticket.title(), ticket.status().name(), requesterUserId, serviceId, clientRequestId)
                .stream().mapToLong(Long::longValue).findFirst();
    }

    @Override
    public Optional<StoredReceipt> findReceipt(long requesterUserId, UUID clientRequestId) {
        return DataAccessUtils.optionalResult(jdbcTemplate.query(FIND_RECEIPT_SQL,
                (row, index) -> new StoredReceipt(
                        row.getLong("ticket_id"),
                        Ticket.restore(row.getString("title"), TicketStatus.valueOf(row.getString("status"))),
                        row.getLong("message_id"), row.getString("body"), row.getLong("job_id")),
                requesterUserId, clientRequestId));
    }

    @Override
    public Optional<Ticket> findById(long id) {
        return jdbcTemplate.query(
                        FIND_BY_ID_SQL,
                        (resultSet, rowNumber) -> Ticket.restore(
                                resultSet.getString("title"),
                                TicketStatus.valueOf(
                                        resultSet.getString("status"))),
                        id)
                .stream()
                .findFirst();
    }
}
