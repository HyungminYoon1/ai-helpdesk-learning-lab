package lab.helpdesk.ticket.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
class JdbcTicketRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired
    private TicketRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void deleteTickets() {
        jdbcTemplate.update("DELETE FROM tickets");
    }

    @Test
    void postgres_profile_selects_jdbc_adapter() {
        assertThat(repository)
                .isInstanceOf(JdbcTicketRepository.class);
    }

    @Test
    void ticket_is_inserted_and_restored_from_postgresql() {
        Ticket ticket = new Ticket("로그인 오류");
        ticket.startProgress();

        long id = repository.save(ticket);

        assertThat(id).isPositive();
        assertThat(repository.findById(id))
                .get()
                .satisfies(restored -> {
                    assertThat(restored.title())
                            .isEqualTo("로그인 오류");
                    assertThat(restored.status())
                            .isEqualTo(TicketStatus.IN_PROGRESS);
                });
    }

    @Test
    void missing_ticket_returns_empty_optional() {
        assertThat(repository.findById(Long.MAX_VALUE))
                .isEmpty();
    }

    @Test
    void migration_rejects_blank_title() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO tickets (title, status)
                VALUES (?, ?)
                """,
                "   ",
                TicketStatus.OPEN.name()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migration_rejects_unknown_status() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO tickets (title, status)
                VALUES (?, ?)
                """,
                "로그인 오류",
                "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transaction_rolls_back_first_insert_when_second_insert_fails() {
        assertThatThrownBy(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    int insertedRows = jdbcTemplate.update(
                            """
                            INSERT INTO tickets (title, status)
                            VALUES (?, ?)
                            """,
                            "정상 Ticket",
                            TicketStatus.OPEN.name());

                    assertThat(insertedRows).isOne();

                    jdbcTemplate.update(
                            """
                            INSERT INTO tickets (title, status)
                            VALUES (?, ?)
                            """,
                            "   ",
                            TicketStatus.OPEN.name());
                }))
                .isInstanceOf(DataIntegrityViolationException.class);

        Long ticketCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tickets",
                Long.class);

        assertThat(ticketCount).isZero();
    }
}
