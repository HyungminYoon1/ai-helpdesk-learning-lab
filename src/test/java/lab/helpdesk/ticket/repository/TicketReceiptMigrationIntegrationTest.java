package lab.helpdesk.ticket.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TicketReceiptMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v2_preserves_ticket_created_before_message_and_job_tables_exist() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).target("1").load().migrate();
        Long oldTicketId = jdbcTemplate.queryForObject(
                """
                INSERT INTO tickets (title, status)
                VALUES (?, ?) RETURNING id
                """,
                Long.class, "기존 문의", "IN_PROGRESS");

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM tickets WHERE id = ?", String.class, oldTicketId))
                .isEqualTo("기존 문의");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tickets WHERE id = ?", String.class, oldTicketId))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_messages", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_suggestion_jobs", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success",
                Long.class)).isOne();
    }
}
