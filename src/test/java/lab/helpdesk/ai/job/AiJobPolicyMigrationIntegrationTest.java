package lab.helpdesk.ai.job;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AiJobPolicyMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Test
    void v3_migrates_a_v2_pending_job_without_rewriting_the_original_message() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbcTemplate = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("2").load().migrate();
        Long ticketId = jdbcTemplate.queryForObject("""
                INSERT INTO tickets (title, status) VALUES (?, 'IN_PROGRESS') RETURNING id
                """, Long.class, "기존 문의");
        String originalBody = "  원문 줄바꿈\n공백을 유지합니다.  ";
        Long messageId = jdbcTemplate.queryForObject("""
                INSERT INTO ticket_messages (ticket_id, body, author_username)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, ticketId, originalBody, "synthetic-author");
        Long jobId = jdbcTemplate.queryForObject("""
                INSERT INTO ai_suggestion_jobs (input_message_id, status)
                VALUES (?, 'PENDING') RETURNING id
                """, Long.class, messageId);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertThat(jdbcTemplate.queryForObject("SELECT body FROM ticket_messages WHERE id = ?",
                String.class, messageId)).isEqualTo(originalBody);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM tickets WHERE id = ?",
                String.class, ticketId)).isEqualTo("IN_PROGRESS");
        assertThat(jdbcTemplate.queryForObject("SELECT input_message_id FROM ai_suggestion_jobs WHERE id = ?",
                Long.class, jobId)).isEqualTo(messageId);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?",
                String.class, jobId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT policy_snapshot_source FROM ai_suggestion_jobs WHERE id = ?",
                String.class, jobId)).isEqualTo("V2_MIGRATION");
        assertThat(jdbcTemplate.queryForObject("SELECT max_generation_attempts FROM ai_suggestion_jobs WHERE id = ?",
                Integer.class, jobId)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT max_output_repair_attempts FROM ai_suggestion_jobs WHERE id = ?",
                Integer.class, jobId)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT current_attempt = 0 AND reserved_generation_count = 0
                    AND first_started_at IS NULL AND processing_deadline_at IS NULL
                FROM ai_suggestion_jobs WHERE id = ?
                """, Boolean.class, jobId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success
                """, Long.class)).isOne();
    }
}
