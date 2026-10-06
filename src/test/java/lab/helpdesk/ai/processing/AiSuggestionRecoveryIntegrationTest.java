package lab.helpdesk.ai.processing;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.JdbcAiSuggestionInputRepository;
import lab.helpdesk.ai.job.AiAttemptResultCode;
import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobRequestKind;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.job.JdbcAiSuggestionJobExecutionRepository;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.provider.AiProviderFailureException;
import lab.helpdesk.ai.provider.AiProviderFailureException.Kind;
import lab.helpdesk.ai.provider.AiProviderFailureException.Reason;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.suggestion.AiSuggestionStoredResult;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

/** Real PostgreSQL and controlled Provider responses, not live AI or a JVM restart. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.main.banner-mode=off"})
@ActiveProfiles("postgres")
@Testcontainers
class AiSuggestionRecoveryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    private static final String VALID = """
            {"decision":"SUGGEST","summary":"Synthetic restored question",
             "categories":["ACCOUNT"],"priority":"NORMAL"}
            """;
    private static final String ORIGINAL = "Original message stays independently committed.";
    private final AiSuggestionOutputValidator validator = new AiSuggestionOutputValidator(200);
    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private JdbcAiSuggestionJobExecutionRepository execution;
    @Autowired private AiSuggestionResultService results;
    @Autowired private JdbcAiSuggestionInputRepository inputs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clear_only_the_verified_testcontainer_database() {
        Boolean isolated = jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) throw new IllegalStateException("AI_RECOVERY_TEST_DATABASE_REQUIRED");
        jdbc.update("DELETE FROM ticket_suggestion_categories");
        jdbc.update("DELETE FROM ticket_suggestions");
        jdbc.update("DELETE FROM ai_suggestion_attempts");
        jdbc.update("DELETE FROM ai_suggestion_jobs");
        jdbc.update("DELETE FROM ticket_messages");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    void unknown_result_is_durable_and_recovery_commits_a_new_reservation_before_calling() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        var firstWorker = worker((input, timeout, kind) -> {
            calls.incrementAndGet();
            throw new AiProviderFailureException(Kind.OUTCOME_UNKNOWN);
        });
        AiJobClaim first = firstWorker.runOnce().claim();
        assertThat(code(first)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(firstWorker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        expireLease(receipt.jobId());

        var newWorker = worker((input, timeout, kind) -> {
            calls.incrementAndGet();
            assertThat(kind).isEqualTo(AiJobRequestKind.RECOVERY);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(count("ai_suggestion_attempts")).isEqualTo(2);
            // A separate transaction can acquire the Job lock during Provider work.
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    assertThat(jdbc.queryForObject("SELECT id FROM ai_suggestion_jobs WHERE id = ? FOR UPDATE NOWAIT",
                            Long.class, receipt.jobId())).isEqualTo(receipt.jobId()));
            return VALID;
        });
        AiSuggestionProcessingResult recovered = newWorker.runOnce();
        assertThat(recovered.outcome()).isEqualTo(Outcome.STORED);
        assertThat(recovered.claim().attemptNumber()).isEqualTo(2);
        assertThat(recovered.claim().processingDeadlineAt()).isEqualTo(first.processingDeadlineAt());
        assertThat(recovered.claim().firstStartedAt()).isEqualTo(first.firstStartedAt());
        assertThat(calls).hasValue(2);
        assertThat(count("ticket_suggestions")).isOne();
        assertOriginal(receipt);
    }

    @Test
    void unconfirmed_reservation_is_not_proof_of_execution_and_is_not_refunded() {
        TicketReceiptResult receipt = receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        assertThat(code(first)).isEqualTo("UNCONFIRMED");
        expireLease(receipt.jobId());
        AtomicInteger calls = new AtomicInteger();
        var worker = worker((input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        });

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(calls).hasValue(1);
        assertThat(number(receipt.jobId(), "reserved_generation_count")).isEqualTo(2);
        assertThat(code(first)).isEqualTo("UNCONFIRMED");
        assertOriginal(receipt);
    }

    @Test
    void known_rate_limit_without_a_hint_stays_blocked_even_after_lease_expiry() {
        TicketReceiptResult receipt = receive();
        AiJobClaim first = worker((input, timeout, kind) -> {
            throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT, null);
        }).runOnce().claim();
        assertThat(code(first)).isEqualTo("AUTO_RETRY_BLOCKED");
        expireLease(receipt.jobId());
        AtomicInteger calls = new AtomicInteger();

        assertThat(worker((input, timeout, kind) -> { calls.incrementAndGet(); return VALID; })
                .runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(claims.claimRecoveryAfterResultCheck(first.jobId(), first.attemptNumber())).isEmpty();
        assertThat(calls).hasValue(0);
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertOriginal(receipt);
    }

    @Test
    void other_unapproved_temporary_rejections_cannot_become_unknown_recovery() {
        TicketReceiptResult receipt = receive();
        AiJobClaim first = worker((input, timeout, kind) -> {
            throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.OVERLOADED,
                    Duration.ofSeconds(15));
        }).runOnce().claim();
        expireLease(receipt.jobId());

        assertThat(code(first)).isEqualTo("AUTO_RETRY_BLOCKED");
        assertThat(claims.findNextRecoveryCandidate()).isEmpty();
        assertThat(claims.claimRecoveryAfterResultCheck(first.jobId(), first.attemptNumber())).isEmpty();
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertOriginal(receipt);
    }

    @Test
    void old_failure_metadata_is_not_used_as_the_new_attempt_result() {
        TicketReceiptResult receipt = receive();
        var first = claims.claimNextPending().orElseThrow();
        assertThat(claims.recordAttemptResultIfCurrent(first, AiAttemptResultCode.AUTO_RETRY_BLOCKED)).isTrue();
        assertThat(claims.scheduleRateLimitRetry(first, Duration.ofSeconds(15)).name()).isEqualTo("SCHEDULED");
        jdbc.update("UPDATE ai_suggestion_jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                receipt.jobId());
        AiJobClaim second = claims.claimNextPending().orElseThrow();
        assertThat(code(second)).isEqualTo("UNCONFIRMED");
        assertThat(jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?",
                String.class, receipt.jobId())).isEqualTo("PROVIDER_RATE_LIMITED");
        expireLease(receipt.jobId());

        AiJobClaim third = claims.claimRecoveryAfterResultCheck(second.jobId(), second.attemptNumber()).orElseThrow();
        assertThat(third.attemptNumber()).isEqualTo(3);
        assertThat(code(first)).isEqualTo("AUTO_RETRY_BLOCKED");
        assertThat(count("ai_suggestion_attempts")).isEqualTo(3);
    }

    @Test
    void failed_result_read_does_not_authorize_recovery_or_spend_another_slot() {
        TicketReceiptResult receipt = receive();
        claims.claimNextPending().orElseThrow();
        expireLease(receipt.jobId());
        var unreadable = new AiSuggestionResultService(null, null) {
            @Override public Optional<AiSuggestionStoredResult> findStoredResult(long jobId) {
                throw new IllegalStateException("SIMULATED_READ_FAILURE");
            }
        };
        AtomicInteger calls = new AtomicInteger();

        assertThat(worker((input, timeout, kind) -> { calls.incrementAndGet(); return VALID; }, unreadable)
                .runOnce().outcome()).isEqualTo(Outcome.RECOVERY_STATE_UNCONFIRMED);
        assertThat(calls).hasValue(0);
        assertThat(number(receipt.jobId(), "current_attempt")).isOne();
        assertThat(count("ai_suggestion_attempts")).isOne();
    }

    @Test
    void a_completion_between_result_lookup_and_claim_prevents_new_generation() {
        TicketReceiptResult receipt = receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        expireLease(receipt.jobId());
        var completingReader = new AiSuggestionResultService(null, null) {
            @Override public Optional<AiSuggestionStoredResult> findStoredResult(long jobId) {
                var oldSnapshot = results.findStoredResult(jobId);
                assertThat(results.complete(first, validator.validate(VALID)).name()).isEqualTo("STORED");
                return oldSnapshot;
            }
        };
        AtomicInteger calls = new AtomicInteger();

        assertThat(worker((input, timeout, kind) -> { calls.incrementAndGet(); return VALID; }, completingReader)
                .runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(0);
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(count("ticket_suggestions")).isOne();
        assertOriginal(receipt);
    }

    @Test
    void a_block_recorded_after_candidate_lookup_is_rechecked_at_the_actual_claim() {
        receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        expireLease(first.jobId());
        var blockingReader = new AiSuggestionResultService(null, null) {
            @Override public Optional<AiSuggestionStoredResult> findStoredResult(long jobId) {
                var oldSnapshot = results.findStoredResult(jobId);
                assertThat(claims.recordAttemptResultIfCurrent(first, AiAttemptResultCode.AUTO_RETRY_BLOCKED)).isTrue();
                return oldSnapshot;
            }
        };
        AtomicInteger calls = new AtomicInteger();

        assertThat(worker((input, timeout, kind) -> { calls.incrementAndGet(); return VALID; }, blockingReader)
                .runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(0);
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(number(first.jobId(), "current_attempt")).isOne();
        assertThat(code(first)).isEqualTo("AUTO_RETRY_BLOCKED");
    }

    @Test
    void a_result_present_with_inconsistent_running_state_is_not_regenerated() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        expireLease(claim.jobId());
        // Test-owned inconsistent rows: the production result Service commits these together.
        jdbc.update("INSERT INTO ticket_suggestions (job_id, summary, priority) VALUES (?, 'Synthetic saved result', 'NORMAL')",
                claim.jobId());

        assertThat(claims.findNextRecoveryCandidate()).isEmpty();
        assertThat(claims.claimRecoveryAfterResultCheck(claim.jobId(), claim.attemptNumber())).isEmpty();
        assertThat(count("ai_suggestion_attempts")).isOne();
    }

    @Test
    void recovery_reservation_insert_failure_rolls_back_the_attempt_increment() {
        TicketReceiptResult receipt = receive();
        claims.claimNextPending().orElseThrow();
        expireLease(receipt.jobId());
        var before = jdbc.queryForMap("SELECT * FROM ai_suggestion_jobs WHERE id = ?", receipt.jobId());
        AtomicInteger calls = new AtomicInteger();
        jdbc.execute("ALTER TABLE ai_suggestion_attempts ADD CONSTRAINT test_recovery_reservation_failure CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> worker((input, timeout, kind) -> { calls.incrementAndGet(); return VALID; }).runOnce())
                    .isInstanceOf(IllegalStateException.class).hasMessage("AI_JOB_CLAIM_FAILED");
            assertThat(jdbc.queryForMap("SELECT * FROM ai_suggestion_jobs WHERE id = ?", receipt.jobId())).isEqualTo(before);
            assertThat(count("ai_suggestion_attempts")).isOne();
            assertThat(calls).hasValue(0);
            assertOriginal(receipt);
        } finally {
            jdbc.execute("ALTER TABLE ai_suggestion_attempts DROP CONSTRAINT test_recovery_reservation_failure");
        }
    }

    @Test
    void competing_workers_create_only_one_recovery_reservation_and_one_new_call() throws Exception {
        TicketReceiptResult receipt = receive();
        claims.claimNextPending().orElseThrow();
        expireLease(receipt.jobId());
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionProvider provider = (input, timeout, kind) -> { calls.incrementAndGet(); return VALID; };
        var first = worker(provider);
        var second = worker(provider);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { await(start); return first.runOnce().outcome(); });
            var b = executor.submit(() -> { await(start); return second.runOnce().outcome(); });
            start.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(Outcome.STORED, Outcome.NO_JOB);
        }
        assertThat(calls).hasValue(1);
        assertThat(count("ai_suggestion_attempts")).isEqualTo(2);
        assertThat(count("ticket_suggestions")).isOne();
    }

    @Test
    void a_result_classification_and_recovery_claim_serialize_on_the_same_job_lock() throws Exception {
        receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        expireLease(first.jobId());
        assertThat(claims.findNextRecoveryCandidate()).isPresent();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var update = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertThat(execution.recordAttemptResultIfCurrent(first, AiAttemptResultCode.AUTO_RETRY_BLOCKED)).isTrue();
                locked.countDown();
                await(release);
            }));
            try {
                await(locked);
                assertThat(claims.claimRecoveryAfterResultCheck(first.jobId(), first.attemptNumber())).isEmpty();
            } finally {
                release.countDown();
            }
            update.get(5, TimeUnit.SECONDS);
        }
        assertThat(claims.claimRecoveryAfterResultCheck(first.jobId(), first.attemptNumber())).isEmpty();
        assertThat(number(first.jobId(), "current_attempt")).isOne();
        assertThat(code(first)).isEqualTo("AUTO_RETRY_BLOCKED");
    }

    @Test
    void an_old_attempt_cannot_record_a_result_on_the_current_attempt_or_unblock_it() {
        receive();
        AiJobClaim first = claims.claimNextPending().orElseThrow();
        expireLease(first.jobId());
        AiJobClaim second = claims.claimRecoveryAfterResultCheck(first.jobId(), 1).orElseThrow();

        assertThat(claims.recordAttemptResultIfCurrent(first, AiAttemptResultCode.AUTO_RETRY_BLOCKED)).isFalse();
        assertThat(code(second)).isEqualTo("UNCONFIRMED");
        assertThat(claims.recordAttemptResultIfCurrent(second, AiAttemptResultCode.AUTO_RETRY_BLOCKED)).isTrue();
        assertThat(claims.recordAttemptResultIfCurrent(second, AiAttemptResultCode.OUTCOME_UNKNOWN)).isFalse();
        assertThat(code(second)).isEqualTo("AUTO_RETRY_BLOCKED");
        assertThatThrownBy(() -> claims.recordAttemptResultIfCurrent(second, AiAttemptResultCode.UNCONFIRMED))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_ATTEMPT_RESULT_RESET_FORBIDDEN");
    }

    @Test
    void exhausted_generation_cap_does_not_allow_a_fourth_claim_but_current_output_can_still_commit() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        for (int attempt = 2; attempt <= 3; attempt++) {
            expireLease(receipt.jobId());
            claim = claims.claimRecoveryAfterResultCheck(claim.jobId(), claim.attemptNumber()).orElseThrow();
        }
        expireLease(receipt.jobId());
        AtomicInteger calls = new AtomicInteger();

        assertThat(worker((input, timeout, kind) -> { calls.incrementAndGet(); return VALID; }).runOnce().outcome())
                .isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(0);
        assertThat(count("ai_suggestion_attempts")).isEqualTo(3);
        assertThat(results.complete(claim, validator.validate(VALID)).name()).isEqualTo("STORED");
        assertOriginal(receipt);
    }

    @Test
    void lease_expiry_without_backoff_does_not_allow_recovery() {
        TicketReceiptResult receipt = receive();
        claims.claimNextPending().orElseThrow();
        jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                receipt.jobId());

        assertThat(claims.findNextRecoveryCandidate()).isEmpty();
        assertThat(claims.claimRecoveryAfterResultCheck(receipt.jobId(), 1)).isEmpty();
        assertThat(count("ai_suggestion_attempts")).isOne();
    }

    @Test
    void original_deadline_closes_a_blocked_job_without_cancelling_the_receipt() {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = worker((input, timeout, kind) -> {
            throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT, null);
        }).runOnce().claim();
        jdbc.update("""
                UPDATE ai_suggestion_jobs
                SET first_started_at = clock_timestamp() - INTERVAL '10 minutes',
                    processing_deadline_at = clock_timestamp() - INTERVAL '1 minute',
                    lease_expires_at = clock_timestamp() - INTERVAL '2 minutes'
                WHERE id = ?
                """, receipt.jobId());

        assertThat(worker((input, timeout, kind) -> VALID).runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?",
                String.class, receipt.jobId())).isEqualTo("JOB_PROCESSING_DEADLINE_EXCEEDED");
        assertThat(code(claim)).isEqualTo("AUTO_RETRY_BLOCKED");
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertOriginal(receipt);
    }

    @Test
    void completed_abstain_is_not_mistaken_for_a_missing_suggestion() {
        receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        results.complete(claim, validator.validate("""
                {"decision":"ABSTAIN","summary":null,"categories":null,"priority":null}
                """));
        AtomicInteger calls = new AtomicInteger();

        assertThat(worker((input, timeout, kind) -> { calls.incrementAndGet(); return VALID; }).runOnce().outcome())
                .isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(0);
        assertThat(count("ticket_suggestions")).isZero();
        assertThat(count("ai_suggestion_attempts")).isOne();
    }

    @Test
    void result_recording_failure_does_not_claim_a_durable_observation() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        jdbc.execute("ALTER TABLE ai_suggestion_attempts ADD CONSTRAINT test_result_record_failure "
                + "CHECK (result_code = 'UNCONFIRMED') NOT VALID");
        try {
            assertThatThrownBy(() -> worker((input, timeout, kind) -> {
                calls.incrementAndGet();
                throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT, null);
            }).runOnce()).isInstanceOf(IllegalStateException.class).hasMessage("AI_ATTEMPT_RESULT_RECORD_FAILED");
            assertThat(jdbc.queryForObject("SELECT result_code FROM ai_suggestion_attempts WHERE job_id = ?",
                    String.class, receipt.jobId())).isEqualTo("UNCONFIRMED");
            assertThat(calls).hasValue(1);
            assertThat(count("ai_suggestion_attempts")).isOne();
            assertOriginal(receipt);
        } finally {
            jdbc.execute("ALTER TABLE ai_suggestion_attempts DROP CONSTRAINT test_result_record_failure");
        }
    }

    private AiSuggestionJobWorker worker(AiSuggestionProvider provider) {
        return worker(provider, results);
    }

    private AiSuggestionJobWorker worker(AiSuggestionProvider provider, AiSuggestionResultService resultService) {
        var privacy = new AiInputPrivacyGuard(List.of(new SensitiveFragment("SYNTHETIC_CONTACT", SensitiveType.EMAIL)));
        var processor = new AiSuggestionJobProcessor(claims, inputs, privacy, provider,
                validator, resultService, Clock.systemUTC());
        return new AiSuggestionJobWorker(processor, claims);
    }

    private TicketReceiptResult receive() {
        return receipts.receive("Synthetic restored question", ORIGINAL, "synthetic-author");
    }

    private void expireLease(long jobId) {
        jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE id = ?", jobId);
    }

    private long number(long jobId, String column) {
        // Literal test-owned metadata names only.
        return jdbc.queryForObject("SELECT " + column + " FROM ai_suggestion_jobs WHERE id = ?", Long.class, jobId);
    }

    private String code(AiJobClaim claim) {
        return jdbc.queryForObject("SELECT result_code FROM ai_suggestion_attempts WHERE job_id = ? AND attempt_number = ?",
                String.class, claim.jobId(), claim.attemptNumber());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertOriginal(TicketReceiptResult receipt) {
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?", String.class, receipt.messageId()))
                .isEqualTo(ORIGINAL);
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, receipt.ticket().id()))
                .isEqualTo("OPEN");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("AI_RECOVERY_TEST_SYNC_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI_RECOVERY_TEST_SYNC_INTERRUPTED");
        }
    }
}
