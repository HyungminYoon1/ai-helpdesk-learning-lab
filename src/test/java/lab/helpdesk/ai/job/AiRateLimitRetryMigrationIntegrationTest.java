package lab.helpdesk.ai.job;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AiRateLimitRetryMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v5_preserves_a_v4_job_policy_original_message_and_existing_reservation() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("4").load().migrate();
        long ticketId = jdbc.queryForObject("INSERT INTO tickets (title, status) VALUES ('Synthetic existing ticket', 'OPEN') RETURNING id",
                Long.class);
        long messageId = jdbc.queryForObject("INSERT INTO ticket_messages (ticket_id, body, author_username) VALUES (?, ?, ?) RETURNING id",
                Long.class, ticketId, "Original message", "synthetic-author");
        long jobId = new JdbcAiSuggestionJobRepository(jdbc,
                new AiJobPolicy("stored-policy", 3, 1, 60000, 120000, 5000, 300000)).enqueue(messageId);
        var execution = new JdbcAiSuggestionJobExecutionRepository(jdbc);
        AiJobClaim before = new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(tx -> {
            AiJobClaim claim = execution.claimNextPending().orElseThrow();
            execution.recordReservation(claim);
            return claim;
        });

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?", String.class, messageId))
                .isEqualTo("Original message");
        assertThat(jdbc.queryForObject("SELECT policy_version FROM ai_suggestion_jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("stored-policy");
        assertThat(jdbc.queryForObject("SELECT reserved_generation_count FROM ai_suggestion_jobs WHERE id = ?", Integer.class, jobId))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts WHERE job_id = ?", Long.class, jobId))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT processing_deadline_at = ? FROM ai_suggestion_jobs WHERE id = ?",
                Boolean.class, java.sql.Timestamp.from(before.processingDeadlineAt()), jobId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success", Long.class))
                .isOne();
        assertThat(jdbc.update("UPDATE ai_suggestion_jobs SET next_request_kind = 'TEMPORARY_RETRY', "
                + "last_failure_code = 'PROVIDER_RATE_LIMITED' WHERE id = ?", jobId)).isOne();
    }
}
