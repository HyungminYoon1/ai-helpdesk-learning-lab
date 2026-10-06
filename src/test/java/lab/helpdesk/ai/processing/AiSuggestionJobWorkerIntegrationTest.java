package lab.helpdesk.ai.processing;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.JdbcAiSuggestionInputRepository;
import lab.helpdesk.ai.job.AiJobRequestKind;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.provider.AiProviderFailureException;
import lab.helpdesk.ai.provider.AiProviderFailureException.Kind;
import lab.helpdesk.ai.provider.AiProviderFailureException.Reason;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL and controlled failures. Not a live AI or Browser experiment. */
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.main.banner-mode=off"})
@ActiveProfiles("postgres")
@Testcontainers
class AiSuggestionJobWorkerIntegrationTest {

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
    @Autowired private ApplicationContext context;

    @BeforeEach
    void clear_only_the_verified_testcontainer_database() {
        Boolean isolated = jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) throw new IllegalStateException("AI_WORKER_TEST_DATABASE_REQUIRED");
        jdbc.update("DELETE FROM ticket_suggestion_categories");
        jdbc.update("DELETE FROM ticket_suggestions");
        jdbc.update("DELETE FROM ai_suggestion_attempts");
        jdbc.update("DELETE FROM ai_suggestion_jobs");
        jdbc.update("DELETE FROM ticket_messages");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    void a_confirmed_rate_limit_waits_then_sends_one_new_reserved_request() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobWorker worker = worker((input, timeout, kind) -> {
            if (calls.incrementAndGet() == 1) {
                throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT,
                        Duration.ofSeconds(15));
            }
            assertThat(kind).isEqualTo(AiJobRequestKind.TEMPORARY_RETRY);
            assertThat(count("ai_suggestion_attempts")).isEqualTo(2);
            return VALID;
        });

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.TEMPORARY_RETRY_SCHEDULED);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(1);
        assertThat(status(receipt)).isEqualTo("PENDING");
        assertThat(count("ticket_suggestions")).isZero();
        makeDue(receipt);

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(calls).hasValue(2);
        assertThat(status(receipt)).isEqualTo("SUCCEEDED");
        assertThat(count("ticket_suggestions")).isOne();
        assertThat(jdbc.queryForObject("SELECT reserved_output_repair_count FROM ai_suggestion_jobs WHERE id = ?",
                Integer.class, receipt.jobId())).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void billing_failure_is_terminal_even_with_unused_attempts() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobWorker worker = worker((input, timeout, kind) -> {
            calls.incrementAndGet();
            throw new AiProviderFailureException(Kind.CONFIGURATION, Reason.BILLING_OR_QUOTA, null);
        });

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(1);
        assertThat(status(receipt)).isEqualTo("FAILED");
        assertThat(count("ticket_suggestions")).isZero();
        assertReceiptPreserved(receipt);
    }

    @Test
    void a_rate_limit_without_a_usable_wait_hint_does_not_gain_a_default_replay_policy() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobWorker worker = worker((input, timeout, kind) -> {
            calls.incrementAndGet();
            throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT, null);
        });

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.TEMPORARY_REJECTION);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(1);
        assertThat(status(receipt)).isEqualTo("RUNNING");
        assertThat(count("ai_suggestion_attempts")).isOne();
    }

    @Test
    void an_unapproved_temporary_failure_is_not_treated_as_a_confirmed_rate_limit() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobWorker worker = worker((input, timeout, kind) -> {
            calls.incrementAndGet();
            throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.OVERLOADED,
                    Duration.ofSeconds(15));
        });

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.TEMPORARY_REJECTION);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(1);
        assertThat(status(receipt)).isEqualTo("RUNNING");
    }

    @Test
    void a_missing_field_uses_the_existing_repair_policy_not_a_rate_limit_retry() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobWorker worker = worker((input, timeout, kind) -> {
            if (calls.incrementAndGet() == 1) return """
                    {"decision":"SUGGEST","summary":"Synthetic question","categories":["ACCOUNT"]}
                    """;
            assertThat(kind).isEqualTo(AiJobRequestKind.OUTPUT_REPAIR);
            return VALID;
        });

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.OUTPUT_REPAIR_SCHEDULED);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        makeDue(receipt);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(calls).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT reserved_output_repair_count FROM ai_suggestion_jobs WHERE id = ?",
                Integer.class, receipt.jobId())).isOne();
    }

    @Test
    void an_unknown_outcome_is_not_replayed_by_the_next_poll() {
        TicketReceiptResult receipt = receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobWorker worker = worker((input, timeout, kind) -> {
            calls.incrementAndGet();
            throw new AiProviderFailureException(Kind.OUTCOME_UNKNOWN);
        });

        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.PROVIDER_OUTCOME_UNKNOWN);
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(calls).hasValue(1);
        assertThat(status(receipt)).isEqualTo("RUNNING");
    }

    @Test
    void competing_ticks_do_not_send_or_store_the_same_job_twice() throws Exception {
        receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobWorker worker = worker((input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(worker::runOnce);
            var second = executor.submit(worker::runOnce);
            assertThat(List.of(first.get().outcome(), second.get().outcome()))
                    .containsExactlyInAnyOrder(Outcome.STORED, Outcome.NO_JOB);
        }
        assertThat(calls).hasValue(1);
        assertThat(count("ai_suggestion_attempts")).isOne();
        assertThat(count("ticket_suggestions")).isOne();
    }

    @Test
    void the_poller_is_not_registered_by_the_existing_postgres_profile() {
        assertThat(context.getBeansOfType(AiSuggestionJobWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(AiSuggestionWorkerScheduler.class)).isEmpty();
    }

    @Test
    void a_whole_tick_transaction_is_rejected_before_reserving_or_calling() {
        receive();
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionJobWorker worker = worker((input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        });
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(tx -> worker.runOnce()))
                .isInstanceOf(IllegalStateException.class).hasMessage("AI_PROCESSOR_REQUIRES_NO_OUTER_TRANSACTION");
        assertThat(calls).hasValue(0);
        assertThat(count("ai_suggestion_attempts")).isZero();
    }

    private AiSuggestionJobWorker worker(AiSuggestionProvider provider) {
        var privacy = new AiInputPrivacyGuard(List.of(new SensitiveFragment("SYNTHETIC_CONTACT", SensitiveType.EMAIL)));
        var processor = new AiSuggestionJobProcessor(claims, inputs, privacy, provider,
                new AiSuggestionOutputValidator(200), results, Clock.systemUTC());
        return new AiSuggestionJobWorker(processor, claims);
    }

    private TicketReceiptResult receive() {
        return receipts.receive("Synthetic login question", "Original customer message.", "synthetic-author");
    }

    private void makeDue(TicketReceiptResult receipt) {
        jdbc.update("UPDATE ai_suggestion_jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                receipt.jobId());
    }

    private String status(TicketReceiptResult receipt) {
        return jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId());
    }

    private long count(String table) {
        // Literal test-owned table names only.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertReceiptPreserved(TicketReceiptResult receipt) {
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?",
                String.class, receipt.messageId())).isEqualTo("Original customer message.");
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?",
                String.class, receipt.ticket().id())).isEqualTo("OPEN");
    }
}
