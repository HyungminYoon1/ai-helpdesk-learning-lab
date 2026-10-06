package lab.helpdesk.ai.job;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AiStorageRetryMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v6_preserves_v5_receipts_policy_reservations_and_completed_suggestions() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Flyway.configure().dataSource(dataSource).target("5").load().migrate();
        long ticketId = jdbc.queryForObject("INSERT INTO tickets (title, status) VALUES ('Synthetic existing ticket', 'OPEN') RETURNING id",
                Long.class);
        long messageId = jdbc.queryForObject("INSERT INTO ticket_messages (ticket_id, body, author_username) VALUES (?, ?, ?) RETURNING id",
                Long.class, ticketId, "Original message", "synthetic-author");
        var jobs = new JdbcAiSuggestionJobRepository(jdbc,
                new AiJobPolicy("stored-policy", 3, 1, 60000, 120000, 5000, 300000));
        long jobId = jobs.enqueue(messageId);
        var execution = new JdbcAiSuggestionJobExecutionRepository(jdbc);
        tx.executeWithoutResult(transaction -> {
            AiJobClaim claim = execution.claimNextPending().orElseThrow();
            execution.recordReservation(claim);
            long suggestionId = jdbc.queryForObject("INSERT INTO ticket_suggestions (job_id, summary, priority) "
                    + "VALUES (?, 'Synthetic existing summary', 'NORMAL') RETURNING id", Long.class, jobId);
            jdbc.update("INSERT INTO ticket_suggestion_categories (suggestion_id, category) VALUES (?, 'ACCOUNT')", suggestionId);
            jdbc.update("UPDATE ai_suggestion_jobs SET status = 'SUCCEEDED', lease_expires_at = NULL, "
                    + "last_failure_code = NULL, finished_at = clock_timestamp() WHERE id = ?", jobId);
        });
        var jobBefore = jdbc.queryForMap("SELECT * FROM ai_suggestion_jobs WHERE id = ?", jobId);
        var reservationBefore = jdbc.queryForMap("SELECT * FROM ai_suggestion_attempts WHERE job_id = ?", jobId);
        var suggestionBefore = jdbc.queryForMap("SELECT * FROM ticket_suggestions WHERE job_id = ?", jobId);
        long secondMessage = jdbc.queryForObject("INSERT INTO ticket_messages (ticket_id, body, author_username) "
                + "VALUES (?, 'Second original message', 'synthetic-author') RETURNING id", Long.class, ticketId);
        long secondJob = jobs.enqueue(secondMessage);
        AiJobClaim secondClaim = tx.execute(transaction -> {
            AiJobClaim claim = execution.claimNextPending().orElseThrow();
            assertThat(claim.jobId()).isEqualTo(secondJob);
            execution.recordReservation(claim);
            return claim;
        });
        var runningBefore = jdbc.queryForMap("SELECT * FROM ai_suggestion_jobs WHERE id = ?", secondJob);
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_suggestion_jobs SET status = 'FAILED', lease_expires_at = NULL, "
                + "finished_at = clock_timestamp(), last_failure_code = 'RESULT_STORAGE_RETRY_EXHAUSTED' WHERE id = ?", secondJob))
                .isInstanceOf(DataIntegrityViolationException.class);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForMap("SELECT * FROM ai_suggestion_jobs WHERE id = ?", jobId)).isEqualTo(jobBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM ai_suggestion_attempts WHERE job_id = ?", jobId)).isEqualTo(reservationBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM ticket_suggestions WHERE job_id = ?", jobId)).isEqualTo(suggestionBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM ai_suggestion_jobs WHERE id = ?", secondJob)).isEqualTo(runningBefore);
        assertThat(jdbc.queryForObject("SELECT category FROM ticket_suggestion_categories", String.class)).isEqualTo("ACCOUNT");
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?", String.class, messageId))
                .isEqualTo("Original message");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success", Long.class))
                .isOne();

        tx.executeWithoutResult(transaction ->
                assertThat(execution.failIfCurrent(secondClaim, AiJobFailureCode.RESULT_STORAGE_RETRY_EXHAUSTED)).isTrue());
        assertThat(jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?", String.class, secondJob))
                .isEqualTo("RESULT_STORAGE_RETRY_EXHAUSTED");
    }
}
