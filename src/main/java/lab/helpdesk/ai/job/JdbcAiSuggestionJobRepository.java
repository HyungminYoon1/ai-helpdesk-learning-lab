package lab.helpdesk.ai.job;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcAiSuggestionJobRepository implements AiSuggestionJobRepository {

    private static final String INSERT_SQL = """
            INSERT INTO ai_suggestion_jobs (
                input_message_id, status, policy_version, policy_snapshot_source,
                max_generation_attempts, max_output_repair_attempts,
                request_timeout_ms, attempt_lease_ms, retry_backoff_ms,
                job_processing_timeout_ms)
            VALUES (?, 'PENDING', ?, 'APPLICATION', ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AiJobPolicy policy;

    public JdbcAiSuggestionJobRepository(JdbcTemplate jdbcTemplate, AiJobPolicy policy) {
        this.jdbcTemplate = jdbcTemplate;
        this.policy = policy;
    }

    @Override
    public long enqueue(long inputMessageId) {
        if (inputMessageId <= 0) {
            throw new IllegalArgumentException("input message id must be positive");
        }

        Long id = jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                inputMessageId,
                policy.policyVersion(),
                policy.maxGenerationAttempts(),
                policy.maxOutputRepairAttempts(),
                policy.requestTimeoutMs(),
                policy.attemptLeaseMs(),
                policy.retryBackoffMs(),
                policy.jobProcessingTimeoutMs());

        if (id == null) {
            throw new IllegalStateException("database did not return a job id");
        }
        return id;
    }
}
