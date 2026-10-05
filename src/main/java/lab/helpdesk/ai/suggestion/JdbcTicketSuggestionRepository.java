package lab.helpdesk.ai.suggestion;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Category;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Decision;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Priority;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@Profile("postgres")
public class JdbcTicketSuggestionRepository implements TicketSuggestionRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTicketSuggestionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long save(long jobId, ContractValidatedOutput output) {
        requireTransaction();
        Objects.requireNonNull(output);
        if (jobId <= 0 || output.decision() != Decision.SUGGEST) {
            throw new IllegalArgumentException("invalid suggestion storage input");
        }
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO ticket_suggestions (job_id, summary, priority)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, jobId, output.summary(), output.priority().name());
        if (id == null) {
            throw new IllegalStateException("suggestion identity was not returned");
        }
        for (Category category : output.categories()) {
            int insertedRows = jdbcTemplate.update("""
                    INSERT INTO ticket_suggestion_categories (suggestion_id, category)
                    VALUES (?, ?)
                    """, id, category.name());
            if (insertedRows != 1) {
                throw new IllegalStateException("suggestion category was not recorded");
            }
        }
        return id;
    }

    @Override
    public Optional<TicketSuggestion> findByJobId(long jobId) {
        if (jobId <= 0) {
            throw new IllegalArgumentException("jobId must be positive");
        }
        Optional<StoredRow> row = jdbcTemplate.query("""
                SELECT id, job_id, summary, priority, review_status, created_at
                FROM ticket_suggestions WHERE job_id = ?
                """, (result, rowNumber) -> new StoredRow(
                        result.getLong("id"), result.getLong("job_id"), result.getString("summary"),
                        Priority.valueOf(result.getString("priority")),
                        TicketSuggestion.ReviewStatus.valueOf(result.getString("review_status")),
                        result.getObject("created_at", OffsetDateTime.class).toInstant()), jobId)
                .stream().findFirst();
        return row.map(stored -> new TicketSuggestion(
                stored.id(), stored.jobId(), stored.summary(),
                jdbcTemplate.query("""
                        SELECT category FROM ticket_suggestion_categories
                        WHERE suggestion_id = ? ORDER BY category
                        """, (result, rowNumber) -> Category.valueOf(result.getString("category")), stored.id()),
                stored.priority(), stored.reviewStatus(), stored.createdAt()));
    }

    private record StoredRow(long id, long jobId, String summary, Priority priority,
            TicketSuggestion.ReviewStatus reviewStatus, Instant createdAt) {
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("suggestion writes require a transaction");
        }
    }
}
