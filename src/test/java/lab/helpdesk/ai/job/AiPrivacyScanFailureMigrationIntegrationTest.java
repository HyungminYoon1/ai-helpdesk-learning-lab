package lab.helpdesk.ai.job;

import java.util.List;

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
class AiPrivacyScanFailureMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v8_adds_scan_failure_codes_without_rewriting_v7_data_or_reservations() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Flyway.configure().dataSource(dataSource).target("7").load().migrate();
        var jobs = new JdbcAiSuggestionJobRepository(jdbc,
                new AiJobPolicy("stored-policy", 3, 1, 60000, 120000, 5000, 300000));
        var execution = new JdbcAiSuggestionJobExecutionRepository(jdbc);

        long running = enqueue(jdbc, jobs);
        AiJobClaim runningClaim = claim(tx, execution);
        long completed = enqueue(jdbc, jobs);
        AiJobClaim completedClaim = claim(tx, execution);
        assertThat(completedClaim.jobId()).isEqualTo(completed);
        long suggestion = jdbc.queryForObject("INSERT INTO ticket_suggestions (job_id, summary, priority) "
                + "VALUES (?, 'Synthetic stored summary', 'NORMAL') RETURNING id", Long.class, completed);
        jdbc.update("INSERT INTO ticket_suggestion_categories (suggestion_id, category) VALUES (?, 'ACCOUNT')", suggestion);
        jdbc.update("UPDATE ai_suggestion_jobs SET status = 'SUCCEEDED', lease_expires_at = NULL, "
                + "last_failure_code = NULL, finished_at = clock_timestamp() WHERE id = ?", completed);
        long pending = enqueue(jdbc, jobs);

        for (String code : List.of("PRIVACY_SCAN_TIMEOUT", "PRIVACY_SCAN_INVALID_RESULT")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE ai_suggestion_jobs SET status = 'FAILED', "
                    + "lease_expires_at = NULL, finished_at = clock_timestamp(), last_failure_code = ? WHERE id = ?",
                    code, running)).isInstanceOf(DataIntegrityViolationException.class);
        }
        List<String> before = snapshot(jdbc);

        Flyway.configure().dataSource(dataSource).target("8").load().migrate();

        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8' AND success",
                Long.class)).isOne();
        Flyway.configure().dataSource(dataSource).load().validate();
        tx.executeWithoutResult(transaction -> assertThat(
                execution.failIfCurrent(runningClaim, AiJobFailureCode.PRIVACY_SCAN_TIMEOUT)).isTrue());
        AiJobClaim pendingClaim = claim(tx, execution);
        assertThat(pendingClaim.jobId()).isEqualTo(pending);
        tx.executeWithoutResult(transaction -> assertThat(
                execution.failIfCurrent(pendingClaim, AiJobFailureCode.PRIVACY_SCAN_INVALID_RESULT)).isTrue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_jobs WHERE last_failure_code "
                + "IN ('PRIVACY_SCAN_TIMEOUT', 'PRIVACY_SCAN_INVALID_RESULT')", Long.class)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_suggestion_jobs SET last_failure_code = 'UNKNOWN_SCAN_FAILURE' WHERE id = ?",
                running)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions", Long.class)).isOne();
    }

    private long enqueue(JdbcTemplate jdbc, JdbcAiSuggestionJobRepository jobs) {
        long ticket = jdbc.queryForObject("INSERT INTO tickets (title, status) VALUES ('Synthetic ticket', 'OPEN') RETURNING id",
                Long.class);
        long message = jdbc.queryForObject("INSERT INTO ticket_messages (ticket_id, body, author_username) "
                + "VALUES (?, 'Synthetic original message', 'synthetic-author') RETURNING id", Long.class, ticket);
        return jobs.enqueue(message);
    }

    private AiJobClaim claim(TransactionTemplate tx, JdbcAiSuggestionJobExecutionRepository execution) {
        return tx.execute(transaction -> {
            AiJobClaim claim = execution.claimNextPending().orElseThrow();
            execution.recordReservation(claim);
            return claim;
        });
    }

    private List<String> snapshot(JdbcTemplate jdbc) {
        return List.of("tickets", "ticket_messages", "ai_suggestion_jobs", "ai_suggestion_attempts",
                        "ticket_suggestions", "ticket_suggestion_categories").stream()
                .map(table -> jdbc.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(r) "
                        + "ORDER BY to_jsonb(r)::text)::text, '[]')) FROM " + table + " r", String.class)).toList();
    }
}
