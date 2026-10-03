package lab.helpdesk.ai.job;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcAiSuggestionJobRepository implements AiSuggestionJobRepository {

    private static final String INSERT_SQL = """
            INSERT INTO ai_suggestion_jobs (input_message_id, status)
            VALUES (?, 'PENDING')
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiSuggestionJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long enqueue(long inputMessageId) {
        if (inputMessageId <= 0) {
            throw new IllegalArgumentException("input message id must be positive");
        }

        Long id = jdbcTemplate.queryForObject(
                INSERT_SQL,
                Long.class,
                inputMessageId);

        if (id == null) {
            throw new IllegalStateException("database did not return a job id");
        }
        return id;
    }
}
