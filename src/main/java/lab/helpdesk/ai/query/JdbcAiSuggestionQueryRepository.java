package lab.helpdesk.ai.query;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcAiSuggestionQueryRepository implements AiSuggestionQueryRepository {

    private static final String FIND_SQL = """
            SELECT t.id AS ticket_id, j.id AS job_id, j.status AS job_status,
                   j.last_failure_code, s.id AS suggestion_id, s.summary, s.priority,
                   s.review_status, s.created_at, c.category
            FROM tickets t
            LEFT JOIN ticket_messages m ON m.id = (
                SELECT first_message.id FROM ticket_messages first_message
                WHERE first_message.ticket_id = t.id ORDER BY first_message.id LIMIT 1
            )
            LEFT JOIN ai_suggestion_jobs j ON j.input_message_id = m.id
            LEFT JOIN ticket_suggestions s ON s.job_id = j.id
            LEFT JOIN ticket_suggestion_categories c ON c.suggestion_id = s.id
            WHERE t.id = ?
            ORDER BY c.category
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiSuggestionQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<StoredAiSuggestionQuery> findByTicketId(long ticketId) {
        // 최초 Message·Job·제안·모든 분류를 단일 SELECT의 같은 Snapshot에서 읽는다.
        return jdbcTemplate.query(FIND_SQL, row -> {
            if (!row.next()) {
                return Optional.empty();
            }
            long storedTicketId = row.getLong("ticket_id");
            Long jobId = row.getObject("job_id", Long.class);
            var job = jobId == null ? null : new StoredAiSuggestionQuery.Job(
                    jobId, row.getString("job_status"), row.getString("last_failure_code"));
            Long suggestionId = row.getObject("suggestion_id", Long.class);
            StoredAiSuggestionQuery.Suggestion suggestion = null;
            if (suggestionId != null) {
                String summary = row.getString("summary");
                String priority = row.getString("priority");
                String reviewStatus = row.getString("review_status");
                var createdAt = row.getObject("created_at", OffsetDateTime.class).toInstant();
                var categories = new ArrayList<String>();
                do {
                    String category = row.getString("category");
                    if (category != null) {
                        categories.add(category);
                    }
                } while (row.next());
                suggestion = new StoredAiSuggestionQuery.Suggestion(
                        suggestionId, summary, categories, priority, reviewStatus, createdAt);
            }
            return Optional.of(new StoredAiSuggestionQuery(storedTicketId, job, suggestion));
        }, ticketId);
    }
}
