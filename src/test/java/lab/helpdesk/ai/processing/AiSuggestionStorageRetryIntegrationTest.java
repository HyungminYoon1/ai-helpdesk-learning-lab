package lab.helpdesk.ai.processing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.JdbcAiSuggestionInputRepository;
import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobFailureCode;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.suggestion.AiSuggestionCompletion;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.suggestion.AiSuggestionStorageException;
import lab.helpdesk.ai.suggestion.AiSuggestionStoredResult;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;
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

/** Real PostgreSQL writes/rollbacks; controlled Provider and clock. No live AI or Browser. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.main.banner-mode=off"})
@ActiveProfiles("postgres")
@Testcontainers
class AiSuggestionStorageRetryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    private static final String VALID = """
            {"decision":"SUGGEST","summary":"Synthetic login question",
             "categories":["ACCOUNT"],"priority":"NORMAL"}
            """;

    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private AiSuggestionResultService results;
    @Autowired private JdbcAiSuggestionInputRepository inputs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private MutableClock clock;
    private ObservedResults observed;
    private AtomicInteger providerCalls;

    @BeforeEach
    void prepare_only_the_verified_testcontainer_database() {
        Boolean isolated = jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) throw new IllegalStateException("AI_STORAGE_TEST_DATABASE_REQUIRED");
        // Test-only failure constraints are removed before deleting rows in this isolated DB.
        jdbc.execute("ALTER TABLE ticket_suggestion_categories DROP CONSTRAINT IF EXISTS storage_test_reject_category");
        jdbc.execute("ALTER TABLE ai_suggestion_jobs DROP CONSTRAINT IF EXISTS storage_test_reject_abstain");
        jdbc.update("DELETE FROM ticket_suggestion_categories");
        jdbc.update("DELETE FROM ticket_suggestions");
        jdbc.update("DELETE FROM ai_suggestion_attempts");
        jdbc.update("DELETE FROM ai_suggestion_jobs");
        jdbc.update("DELETE FROM ticket_messages");
        jdbc.update("DELETE FROM tickets");
        clock = new MutableClock();
        observed = new ObservedResults();
        providerCalls = new AtomicInteger();
    }

    @Test
    void retries_the_same_validated_object_after_five_seconds_without_generating_again() {
        TicketReceiptResult receipt = receive();
        rejectCategoryWrites();
        AiSuggestionJobWorker worker = worker(VALID);
        var first = worker.runOnce();
        assertThat(first.outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        assertThat(count("ticket_suggestions")).isZero();
        assertThat(count("ticket_suggestion_categories")).isZero();
        permitCategoryWrites();

        clock.advance(Duration.ofMillis(4999));
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_RETRY_WAITING);
        assertThat(observed.writes).hasSize(1);
        clock.advance(Duration.ofMillis(1));
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(observed.writes).hasSize(2);
        assertThat(observed.writes.get(1)).isSameAs(first.pendingStorageOutput());
        assertThat(count("ticket_suggestions")).isOne();
        assertThat(count("ticket_suggestion_categories")).isOne();
        assertThat(status(receipt)).isEqualTo("SUCCEEDED");
        assertSingleGenerationAndOriginal(receipt);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
    }

    @Test
    void three_failed_saves_end_only_the_current_job_without_a_fourth_save_or_new_generation() {
        TicketReceiptResult receipt = receive();
        rejectCategoryWrites();
        AiSuggestionJobWorker worker = worker(VALID);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        clock.advance(Duration.ofSeconds(5));
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        clock.advance(Duration.ofSeconds(5));
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(observed.writes).hasSize(3);
        assertThat(observed.writes).allSatisfy(output -> assertThat(output).isSameAs(observed.writes.getFirst()));
        assertThat(status(receipt)).isEqualTo("FAILED");
        assertThat(failureCode(receipt)).isEqualTo("RESULT_STORAGE_RETRY_EXHAUSTED");
        assertThat(count("ticket_suggestions")).isZero();
        assertSingleGenerationAndOriginal(receipt);
    }

    @Test
    void an_unacknowledged_successful_commit_is_reconciled_without_inserting_again() {
        TicketReceiptResult receipt = receive();
        observed.loseNextAcknowledgement = true;
        AiSuggestionJobWorker worker = worker(VALID);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        assertThat(status(receipt)).isEqualTo("SUCCEEDED");
        assertThat(count("ticket_suggestions")).isOne();
        observed.failReads = false;
        clock.advance(Duration.ofSeconds(5));

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(observed.writes).hasSize(1);
        assertThat(count("ticket_suggestions")).isOne();
        assertSingleGenerationAndOriginal(receipt);
    }

    @Test
    void a_failed_result_read_neither_permits_a_save_nor_consumes_a_storage_attempt() {
        TicketReceiptResult receipt = receive();
        rejectCategoryWrites();
        AiSuggestionJobWorker worker = worker(VALID);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        permitCategoryWrites();
        observed.failReads = true;
        clock.advance(Duration.ofSeconds(5));
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_STATE_UNCONFIRMED);
        assertThat(observed.writes).hasSize(1);
        assertThat(status(receipt)).isEqualTo("RUNNING");
        observed.failReads = false;
        clock.advance(Duration.ofSeconds(5));

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(observed.writes).hasSize(2);
        assertSingleGenerationAndOriginal(receipt);
    }

    @Test
    void a_replaced_attempt_discards_the_old_object_without_saving_or_failing_the_new_owner() {
        TicketReceiptResult receipt = receive();
        rejectCategoryWrites();
        AiSuggestionJobWorker worker = worker(VALID);
        var first = worker.runOnce();
        permitCategoryWrites();
        jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '10 seconds' WHERE id = ?",
                receipt.jobId());
        assertThat(results.findStoredResult(receipt.jobId()).orElseThrow().suggestion()).isEmpty();
        assertThat(claims.claimRecoveryAfterResultCheck(receipt.jobId(), first.claim().attemptNumber())).isPresent();
        clock.advance(Duration.ofSeconds(5));

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NOT_CURRENT);
        assertThat(observed.writes).hasSize(1);
        assertThat(status(receipt)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT current_attempt FROM ai_suggestion_jobs WHERE id = ?",
                Integer.class, receipt.jobId())).isEqualTo(2);
        assertThat(count("ticket_suggestions")).isZero();
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void the_original_processing_deadline_prevents_a_late_storage_retry() {
        TicketReceiptResult receipt = receive();
        rejectCategoryWrites();
        AiSuggestionJobWorker worker = worker(VALID);
        var first = worker.runOnce();
        permitCategoryWrites();
        expireInDatabase(receipt);
        clock.set(first.claim().processingDeadlineAt());

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(observed.writes).hasSize(1);
        assertThat(failureCode(receipt)).isEqualTo("JOB_PROCESSING_DEADLINE_EXCEEDED");
        assertThat(count("ticket_suggestions")).isZero();
        assertSingleGenerationAndOriginal(receipt);
    }

    @Test
    void a_confirmed_abstain_retries_its_completion_without_creating_a_suggestion() {
        TicketReceiptResult receipt = receive();
        jdbc.execute("ALTER TABLE ai_suggestion_jobs ADD CONSTRAINT storage_test_reject_abstain CHECK (status <> 'ABSTAINED')");
        AiSuggestionJobWorker worker = worker("""
                {"decision":"ABSTAIN","summary":null,"categories":null,"priority":null}
                """);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        jdbc.execute("ALTER TABLE ai_suggestion_jobs DROP CONSTRAINT storage_test_reject_abstain");
        clock.advance(Duration.ofSeconds(5));

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.ABSTAINED);
        assertThat(status(receipt)).isEqualTo("ABSTAINED");
        assertThat(count("ticket_suggestions")).isZero();
        assertThat(count("ticket_suggestion_categories")).isZero();
        assertSingleGenerationAndOriginal(receipt);
    }

    @Test
    void exhausted_generation_allowance_does_not_discard_a_current_validated_output() {
        TicketReceiptResult receipt = receive();
        jdbc.update("UPDATE ai_suggestion_jobs SET max_generation_attempts = 1 WHERE id = ?", receipt.jobId());
        rejectCategoryWrites();
        AiSuggestionJobWorker worker = worker(VALID);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        permitCategoryWrites();
        clock.advance(Duration.ofSeconds(5));

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(observed.writes).hasSize(2);
        assertSingleGenerationAndOriginal(receipt);
    }

    @Test
    void a_completion_between_inspection_and_exhaustion_is_not_overwritten_as_failed() {
        TicketReceiptResult receipt = receive();
        rejectCategoryWrites();
        var processor = processor(VALID, claims);
        AiSuggestionJobWorker worker = new AiSuggestionJobWorker(processor, claims,
                new AiSuggestionWorkerSettings(1000, 1, 5000), clock);
        var first = worker.runOnce();
        permitCategoryWrites();
        // Capture a real RUNNING snapshot, then complete in a separate Transaction before returning it.
        observed.afterNextRead = () -> results.complete(first.claim(), first.pendingStorageOutput());

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(status(receipt)).isEqualTo("SUCCEEDED");
        assertThat(failureCode(receipt)).isNull();
        assertThat(observed.writes).hasSize(1);
        assertThat(count("ticket_suggestions")).isOne();
        assertSingleGenerationAndOriginal(receipt);
    }

    @Test
    void an_unacknowledged_terminal_update_is_not_reported_as_confirmed_until_reconciled() {
        TicketReceiptResult receipt = receive();
        rejectCategoryWrites();
        var uncertainClaims = new AiSuggestionJobClaimService(null) {
            @Override public Optional<AiJobClaim> claimNextPending() { return claims.claimNextPending(); }
            @Override public boolean failIfCurrent(AiJobClaim claim, AiJobFailureCode code) {
                claims.failIfCurrent(claim, code);
                throw new AiSuggestionStorageException();
            }
        };
        var processor = processor(VALID, uncertainClaims);
        AiSuggestionJobWorker worker = new AiSuggestionJobWorker(processor, uncertainClaims,
                new AiSuggestionWorkerSettings(1000, 1, 5000), clock);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_PENDING);

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_STATE_UNCONFIRMED);
        assertThat(status(receipt)).isEqualTo("FAILED");
        clock.advance(Duration.ofSeconds(5));
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(observed.writes).hasSize(1);
        assertSingleGenerationAndOriginal(receipt);
    }

    @Test
    void unreadable_state_at_the_deadline_releases_the_object_without_claiming_a_database_outcome() {
        TicketReceiptResult receipt = receive();
        rejectCategoryWrites();
        AiSuggestionJobWorker worker = worker(VALID);
        var first = worker.runOnce();
        observed.failReads = true;
        clock.set(first.claim().processingDeadlineAt());
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_STATE_UNCONFIRMED);
        assertThat(observed.writes).hasSize(1);
        assertThat(status(receipt)).isEqualTo("RUNNING");

        // When DB access returns, normal pending polling expires the old job and can process a new one.
        observed.failReads = false;
        permitCategoryWrites();
        expireInDatabase(receipt);
        TicketReceiptResult next = receive();
        clock.set(Instant.now());
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(status(receipt)).isEqualTo("FAILED");
        assertThat(status(next)).isEqualTo("SUCCEEDED");
        assertThat(providerCalls).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?",
                Long.class, receipt.jobId())).isZero();
    }

    @Test
    void a_whole_tick_transaction_is_rejected_even_while_a_validated_object_is_pending() {
        receive();
        rejectCategoryWrites();
        AiSuggestionJobWorker worker = worker(VALID);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORAGE_PENDING);
        permitCategoryWrites();
        clock.advance(Duration.ofSeconds(5));
        int readsBefore = observed.reads;

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx -> worker.runOnce()))
                .isInstanceOf(IllegalStateException.class).hasMessage("AI_PROCESSOR_REQUIRES_NO_OUTER_TRANSACTION");
        assertThat(observed.reads).isEqualTo(readsBefore);
        assertThat(observed.writes).hasSize(1);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(providerCalls).hasValue(1);
    }

    private AiSuggestionJobWorker worker(String output) {
        return new AiSuggestionJobWorker(processor(output, claims), claims,
                new AiSuggestionWorkerSettings(1000), clock);
    }

    private AiSuggestionJobProcessor processor(String output, AiSuggestionJobClaimService claimService) {
        var privacy = new AiInputPrivacyGuard(List.of(new SensitiveFragment("SYNTHETIC_CONTACT", SensitiveType.EMAIL)));
        return new AiSuggestionJobProcessor(claimService, inputs, privacy, (input, timeout, kind) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            providerCalls.incrementAndGet();
            return output;
        }, new AiSuggestionOutputValidator(200), observed, clock);
    }

    private TicketReceiptResult receive() {
        return receipts.receive("Synthetic login question", "Original customer message.", "synthetic-author");
    }

    private void rejectCategoryWrites() {
        jdbc.execute("ALTER TABLE ticket_suggestion_categories ADD CONSTRAINT storage_test_reject_category CHECK (category <> 'ACCOUNT')");
    }

    private void permitCategoryWrites() {
        jdbc.execute("ALTER TABLE ticket_suggestion_categories DROP CONSTRAINT storage_test_reject_category");
    }

    private void expireInDatabase(TicketReceiptResult receipt) {
        jdbc.update("UPDATE ai_suggestion_jobs SET first_started_at = statement_timestamp() - INTERVAL '301 seconds', "
                + "processing_deadline_at = statement_timestamp() - INTERVAL '1 second', "
                + "lease_expires_at = statement_timestamp() - INTERVAL '1 second' WHERE id = ?", receipt.jobId());
    }

    private String status(TicketReceiptResult receipt) {
        return jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId());
    }

    private String failureCode(TicketReceiptResult receipt) {
        return jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId());
    }

    private long count(String table) {
        // Only literal test-owned table names are passed to this helper.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertSingleGenerationAndOriginal(TicketReceiptResult receipt) {
        assertThat(providerCalls).hasValue(1);
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(jdbc.queryForObject("SELECT reserved_generation_count FROM ai_suggestion_jobs WHERE id = ?",
                Integer.class, receipt.jobId())).isOne();
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?", String.class, receipt.messageId()))
                .isEqualTo("Original customer message.");
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, receipt.ticket().id()))
                .isEqualTo("OPEN");
    }

    // This decorator delegates to the actual Spring proxy, so PostgreSQL transactions are real.
    private final class ObservedResults extends AiSuggestionResultService {
        private final List<ContractValidatedOutput> writes = new ArrayList<>();
        private int reads;
        private boolean failReads;
        private boolean loseNextAcknowledgement;
        private Runnable afterNextRead;

        private ObservedResults() { super(null, null); }

        @Override
        public AiSuggestionCompletion complete(AiJobClaim claim, ContractValidatedOutput output) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            writes.add(output);
            AiSuggestionCompletion completion = results.complete(claim, output);
            if (loseNextAcknowledgement) {
                loseNextAcknowledgement = false;
                failReads = true;
                throw new AiSuggestionStorageException();
            }
            return completion;
        }

        @Override
        public Optional<AiSuggestionStoredResult> findStoredResult(long jobId) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            reads++;
            if (failReads) throw new AiSuggestionStorageException();
            Optional<AiSuggestionStoredResult> stored = results.findStoredResult(jobId);
            if (afterNextRead != null) {
                Runnable action = afterNextRead;
                afterNextRead = null;
                action.run();
            }
            return stored;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
        private void advance(Duration duration) { now = now.plus(duration); }
        private void set(Instant instant) { now = instant; }
    }
}
