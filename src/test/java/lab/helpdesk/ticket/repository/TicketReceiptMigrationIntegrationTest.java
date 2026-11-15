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

    @Test
    void v14_preserves_v13_receipt_rows_and_leaves_the_historical_key_empty() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).schemas("receipt_v13").target("13").load().migrate();

        long owner = jdbc.queryForObject("""
                INSERT INTO receipt_v13.helpdesk_user_identities (account_key, login_username)
                VALUES (gen_random_uuid(), 'historical-customer') RETURNING id
                """, Long.class);
        long service = jdbc.queryForObject("""
                INSERT INTO receipt_v13.support_services (code, display_name)
                VALUES ('historical', 'Historical support') RETURNING id
                """, Long.class);
        long ticket = jdbc.queryForObject("""
                INSERT INTO receipt_v13.tickets (title, status, requester_user_id, service_id)
                VALUES ('기존 문의', 'IN_PROGRESS', ?, ?) RETURNING id
                """, Long.class, owner, service);
        String original = "  기존 원문\n보존  ";
        long message = jdbc.queryForObject("""
                INSERT INTO receipt_v13.ticket_messages (ticket_id, body, author_username, author_user_id)
                VALUES (?, ?, 'historical-customer', ?) RETURNING id
                """, Long.class, ticket, original, owner);
        long job = jdbc.queryForObject("""
                INSERT INTO receipt_v13.ai_suggestion_jobs (input_message_id, status)
                VALUES (?, 'PENDING') RETURNING id
                """, Long.class, message);
        String snapshot = """
                SELECT t.id, t.title, t.status, t.requester_user_id, t.service_id,
                       m.id AS message_id, m.body, m.author_user_id, j.id AS job_id, j.status AS job_status
                  FROM receipt_v13.tickets t
                  JOIN receipt_v13.ticket_messages m ON m.ticket_id = t.id
                  JOIN receipt_v13.ai_suggestion_jobs j ON j.input_message_id = m.id
                 WHERE t.id = ? AND m.id = ? AND j.id = ?
                """;
        var before = jdbc.queryForMap(snapshot, ticket, message, job);

        Flyway.configure().dataSource(dataSource).schemas("receipt_v13").load().migrate();

        assertThat(jdbc.queryForMap(snapshot, ticket, message, job)).isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT receipt_request_id IS NULL FROM receipt_v13.tickets WHERE id = ?
                """, Boolean.class, ticket)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM receipt_v13.flyway_schema_history WHERE version = '14' AND success
                """, Long.class)).isOne();
    }
}
