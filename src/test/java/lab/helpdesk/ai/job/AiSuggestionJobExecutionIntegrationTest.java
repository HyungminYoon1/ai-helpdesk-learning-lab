package lab.helpdesk.ai.job;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketMessage;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import lab.helpdesk.ticket.repository.TicketMessageRepository;
import lab.helpdesk.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
class AiSuggestionJobExecutionIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private TicketRepository tickets;
    @Autowired private TicketMessageRepository messages;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private JdbcAiSuggestionJobExecutionRepository executionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clear_only_the_verified_isolated_test_database() {
        Boolean isolatedDatabase = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolatedDatabase)) {
            throw new IllegalStateException("job test cleanup requires the isolated test database");
        }
        jdbcTemplate.update("DELETE FROM ai_suggestion_attempts");
        jdbcTemplate.update("DELETE FROM ai_suggestion_jobs");
        jdbcTemplate.update("DELETE FROM ticket_messages");
        jdbcTemplate.update("DELETE FROM tickets");
    }

    @Test
    void receipt_stores_the_approved_policy_snapshot_before_execution() {
        TicketReceiptResult receipt = receive();

        assertThat(text(receipt.jobId(), "status")).isEqualTo("PENDING");
        assertThat(text(receipt.jobId(), "policy_snapshot_source")).isEqualTo("APPLICATION");
        assertThat(text(receipt.jobId(), "policy_version")).isEqualTo("job-policy-v1");
        assertThat(number(receipt.jobId(), "max_generation_attempts")).isEqualTo(3);
        assertThat(number(receipt.jobId(), "max_output_repair_attempts")).isEqualTo(1);
        assertThat(number(receipt.jobId(), "request_timeout_ms")).isEqualTo(60000);
        assertThat(number(receipt.jobId(), "attempt_lease_ms")).isEqualTo(120000);
        assertThat(number(receipt.jobId(), "retry_backoff_ms")).isEqualTo(5000);
        assertThat(number(receipt.jobId(), "job_processing_timeout_ms")).isEqualTo(300000);
        assertThat(number(receipt.jobId(), "reserved_generation_count")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT first_started_at IS NULL AND processing_deadline_at IS NULL
                FROM ai_suggestion_jobs WHERE id = ?
                """, Boolean.class, receipt.jobId())).isTrue();
        assertThat(reservationCount()).isZero();
    }

    @Test
    void claim_commits_ownership_and_reservation_before_returning() {
        TicketReceiptResult receipt = receive();

        AiJobClaim claim = claims.claimNextPending().orElseThrow();

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(claim.jobId()).isEqualTo(receipt.jobId());
        assertThat(claim.inputMessageId()).isEqualTo(receipt.messageId());
        assertThat(claim.attemptNumber()).isOne();
        assertThat(claim.reservedGenerationCount()).isOne();
        assertThat(claim.reservedOutputRepairCount()).isZero();
        assertThat(claim.requestKind()).isEqualTo(AiJobRequestKind.INITIAL);
        assertThat(claim.processingDeadlineAt()).isEqualTo(claim.firstStartedAt().plusMillis(300000));
        assertThat(claim.leaseExpiresAt()).isEqualTo(claim.firstStartedAt().plusMillis(120000));
        assertThat(text(receipt.jobId(), "status")).isEqualTo("RUNNING");
        assertThat(reservationCount()).isOne();
        assertThat(claims.claimNextPending()).isEmpty();
    }

    @Test
    void claim_commit_is_independent_of_an_outer_transaction_rollback() {
        TicketReceiptResult receipt = receive();
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            assertThat(claims.claimNextPending()).isPresent();
            outer.setRollbackOnly();
        });

        assertThat(text(receipt.jobId(), "status")).isEqualTo("RUNNING");
        assertThat(number(receipt.jobId(), "reserved_generation_count")).isOne();
        assertThat(reservationCount()).isOne();
    }

    @Test
    void two_workers_competing_for_one_job_get_only_one_committed_reservation() throws Exception {
        receive();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { await(start); return claims.claimNextPending(); });
            var second = executor.submit(() -> { await(start); return claims.claimNextPending(); });
            start.countDown();

            List<Optional<AiJobClaim>> results = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent).count()).isOne();
        }
        assertThat(reservationCount()).isOne();
    }

    @Test
    void polling_skips_a_locked_job_and_claims_another_job() throws Exception {
        TicketReceiptResult lockedReceipt = receive();
        TicketReceiptResult freeReceipt = receive();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var lockOwner = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(transaction -> {
                        jdbcTemplate.queryForObject("""
                                SELECT id FROM ai_suggestion_jobs WHERE id = ? FOR UPDATE
                                """, Long.class, lockedReceipt.jobId());
                        locked.countDown();
                        await(release);
                    }));
            try {
                await(locked);
                assertThat(claims.claimNextPending().orElseThrow().jobId()).isEqualTo(freeReceipt.jobId());
            } finally {
                release.countDown();
            }
            lockOwner.get(5, TimeUnit.SECONDS);
        }
        assertThat(text(lockedReceipt.jobId(), "status")).isEqualTo("PENDING");
    }

    @Test
    void recovery_requires_expiry_and_preserves_the_original_deadline() {
        receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        assertThat(claims.claimRecoveryAfterResultCheck(first.jobId(), first.attemptNumber())).isEmpty();

        expireLeaseForTest(first.jobId());
        assertThat(claims.claimNextPending()).isEmpty(); // 시간 경과만으로 자동 재호출하지 않는다.
        AiJobClaim second = claims.claimRecoveryAfterResultCheck(first.jobId(), 1).orElseThrow();

        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(second.reservedGenerationCount()).isEqualTo(2);
        assertThat(second.requestKind()).isEqualTo(AiJobRequestKind.RECOVERY);
        assertThat(second.firstStartedAt()).isEqualTo(first.firstStartedAt());
        assertThat(second.processingDeadlineAt()).isEqualTo(first.processingDeadlineAt());
        assertThat(reservationCount()).isEqualTo(2);
    }

    @Test
    void recovery_waits_for_backoff_even_after_the_lease_expires() {
        receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        jdbcTemplate.update("""
                UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second'
                WHERE id = ?
                """, first.jobId());

        assertThat(claims.claimRecoveryAfterResultCheck(first.jobId(), 1)).isEmpty();
        assertThat(number(first.jobId(), "current_attempt")).isOne();
        assertThat(reservationCount()).isOne();
    }

    @Test
    void two_recovery_requests_cannot_replace_the_same_attempt_twice() throws Exception {
        receive();
        AiJobClaim firstClaim = claims.claimNextPending().orElseThrow();
        expireLeaseForTest(firstClaim.jobId());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                await(start);
                return claims.claimRecoveryAfterResultCheck(firstClaim.jobId(), 1);
            });
            var second = executor.submit(() -> {
                await(start);
                return claims.claimRecoveryAfterResultCheck(firstClaim.jobId(), 1);
            });
            start.countDown();
            List<Optional<AiJobClaim>> results = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent).count()).isOne();
        }
        assertThat(number(firstClaim.jobId(), "current_attempt")).isEqualTo(2);
        assertThat(reservationCount()).isEqualTo(2);
    }

    @Test
    void queue_waiting_does_not_start_the_processing_deadline() {
        TicketReceiptResult receipt = receive();
        jdbcTemplate.update("""
                UPDATE ai_suggestion_jobs SET created_at = clock_timestamp() - INTERVAL '1 day'
                WHERE id = ?
                """, receipt.jobId());

        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThat(claim.processingDeadlineAt()).isEqualTo(claim.firstStartedAt().plusMillis(300000));
        assertThat(text(claim.jobId(), "status")).isEqualTo("RUNNING");
    }

    @Test
    void stale_attempt_cannot_overwrite_the_current_job_or_schedule_a_repair() {
        receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        expireLeaseForTest(first.jobId());
        AiJobClaim second = claims.claimRecoveryAfterResultCheck(first.jobId(), 1).orElseThrow();

        assertThat(claims.failIfCurrent(first, AiJobFailureCode.PROVIDER_REFUSED)).isFalse();
        assertThat(claims.scheduleOutputRepair(first)).isFalse();
        assertThat(claims.claimRecoveryAfterResultCheck(first.jobId(), 1)).isEmpty();
        assertThat(text(second.jobId(), "status")).isEqualTo("RUNNING");
        assertThat(number(second.jobId(), "current_attempt")).isEqualTo(2);
    }

    @Test
    void fifth_reservation_is_allowed_but_a_sixth_is_not() {
        enqueueWithPolicy(new AiJobPolicy("cap-five", 5, 1, 60000, 120000, 5000, 300000));
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        for (int expected = 2; expected <= 5; expected++) {
            expireLeaseForTest(claim.jobId());
            claim = claims.claimRecoveryAfterResultCheck(claim.jobId(), claim.attemptNumber()).orElseThrow();
            assertThat(claim.reservedGenerationCount()).isEqualTo(expected);
        }
        expireLeaseForTest(claim.jobId());

        assertThat(claims.claimRecoveryAfterResultCheck(claim.jobId(), 5)).isEmpty();
        assertThat(number(claim.jobId(), "reserved_generation_count")).isEqualTo(5);
        assertThat(reservationCount()).isEqualTo(5);
        // 새 호출이 금지되어도, 아직 현재인 마지막 Attempt의 결과 반영은 막지 않는다.
        assertThat(claims.failIfCurrent(claim, AiJobFailureCode.PROVIDER_REFUSED)).isTrue();
        assertThat(number(claim.jobId(), "reserved_generation_count")).isEqualTo(5);
    }

    @Test
    void reservation_ledger_failure_rolls_back_the_claim_but_not_the_receipt() {
        TicketReceiptResult receipt = receive();
        jdbcTemplate.execute("""
                ALTER TABLE ai_suggestion_attempts
                ADD CONSTRAINT test_reject_reservation CHECK (false) NOT VALID
                """);
        try {
            assertThatThrownBy(() -> claims.claimNextPending())
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(text(receipt.jobId(), "status")).isEqualTo("PENDING");
            assertThat(number(receipt.jobId(), "current_attempt")).isZero();
            assertThat(number(receipt.jobId(), "reserved_generation_count")).isZero();
            assertThat(reservationCount()).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tickets", Long.class)).isOne();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_messages", Long.class)).isOne();
        } finally {
            jdbcTemplate.execute("ALTER TABLE ai_suggestion_attempts DROP CONSTRAINT test_reject_reservation");
        }
    }

    @Test
    void processing_deadline_ends_the_job_without_resetting_counts() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        jdbcTemplate.update("""
                UPDATE ai_suggestion_jobs
                SET first_started_at = clock_timestamp() - INTERVAL '10 minutes',
                    processing_deadline_at = clock_timestamp() - INTERVAL '1 minute',
                    lease_expires_at = clock_timestamp() - INTERVAL '2 minutes'
                WHERE id = ?
                """, claim.jobId());

        assertThat(claims.claimNextPending()).isEmpty();
        assertThat(claims.claimRecoveryAfterResultCheck(claim.jobId(), 1)).isEmpty();
        assertThat(claims.failIfCurrent(claim, AiJobFailureCode.PROVIDER_REFUSED)).isFalse();
        assertThat(text(claim.jobId(), "status")).isEqualTo("FAILED");
        assertThat(text(claim.jobId(), "last_failure_code")).isEqualTo("JOB_PROCESSING_DEADLINE_EXCEEDED");
        assertThat(number(claim.jobId(), "reserved_generation_count")).isOne();
        assertThat(reservationCount()).isOne();
    }

    @Test
    void output_repair_has_backoff_and_uses_both_cumulative_caps() {
        receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        assertThat(claims.scheduleOutputRepair(first)).isTrue();
        assertThat(claims.claimNextPending()).isEmpty();
        assertThat(number(first.jobId(), "reserved_generation_count")).isOne();
        assertThat(number(first.jobId(), "reserved_output_repair_count")).isZero();

        makeRepairDueForTest(first.jobId());
        AiJobClaim repair = claims.claimNextPending().orElseThrow();
        assertThat(repair.requestKind()).isEqualTo(AiJobRequestKind.OUTPUT_REPAIR);
        assertThat(repair.reservedGenerationCount()).isEqualTo(2);
        assertThat(repair.reservedOutputRepairCount()).isOne();
        assertThat(claims.scheduleOutputRepair(repair)).isFalse();
        assertThat(text(repair.jobId(), "status")).isEqualTo("FAILED");
        assertThat(text(repair.jobId(), "last_failure_code")).isEqualTo("OUTPUT_REPAIR_LIMIT_EXHAUSTED");
        assertThat(reservationCount()).isEqualTo(2);
    }

    @Test
    void overall_cap_can_stop_repairs_before_the_repair_cap_is_spent() {
        enqueueWithPolicy(new AiJobPolicy("joint-caps", 2, 2, 60000, 120000, 5000, 300000));
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        assertThat(claims.scheduleOutputRepair(first)).isTrue();
        makeRepairDueForTest(first.jobId());
        AiJobClaim repair = claims.claimNextPending().orElseThrow();

        assertThat(claims.scheduleOutputRepair(repair)).isFalse();
        assertThat(text(repair.jobId(), "last_failure_code")).isEqualTo("GENERATION_LIMIT_EXHAUSTED");
        assertThat(number(repair.jobId(), "reserved_generation_count")).isEqualTo(2);
        assertThat(number(repair.jobId(), "reserved_output_repair_count")).isOne();
    }

    @Test
    void zero_repair_cap_disables_output_repair_but_not_the_initial_request() {
        enqueueWithPolicy(new AiJobPolicy("no-repair", 3, 0, 60000, 120000, 5000, 300000));
        AiJobClaim first = claims.claimNextPending().orElseThrow();

        assertThat(first.reservedGenerationCount()).isOne();
        assertThat(claims.scheduleOutputRepair(first)).isFalse();
        assertThat(text(first.jobId(), "last_failure_code")).isEqualTo("OUTPUT_REPAIR_LIMIT_EXHAUSTED");
        assertThat(claims.claimNextPending()).isEmpty();
        assertThat(reservationCount()).isOne();
    }

    @Test
    void recreated_repository_with_new_configuration_does_not_replace_an_old_snapshot() {
        AiJobPolicy oldPolicy = new AiJobPolicy("old-policy", 3, 1, 60000, 120000, 5000, 300000);
        long oldJobId = enqueueWithPolicy(oldPolicy);
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        long newJobId = enqueueWithPolicy(new AiJobPolicy("new-policy", 5, 2, 10000, 20000, 1000, 90000));
        expireLeaseForTest(oldJobId);

        // 새 Spring Context나 JVM을 시작한 Test가 아니라, 새 Repository 객체의 복원을 확인한다.
        var restartedRepository = new JdbcAiSuggestionJobExecutionRepository(jdbcTemplate);
        AiJobClaim recovered = new TransactionTemplate(transactionManager).execute(transaction -> {
            AiJobClaim result = restartedRepository.claimRecovery(oldJobId, 1).orElseThrow();
            restartedRepository.recordReservation(result);
            return result;
        });
        assertThat(recovered.policy()).isEqualTo(oldPolicy);
        assertThat(recovered.reservedGenerationCount()).isEqualTo(2);
        assertThat(recovered.processingDeadlineAt()).isEqualTo(first.processingDeadlineAt());
        assertThat(text(newJobId, "policy_version")).isEqualTo("new-policy");
        assertThat(number(newJobId, "max_generation_attempts")).isEqualTo(5);
    }

    @Test
    void control_plane_updates_are_rejected_without_a_transaction() {
        assertThatThrownBy(() -> executionRepository.claimNextPending())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("job execution changes require a transaction");
        assertThatThrownBy(() -> executionRepository.expireProcessingDeadlines())
                .isInstanceOf(IllegalStateException.class);
        assertThat(reservationCount()).isZero();
    }

    private TicketReceiptResult receive() {
        return receipts.receive("로그인 문의", "새 로그인 링크를 요청합니다.", "synthetic-author");
    }

    private long enqueueWithPolicy(AiJobPolicy policy) {
        return new TransactionTemplate(transactionManager).execute(transaction -> {
            long ticketId = tickets.save(new Ticket("로그인 문의"));
            long messageId = messages.save(ticketId, new TicketMessage("문의 원문", "synthetic-author"));
            return new JdbcAiSuggestionJobRepository(jdbcTemplate, policy).enqueue(messageId);
        });
    }

    private void expireLeaseForTest(long jobId) {
        jdbcTemplate.update("""
                UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 minute'
                WHERE id = ?
                """, jobId);
    }

    private void makeRepairDueForTest(long jobId) {
        jdbcTemplate.update("""
                UPDATE ai_suggestion_jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'
                WHERE id = ?
                """, jobId);
    }

    private long number(long jobId, String column) {
        // Column 이름은 이 Test에 하드코딩한 Metadata만 받는다. HTTP 입력을 SQL에 넣지 않는다.
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM ai_suggestion_jobs WHERE id = ?",
                Long.class, jobId);
    }

    private String text(long jobId, String column) {
        return jdbcTemplate.queryForObject("SELECT " + column + " FROM ai_suggestion_jobs WHERE id = ?",
                String.class, jobId);
    }

    private long reservationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts", Long.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("job test synchronization timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("job test synchronization interrupted");
        }
    }
}
