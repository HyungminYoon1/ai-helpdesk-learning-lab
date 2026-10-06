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
class AiAttemptResultMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v7_holds_legacy_attempts_preserves_receipts_and_starts_new_reservations_unconfirmed() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Flyway.configure().dataSource(dataSource).target("6").load().migrate();
        long ticketId = jdbc.queryForObject("INSERT INTO tickets (title, status) VALUES ('Synthetic old ticket', 'OPEN') RETURNING id",
                Long.class);
        long messageId = jdbc.queryForObject("INSERT INTO ticket_messages (ticket_id, body, author_username) "
                + "VALUES (?, 'Original stored message', 'synthetic-author') RETURNING id", Long.class, ticketId);
        var jobs = new JdbcAiSuggestionJobRepository(jdbc,
                new AiJobPolicy("stored-policy", 3, 1, 60000, 120000, 5000, 300000));
        long oldJob = jobs.enqueue(messageId);
        var execution = new JdbcAiSuggestionJobExecutionRepository(jdbc);
        AiJobClaim oldClaim = tx.execute(transaction -> {
            var claim = execution.claimNextPending().orElseThrow();
            execution.recordReservation(claim);
            return claim;
        });
        var jobBefore = jdbc.queryForMap("SELECT * FROM ai_suggestion_jobs WHERE id = ?", oldJob);
        var attemptBefore = jdbc.queryForMap("SELECT * FROM ai_suggestion_attempts WHERE job_id = ?", oldJob);
        var messageBefore = jdbc.queryForMap("SELECT * FROM ticket_messages WHERE id = ?", messageId);

        Flyway.configure().dataSource(dataSource).target("7").load().migrate();

        assertThat(jdbc.queryForMap("SELECT * FROM ai_suggestion_jobs WHERE id = ?", oldJob)).isEqualTo(jobBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM ticket_messages WHERE id = ?", messageId)).isEqualTo(messageBefore);
        var attemptAfter = jdbc.queryForMap("SELECT * FROM ai_suggestion_attempts WHERE job_id = ?", oldJob);
        assertThat(attemptAfter.remove("result_code")).isEqualTo("AUTO_RETRY_BLOCKED");
        assertThat(attemptAfter).isEqualTo(attemptBefore);
        jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE id = ?", oldJob);
        java.util.Optional<AiJobClaim> legacyRecovery = tx.execute(transaction ->
                execution.claimRecovery(oldJob, oldClaim.attemptNumber()));
        assertThat(legacyRecovery).isEmpty();

        long newMessage = jdbc.queryForObject("INSERT INTO ticket_messages (ticket_id, body, author_username) "
                + "VALUES (?, 'New original message', 'synthetic-author') RETURNING id", Long.class, ticketId);
        long newJob = jobs.enqueue(newMessage);
        tx.executeWithoutResult(transaction -> {
            var newClaim = execution.claimNextPending().orElseThrow();
            assertThat(newClaim.jobId()).isEqualTo(newJob);
            execution.recordReservation(newClaim);
        });
        assertThat(jdbc.queryForObject("SELECT result_code FROM ai_suggestion_attempts WHERE job_id = ?", String.class, newJob))
                .isEqualTo("UNCONFIRMED");
        assertThat(jdbc.update("UPDATE ai_suggestion_attempts SET result_code = 'OUTCOME_UNKNOWN' WHERE job_id = ?", newJob)).isOne();
        assertThat(jdbc.update("UPDATE ai_suggestion_attempts SET result_code = 'AUTO_RETRY_BLOCKED' WHERE job_id = ?", newJob)).isOne();
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_suggestion_attempts SET result_code = 'PROVIDER_NOT_EXECUTED' WHERE job_id = ?",
                newJob)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_suggestion_attempts SET result_code = NULL WHERE job_id = ?", newJob))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success", Long.class))
                .isOne();
    }
}
