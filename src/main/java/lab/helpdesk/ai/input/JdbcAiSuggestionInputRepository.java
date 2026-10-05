package lab.helpdesk.ai.input;

import java.util.Objects;
import java.util.Optional;

import lab.helpdesk.ai.job.AiJobClaim;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcAiSuggestionInputRepository implements AiSuggestionInputRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiSuggestionInputRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<StoredAiSuggestionInput> findCurrentInput(AiJobClaim claim) {
        Objects.requireNonNull(claim);
        // Load the Message attached to this Job, never the newest Message of its Ticket.
        return jdbcTemplate.query("""
                SELECT t.id AS ticket_id, m.id AS message_id, t.title, m.body
                FROM ai_suggestion_jobs j
                JOIN ticket_messages m ON m.id = j.input_message_id
                JOIN tickets t ON t.id = m.ticket_id
                WHERE j.id = ? AND j.input_message_id = ? AND j.current_attempt = ?
                  AND j.status = 'RUNNING'
                  AND j.processing_deadline_at > clock_timestamp()
                """, (row, rowNumber) -> new StoredAiSuggestionInput(
                        row.getLong("ticket_id"), row.getLong("message_id"),
                        row.getString("title"), row.getString("body")),
                claim.jobId(), claim.inputMessageId(), claim.attemptNumber()).stream().findFirst();
    }

    @Override
    public boolean canSendReservedRequest(AiJobClaim claim) {
        Objects.requireNonNull(claim);
        // The cap was already spent by Claim. Do not require an unused slot for this request.
        return !jdbcTemplate.queryForList("""
                SELECT j.id FROM ai_suggestion_jobs j
                JOIN ai_suggestion_attempts a
                  ON a.job_id = j.id AND a.attempt_number = j.current_attempt
                WHERE j.id = ? AND j.input_message_id = ? AND j.current_attempt = ?
                  AND j.status = 'RUNNING'
                  AND j.lease_expires_at > clock_timestamp()
                  AND j.processing_deadline_at > clock_timestamp()
                """, Long.class, claim.jobId(), claim.inputMessageId(), claim.attemptNumber()).isEmpty();
    }
}
