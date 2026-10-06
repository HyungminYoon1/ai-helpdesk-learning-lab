package lab.helpdesk.ai.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@Profile("postgres")
public class JdbcAiSuggestionJobExecutionRepository implements AiSuggestionJobExecutionRepository {

    private static final String CLAIM_PENDING_SQL = """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS now),
            candidate AS (
                SELECT j.id
                FROM ai_suggestion_jobs j, moment m
                WHERE j.status = 'PENDING'
                  AND (j.next_attempt_at IS NULL OR j.next_attempt_at <= m.now)
                  AND (j.processing_deadline_at IS NULL OR j.processing_deadline_at > m.now)
                  AND j.reserved_generation_count < j.max_generation_attempts
                  AND (j.next_request_kind IN ('INITIAL', 'TEMPORARY_RETRY')
                    OR (j.next_request_kind = 'OUTPUT_REPAIR'
                        AND j.reserved_output_repair_count < j.max_output_repair_attempts))
                ORDER BY j.created_at, j.id
                LIMIT 1
                FOR UPDATE OF j SKIP LOCKED
            )
            UPDATE ai_suggestion_jobs j
            SET status = 'RUNNING',
                current_attempt = j.current_attempt + 1,
                reserved_generation_count = j.reserved_generation_count + 1,
                reserved_output_repair_count = j.reserved_output_repair_count
                    + CASE WHEN j.next_request_kind = 'OUTPUT_REPAIR' THEN 1 ELSE 0 END,
                first_started_at = COALESCE(j.first_started_at, m.now),
                processing_deadline_at = COALESCE(j.processing_deadline_at,
                    m.now + j.job_processing_timeout_ms * INTERVAL '1 millisecond'),
                lease_expires_at = LEAST(m.now + j.attempt_lease_ms * INTERVAL '1 millisecond',
                    COALESCE(j.processing_deadline_at,
                        m.now + j.job_processing_timeout_ms * INTERVAL '1 millisecond')),
                next_attempt_at = NULL
            FROM candidate c, moment m
            WHERE j.id = c.id
            RETURNING j.*
            """;

    private static final String CLAIM_RECOVERY_SQL = """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS now)
            UPDATE ai_suggestion_jobs j
            SET current_attempt = j.current_attempt + 1,
                reserved_generation_count = j.reserved_generation_count + 1,
                lease_expires_at = LEAST(m.now + j.attempt_lease_ms * INTERVAL '1 millisecond',
                    j.processing_deadline_at),
                next_request_kind = 'RECOVERY',
                next_attempt_at = NULL,
                last_failure_code = 'PROVIDER_OUTCOME_UNKNOWN'
            FROM moment m
            WHERE j.id = ? AND j.status = 'RUNNING' AND j.current_attempt = ?
              AND j.lease_expires_at + j.retry_backoff_ms * INTERVAL '1 millisecond' <= m.now
              AND j.processing_deadline_at > m.now
              AND j.reserved_generation_count < j.max_generation_attempts
              AND EXISTS (
                  SELECT 1 FROM ai_suggestion_attempts a
                  WHERE a.job_id = j.id AND a.attempt_number = j.current_attempt
                    AND a.result_code IN ('UNCONFIRMED', 'OUTCOME_UNKNOWN'))
              AND NOT EXISTS (SELECT 1 FROM ticket_suggestions s WHERE s.job_id = j.id)
            RETURNING j.*
            """;

    private static final String FIND_RECOVERY_SQL = """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS now)
            SELECT j.id, j.current_attempt
            FROM ai_suggestion_jobs j
            JOIN ai_suggestion_attempts a
              ON a.job_id = j.id AND a.attempt_number = j.current_attempt
            CROSS JOIN moment m
            WHERE j.status = 'RUNNING'
              AND a.result_code IN ('UNCONFIRMED', 'OUTCOME_UNKNOWN')
              AND j.lease_expires_at + j.retry_backoff_ms * INTERVAL '1 millisecond' <= m.now
              AND j.processing_deadline_at > m.now
              AND j.reserved_generation_count < j.max_generation_attempts
              AND NOT EXISTS (SELECT 1 FROM ticket_suggestions s WHERE s.job_id = j.id)
            ORDER BY j.created_at, j.id
            LIMIT 1
            """;

    private static final String EXPIRE_DEADLINES_SQL = """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS now),
            expired AS (
                SELECT j.id FROM ai_suggestion_jobs j, moment m
                WHERE j.status IN ('PENDING', 'RUNNING')
                  AND j.processing_deadline_at <= m.now
                FOR UPDATE OF j SKIP LOCKED
            )
            UPDATE ai_suggestion_jobs j
            SET status = 'FAILED', last_failure_code = 'JOB_PROCESSING_DEADLINE_EXCEEDED',
                finished_at = m.now, lease_expires_at = NULL, next_attempt_at = NULL
            FROM expired e, moment m
            WHERE j.id = e.id
            """;

