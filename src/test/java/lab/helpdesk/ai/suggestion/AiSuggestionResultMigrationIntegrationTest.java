package lab.helpdesk.ai.suggestion;

import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobPolicy;
import lab.helpdesk.ai.job.JdbcAiSuggestionJobExecutionRepository;
import lab.helpdesk.ai.job.JdbcAiSuggestionJobRepository;
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
class AiSuggestionResultMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v4_preserves_a_v3_running_job_its_policy_reservation_and_original_rows() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("3").load().migrate();
        long ticketId = jdbc.queryForObject("""
                INSERT INTO tickets (title, status) VALUES (?, 'IN_PROGRESS') RETURNING id
                """, Long.class, "기존 문의");
        String originalBody = "  원문\n줄바꿈과 공백  ";
        long messageId = jdbc.queryForObject("""
                INSERT INTO ticket_messages (ticket_id, body, author_username)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, ticketId, originalBody, "synthetic-author");
        AiJobPolicy policy = new AiJobPolicy("migration-policy", 5, 2, 10000, 20000, 1000, 90000);
        long jobId = new JdbcAiSuggestionJobRepository(jdbc, policy).enqueue(messageId);
        var execution = new JdbcAiSuggestionJobExecutionRepository(jdbc);
        AiJobClaim before = new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(tx -> {
            AiJobClaim claim = execution.claimNextPending().orElseThrow();
            execution.recordReservation(claim);
            return claim;
        });

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?", String.class, messageId))
                .isEqualTo(originalBody);
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, ticketId))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT policy_version FROM ai_suggestion_jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("migration-policy");
        assertThat(jdbc.queryForObject("""
                SELECT current_attempt = 1 AND reserved_generation_count = 1
                    AND max_generation_attempts = 5 AND first_started_at = ? AND processing_deadline_at = ?
                FROM ai_suggestion_jobs WHERE id = ?
                """, Boolean.class, java.sql.Timestamp.from(before.firstStartedAt()),
                java.sql.Timestamp.from(before.processingDeadlineAt()), jobId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestion_categories", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success
                """, Long.class)).isOne();
    }
}
