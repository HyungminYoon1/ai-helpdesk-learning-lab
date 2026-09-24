package lab.helpdesk.ticket.repository;

import java.util.Optional;

import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcTicketRepository implements TicketRepository {

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