    private static final String SCHEDULE_REPAIR_SQL = """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS now)
            UPDATE ai_suggestion_jobs j
            SET status = 'PENDING', next_request_kind = 'OUTPUT_REPAIR',
                next_attempt_at = m.now + j.retry_backoff_ms * INTERVAL '1 millisecond',
                lease_expires_at = NULL, last_failure_code = 'OUTPUT_REQUIRED_FIELD_MISSING'
            FROM moment m
            WHERE j.id = ? AND j.current_attempt = ? AND j.status = 'RUNNING'
              AND j.processing_deadline_at > m.now
              AND j.reserved_generation_count < j.max_generation_attempts
              AND j.reserved_output_repair_count < j.max_output_repair_attempts
            """;

    private static final String EXHAUSTED_REPAIR_SQL = """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS now)
            UPDATE ai_suggestion_jobs j
            SET status = 'FAILED', finished_at = m.now, lease_expires_at = NULL,
                last_failure_code = CASE
                    WHEN j.reserved_generation_count >= j.max_generation_attempts
                        THEN 'GENERATION_LIMIT_EXHAUSTED'
                    ELSE 'OUTPUT_REPAIR_LIMIT_EXHAUSTED' END
            FROM moment m
            WHERE j.id = ? AND j.current_attempt = ? AND j.status = 'RUNNING'
              AND j.processing_deadline_at > m.now
              AND (j.reserved_generation_count >= j.max_generation_attempts
                OR j.reserved_output_repair_count >= j.max_output_repair_attempts)
            """;

    private static final String FAIL_CURRENT_SQL = """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS now)
            UPDATE ai_suggestion_jobs j
            SET status = 'FAILED', last_failure_code = ?, finished_at = m.now,
                lease_expires_at = NULL, next_attempt_at = NULL
            FROM moment m
            WHERE j.id = ? AND j.current_attempt = ? AND j.status = 'RUNNING'
              AND j.processing_deadline_at > m.now
            """;

    private static final String SCHEDULE_RATE_LIMIT_SQL = """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS now)
            UPDATE ai_suggestion_jobs j
            SET status = 'PENDING', next_request_kind = 'TEMPORARY_RETRY',
                next_attempt_at = m.now
                    + GREATEST(j.retry_backoff_ms, ?) * INTERVAL '1 millisecond',
                lease_expires_at = NULL, last_failure_code = 'PROVIDER_RATE_LIMITED'
            FROM moment m
            WHERE j.id = ? AND j.current_attempt = ? AND j.status = 'RUNNING'
              AND j.processing_deadline_at > m.now
              AND j.reserved_generation_count < j.max_generation_attempts
            """;

