package lab.helpdesk.ai.job;

import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@Profile("postgres")
public class JdbcAiSuggestionJobResultRepository implements AiSuggestionJobResultRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiSuggestionJobResultRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean lockCurrentExecution(AiJobClaim claim) {
        requireTransaction();
        Objects.requireNonNull(claim);
        // Lock 뒤의 저장까지 같은 Transaction이어야 실행권 교체와 경쟁하지 않는다.
        return !jdbcTemplate.queryForList("""
                SELECT id FROM ai_suggestion_jobs
                WHERE id = ? AND current_attempt = ? AND status = 'RUNNING'
                  AND processing_deadline_at > clock_timestamp()
                FOR UPDATE
                """, Long.class, claim.jobId(), claim.attemptNumber()).isEmpty();
    }

    @Override
    public boolean finishIfCurrent(AiJobClaim claim, AiJobStatus terminalStatus) {
        requireTransaction();
        Objects.requireNonNull(claim);
        if (terminalStatus != AiJobStatus.SUCCEEDED && terminalStatus != AiJobStatus.ABSTAINED) {
            throw new IllegalArgumentException("invalid result completion status");
        }
        // 남은 새 호출 횟수나 만료된 Lease만으로 현재 응답의 저장을 막지 않는다.
        return jdbcTemplate.update("""
                UPDATE ai_suggestion_jobs
                SET status = ?, finished_at = clock_timestamp(), last_failure_code = NULL,
                    lease_expires_at = NULL, next_attempt_at = NULL
                WHERE id = ? AND current_attempt = ? AND status = 'RUNNING'
                  AND processing_deadline_at > clock_timestamp()
                """, terminalStatus.name(), claim.jobId(), claim.attemptNumber()) == 1;
    }

    @Override
    public Optional<AiJobResultState> findByJobId(long jobId) {
        if (jobId <= 0) {
            throw new IllegalArgumentException("jobId must be positive");
        }
        return jdbcTemplate.query("""
                SELECT id, current_attempt, status FROM ai_suggestion_jobs WHERE id = ?
                """, (row, rowNumber) -> new AiJobResultState(
                        row.getLong("id"), row.getInt("current_attempt"),
                        AiJobStatus.valueOf(row.getString("status"))), jobId).stream().findFirst();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("job result changes require a transaction");
        }
    }
}
