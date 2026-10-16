package lab.helpdesk.ai.job;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RollbackCloudFixtureIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
            .withDatabaseName("helpdesk");

    @Test
    void labelled_fixture_requires_v8_preserves_original_rows_and_rejects_a_second_execution() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("7").load().migrate();
        long ticket = jdbc.queryForObject("INSERT INTO tickets (title, status) "
                + "VALUES ('Existing synthetic receipt', 'OPEN') RETURNING id", Long.class);
        long message = jdbc.queryForObject("INSERT INTO ticket_messages (ticket_id, body, author_username) "
                + "VALUES (?, 'Original synthetic message', 'synthetic-authenticated-author') RETURNING id",
                Long.class, ticket);
        jdbc.update("INSERT INTO ai_suggestion_jobs (input_message_id, status) VALUES (?, 'PENDING')", message);
        var before = originalRows(jdbc, ticket, message);
        String fixture = Files.readString(Path.of("deploy/rollback/cloud-compatibility-fixture.sql"),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> jdbc.execute(fixture))
                .hasMessageContaining("ROLLBACK_FIXTURE_V8_DATABASE_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Long.class)).isOne();
        assertThat(originalRows(jdbc, ticket, message)).isEqualTo(before);

        Flyway.configure().dataSource(dataSource).target("8").load().migrate();
        jdbc.execute(fixture);
        assertThat(originalRows(jdbc, ticket, message)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT last_failure_code FROM ai_suggestion_jobs "
                + "WHERE policy_version = 'rollback-fixture-v1' ORDER BY last_failure_code", String.class))
                .containsExactly("PRIVACY_SCAN_INVALID_RESULT", "PRIVACY_SCAN_TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_jobs "
                + "WHERE policy_version = 'rollback-fixture-v1' AND status = 'FAILED' "
                + "AND current_attempt = 1 AND reserved_generation_count = 1", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts "
                + "WHERE result_code = 'AUTO_RETRY_BLOCKED'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions", Long.class)).isZero();

        assertThatThrownBy(() -> jdbc.execute(fixture))
                .hasMessageContaining("ROLLBACK_FIXTURE_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Long.class)).isEqualTo(3);
        assertThat(originalRows(jdbc, ticket, message)).isEqualTo(before);
    }

    private List<String> originalRows(JdbcTemplate jdbc, long ticket, long message) {
        return List.of(
                jdbc.queryForObject("SELECT to_jsonb(t)::text FROM tickets t WHERE id = ?", String.class, ticket),
                jdbc.queryForObject("SELECT to_jsonb(m)::text FROM ticket_messages m WHERE id = ?", String.class, message),
                jdbc.queryForObject("SELECT to_jsonb(j)::text FROM ai_suggestion_jobs j "
                        + "WHERE input_message_id = ?", String.class, message));
    }
}