    private static final String EXHAUSTED_GENERATION_SQL = """
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS now)
            UPDATE ai_suggestion_jobs j
            SET status = 'FAILED', last_failure_code = 'GENERATION_LIMIT_EXHAUSTED',
                finished_at = m.now, lease_expires_at = NULL, next_attempt_at = NULL
            FROM moment m
            WHERE j.id = ? AND j.current_attempt = ? AND j.status = 'RUNNING'
              AND j.processing_deadline_at > m.now
              AND j.reserved_generation_count >= j.max_generation_attempts
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiSuggestionJobExecutionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AiJobClaim> claimNextPending() {
        requireTransaction();
        return jdbcTemplate.query(CLAIM_PENDING_SQL, this::readClaim).stream().findFirst();
    }

    @Override
    public Optional<AiJobRecoveryCandidate> findNextRecoveryCandidate() {
        return jdbcTemplate.query(FIND_RECOVERY_SQL, (row, number) ->
                new AiJobRecoveryCandidate(row.getLong("id"), row.getInt("current_attempt")))
                .stream().findFirst();
    }

    @Override
    public Optional<AiJobClaim> claimRecovery(long jobId, int expectedAttempt) {
        requireTransaction();
        if (jobId <= 0 || expectedAttempt <= 0) {
            throw new IllegalArgumentException("invalid recovery metadata");
        }
        // Serialize result classification and recovery on the same Job row. The next
        // statement takes a fresh READ COMMITTED snapshot after acquiring this lock.
        if (!lockCurrentJob(jobId, expectedAttempt, true)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(CLAIM_RECOVERY_SQL, this::readClaim, jobId, expectedAttempt)
                .stream().findFirst();
    }

    @Override
    public void recordReservation(AiJobClaim claim) {
        requireTransaction();
        Objects.requireNonNull(claim);
        int rows = jdbcTemplate.update("""
                INSERT INTO ai_suggestion_attempts (job_id, attempt_number, request_kind)
                VALUES (?, ?, ?)
                """, claim.jobId(), claim.attemptNumber(), claim.requestKind().name());
        if (rows != 1) {
            throw new IllegalStateException("job reservation was not recorded");
        }
    }

    @Override
    public boolean recordAttemptResultIfCurrent(AiJobClaim claim, AiAttemptResultCode resultCode) {
        requireTransaction();
        Objects.requireNonNull(claim);
        Objects.requireNonNull(resultCode);
        if (resultCode == AiAttemptResultCode.UNCONFIRMED) {
            throw new IllegalArgumentException("AI_ATTEMPT_RESULT_RESET_FORBIDDEN");
        }
        if (!lockCurrentJob(claim.jobId(), claim.attemptNumber(), false)) {
            return false;
        }
        // A blocked Attempt cannot be changed into an eligible unknown result.
        return jdbcTemplate.update("""
                UPDATE ai_suggestion_attempts SET result_code = ?
                WHERE job_id = ? AND attempt_number = ?
                  AND (result_code <> 'AUTO_RETRY_BLOCKED' OR result_code = ?)
                """, resultCode.name(), claim.jobId(), claim.attemptNumber(), resultCode.name()) == 1;
    }

    @Override
    public int expireProcessingDeadlines() {
        requireTransaction();
        return jdbcTemplate.update(EXPIRE_DEADLINES_SQL);
    }

    @Override
    public boolean scheduleOutputRepair(AiJobClaim claim) {
        requireTransaction();
        Objects.requireNonNull(claim);
        return jdbcTemplate.update(SCHEDULE_REPAIR_SQL, claim.jobId(), claim.attemptNumber()) == 1;
    }

    @Override
    public boolean failForExhaustedRepair(AiJobClaim claim) {
        requireTransaction();
        Objects.requireNonNull(claim);
        return jdbcTemplate.update(EXHAUSTED_REPAIR_SQL, claim.jobId(), claim.attemptNumber()) == 1;
    }

    @Override
    public boolean scheduleRateLimitRetry(AiJobClaim claim, long minimumWaitMs) {
        requireTransaction();
        Objects.requireNonNull(claim);
        if (minimumWaitMs < 0) {
            throw new IllegalArgumentException("AI_RETRY_DELAY_INVALID");
        }
        return jdbcTemplate.update(SCHEDULE_RATE_LIMIT_SQL,
                minimumWaitMs, claim.jobId(), claim.attemptNumber()) == 1;
    }

    @Override
    public boolean failForExhaustedGeneration(AiJobClaim claim) {
        requireTransaction();
        Objects.requireNonNull(claim);
        return jdbcTemplate.update(EXHAUSTED_GENERATION_SQL, claim.jobId(), claim.attemptNumber()) == 1;
    }

    @Override
    public boolean failIfCurrent(AiJobClaim claim, AiJobFailureCode failureCode) {
        requireTransaction();
        Objects.requireNonNull(claim);
        Objects.requireNonNull(failureCode);
        return jdbcTemplate.update(FAIL_CURRENT_SQL,
                failureCode.name(), claim.jobId(), claim.attemptNumber()) == 1;
    }

    private AiJobClaim readClaim(ResultSet row, int rowNumber) throws SQLException {
        AiJobPolicy policy = new AiJobPolicy(
                row.getString("policy_version"),
                row.getInt("max_generation_attempts"),
                row.getInt("max_output_repair_attempts"),
                row.getLong("request_timeout_ms"),
                row.getLong("attempt_lease_ms"),
                row.getLong("retry_backoff_ms"),
                row.getLong("job_processing_timeout_ms"));
        return new AiJobClaim(
                row.getLong("id"), row.getLong("input_message_id"), row.getInt("current_attempt"),
                row.getInt("reserved_generation_count"), row.getInt("reserved_output_repair_count"),
                AiJobRequestKind.valueOf(row.getString("next_request_kind")), policy,
                row.getObject("first_started_at", OffsetDateTime.class).toInstant(),
                row.getObject("processing_deadline_at", OffsetDateTime.class).toInstant(),
                row.getObject("lease_expires_at", OffsetDateTime.class).toInstant());
    }

    private boolean lockCurrentJob(long jobId, int expectedAttempt, boolean skipLocked) {
        String sql = """
                SELECT id FROM ai_suggestion_jobs
                WHERE id = ? AND current_attempt = ? AND status = 'RUNNING'
                FOR UPDATE
                """ + (skipLocked ? " SKIP LOCKED" : "");
        return !jdbcTemplate.queryForList(sql, Long.class, jobId, expectedAttempt).isEmpty();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("job execution changes require a transaction");
        }
    }
}
