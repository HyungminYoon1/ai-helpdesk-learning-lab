package lab.helpdesk.ai.processing;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import lab.helpdesk.HelpdeskApplication;
import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.JdbcAiSuggestionInputRepository;
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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Same JVM, new Spring Contexts, same PostgreSQL. No real Provider or Browser. */
@Testcontainers
class AiSuggestionWorkerContextIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    private static final String VALID = """
            {"decision":"SUGGEST","summary":"Synthetic saved question",
             "categories":["ACCOUNT"],"priority":"NORMAL"}
            """;
    private JdbcTemplate jdbc;

    @BeforeEach
    void clear_only_the_verified_testcontainer_database() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        String url = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        if (url == null || !url.split("\\?", 2)[0].equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0])) {
            throw new IllegalStateException("AI_WORKER_CONTEXT_TEST_DATABASE_REQUIRED");
        }
        jdbc.update("DELETE FROM ticket_suggestion_categories");
        jdbc.update("DELETE FROM ticket_suggestions");
        jdbc.update("DELETE FROM ai_suggestion_attempts");
        jdbc.update("DELETE FROM ai_suggestion_jobs");
        jdbc.update("DELETE FROM ticket_messages");
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    void a_new_context_automatically_processes_a_committed_pending_job() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionProvider provider = (input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        };
        TicketReceiptResult receipt;
        JdbcAiSuggestionInputRepository firstRepository;
        ConfigurableApplicationContext first = start(false, provider);
        try (first) {
            assertThat(first.getBeansOfType(AiSuggestionJobWorker.class)).isEmpty();
            receipt = receive(first);
            firstRepository = first.getBean(JdbcAiSuggestionInputRepository.class);
            assertThat(calls).hasValue(0);
        }
        assertThat(first.isActive()).isFalse();

        try (ConfigurableApplicationContext second = start(true, provider)) {
            assertThat(second.getBean(JdbcAiSuggestionInputRepository.class)).isNotSameAs(firstRepository);
            awaitSucceeded(receipt.jobId());
            assertThat(calls).hasValue(1);
            assertThat(count("ticket_suggestions")).isOne();
            assertThat(count("ai_suggestion_attempts")).isOne();
            assertOriginal(receipt);
        }
    }

    @Test
    void a_new_context_preserves_a_future_retry_time_policy_counts_and_deadline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionProvider provider = (input, timeout, kind) -> {
            if (calls.incrementAndGet() == 1) {
                throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT,
                        Duration.ofSeconds(15));
            }
            return VALID;
        };
        TicketReceiptResult receipt;
        java.time.OffsetDateTime deadline;
        java.time.OffsetDateTime due;
        try (ConfigurableApplicationContext first = start(false, provider)) {
            receipt = receive(first);
            AiSuggestionJobWorker worker = manualWorker(first, provider);
            assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.TEMPORARY_RETRY_SCHEDULED);
            deadline = time("processing_deadline_at", receipt.jobId());
            due = time("next_attempt_at", receipt.jobId());
        }

        try (ConfigurableApplicationContext second = start(true, provider)) {
            Thread.sleep(100);
            assertThat(calls).hasValue(1);
            assertThat(time("processing_deadline_at", receipt.jobId())).isEqualTo(deadline);
            assertThat(time("next_attempt_at", receipt.jobId())).isEqualTo(due);
            assertThat(jdbc.queryForObject("SELECT reserved_generation_count FROM ai_suggestion_jobs WHERE id = ?",
                    Integer.class, receipt.jobId())).isOne();
            // The 15-second wait was asserted above; move this test-owned boundary instead of sleeping 15 seconds.
            jdbc.update("UPDATE ai_suggestion_jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                    receipt.jobId());
            awaitSucceeded(receipt.jobId());
            assertThat(calls).hasValue(2);
            assertThat(count("ai_suggestion_attempts")).isEqualTo(2);
            assertThat(count("ticket_suggestions")).isOne();
            assertThat(time("processing_deadline_at", receipt.jobId())).isEqualTo(deadline);
            assertOriginal(receipt);
        }
    }

    @Test
    void restarting_does_not_reopen_a_failed_job_while_the_poller_processes_another_job() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TicketReceiptResult failed;
        try (ConfigurableApplicationContext first = start(false, (input, timeout, kind) -> VALID)) {
            failed = receive(first);
            AiSuggestionJobWorker worker = manualWorker(first, (input, timeout, kind) -> {
                calls.incrementAndGet();
                throw new AiProviderFailureException(Kind.CONFIGURATION, Reason.BILLING_OR_QUOTA, null);
            });
            assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.FAILED);
        }

        try (ConfigurableApplicationContext second = start(true, (input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        })) {
            TicketReceiptResult fresh = receive(second);
            awaitSucceeded(fresh.jobId());
            assertThat(calls).hasValue(2); // One failed old request and one successful different Job.
            assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, failed.jobId()))
                    .isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts WHERE job_id = ?", Long.class, failed.jobId()))
                    .isOne();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?", Long.class, failed.jobId()))
                    .isZero();
            assertOriginal(failed);
        }
    }

    @Test
    void a_new_context_recovers_an_unknown_attempt_after_result_and_eligibility_checks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TicketReceiptResult unknown;
        try (ConfigurableApplicationContext first = start(false, (input, timeout, kind) -> VALID)) {
            unknown = receive(first);
            AiSuggestionJobWorker worker = manualWorker(first, (input, timeout, kind) -> {
                calls.incrementAndGet();
                throw new AiProviderFailureException(Kind.OUTCOME_UNKNOWN);
            });
            assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.PROVIDER_OUTCOME_UNKNOWN);
            jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE id = ?",
                    unknown.jobId());
        }

        try (ConfigurableApplicationContext second = start(true, (input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        })) {
            awaitSucceeded(unknown.jobId());
            TicketReceiptResult fresh = receive(second);
            awaitSucceeded(fresh.jobId());
            assertThat(calls).hasValue(3);
            assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, unknown.jobId()))
                    .isEqualTo("SUCCEEDED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts WHERE job_id = ?", Long.class, unknown.jobId()))
                    .isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?", Long.class, unknown.jobId()))
                    .isOne();
            assertOriginal(unknown);
        }
    }

    @Test
    void a_new_context_does_not_replay_a_known_rate_limit_without_a_wait_hint() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TicketReceiptResult blocked;
        try (ConfigurableApplicationContext first = start(false, (input, timeout, kind) -> VALID)) {
            blocked = receive(first);
            AiSuggestionJobWorker worker = manualWorker(first, (input, timeout, kind) -> {
                calls.incrementAndGet();
                throw new AiProviderFailureException(Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT, null);
            });
            assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.TEMPORARY_REJECTION);
            assertThat(jdbc.queryForObject("SELECT result_code FROM ai_suggestion_attempts WHERE job_id = ?",
                    String.class, blocked.jobId())).isEqualTo("AUTO_RETRY_BLOCKED");
            jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE id = ?",
                    blocked.jobId());
        }
        try (ConfigurableApplicationContext second = start(true, (input, timeout, kind) -> {
            calls.incrementAndGet();
            return VALID;
        })) {
            TicketReceiptResult fresh = receive(second);
            awaitSucceeded(fresh.jobId());
            assertThat(calls).hasValue(2); // Old rejection plus a different fresh Job, not an old-Job retry.
            assertThat(jdbc.queryForObject("SELECT current_attempt FROM ai_suggestion_jobs WHERE id = ?",
                    Integer.class, blocked.jobId())).isOne();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?",
                    String.class, blocked.jobId())).isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?",
                    Long.class, blocked.jobId())).isZero();
            assertOriginal(blocked);
        }
    }

    private ConfigurableApplicationContext start(boolean enabled, AiSuggestionProvider provider) {
        // Highest-priority test-only properties prevent a process environment from selecting another DB.
        return new SpringApplicationBuilder(HelpdeskApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("postgres")
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-worker-test", Map.of(
                            "server.port", "0",
                            "spring.datasource.url", POSTGRES.getJdbcUrl(),
                            "spring.datasource.username", POSTGRES.getUsername(),
                            "spring.datasource.password", POSTGRES.getPassword(),
                            "logging.level.root", "WARN",
                            "spring.main.banner-mode", "off",
                            "helpdesk.ai.worker.enabled", enabled,
                            "helpdesk.ai.worker.poll-delay-ms", "20")));
                    context.getBeanFactory().registerSingleton("controlledAiSuggestionProvider", provider);
                    context.getBeanFactory().registerSingleton("controlledAiPrivacyGuard", privacy());
                    context.getBeanFactory().registerSingleton("controlledAiOutputValidator", new AiSuggestionOutputValidator(200));
                }).run();
    }

    private AiSuggestionJobWorker manualWorker(ConfigurableApplicationContext context, AiSuggestionProvider provider) {
        var claims = context.getBean(AiSuggestionJobClaimService.class);
        var processor = new AiSuggestionJobProcessor(claims, context.getBean(JdbcAiSuggestionInputRepository.class),
                privacy(), provider, new AiSuggestionOutputValidator(200), context.getBean(AiSuggestionResultService.class),
                java.time.Clock.systemUTC());
        return new AiSuggestionJobWorker(processor, claims);
    }

    private AiInputPrivacyGuard privacy() {
        return new AiInputPrivacyGuard(List.of(new SensitiveFragment("SYNTHETIC_CONTACT", SensitiveType.EMAIL)));
    }

    private TicketReceiptResult receive(ConfigurableApplicationContext context) {
        return context.getBean(TicketReceiptApplicationService.class)
                .receive("Synthetic saved question", "Original message survives context restart.", "synthetic-author");
    }

    private java.time.OffsetDateTime time(String column, long jobId) {
        // Literal test column names only.
        return jdbc.queryForObject("SELECT " + column + " FROM ai_suggestion_jobs WHERE id = ?",
                java.time.OffsetDateTime.class, jobId);
    }

    private void awaitSucceeded(long jobId) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        String status;
        do {
            status = jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, jobId);
            if ("SUCCEEDED".equals(status)) break;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        assertThat(status).isEqualTo("SUCCEEDED");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertOriginal(TicketReceiptResult receipt) {
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ?", String.class, receipt.messageId()))
                .isEqualTo("Original message survives context restart.");
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, receipt.ticket().id()))
                .isEqualTo("OPEN");
    }
}
