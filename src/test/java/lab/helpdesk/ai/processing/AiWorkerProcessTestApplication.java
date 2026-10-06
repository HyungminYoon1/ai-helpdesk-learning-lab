package lab.helpdesk.ai.processing;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import lab.helpdesk.HelpdeskApplication;
import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.job.AiJobPolicy;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.provider.AiProviderFailureException;
import lab.helpdesk.ai.provider.AiProviderFailureException.Kind;
import lab.helpdesk.ai.provider.AiProviderFailureException.Reason;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import lab.helpdesk.ticket.repository.JdbcTicketRepository;
import lab.helpdesk.ticket.repository.TicketRepository;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only child JVM. Controlled Provider, isolated PostgreSQL, no external AI credentials. */
public final class AiWorkerProcessTestApplication {

    static final String ORIGINAL = "Original message survives a terminated Java process.";
    private static final String VALID = """
            {"decision":"SUGGEST","summary":"Synthetic saved question",
             "categories":["ACCOUNT"],"priority":"NORMAL"}
            """;

    private AiWorkerProcessTestApplication() {
    }

    public static void main(String[] args) {
        try {
            run(Mode.valueOf(args[0]));
        } catch (Throwable failure) {
            // A fixed marker, not exception text, keeps DB credentials and payloads out of test logs.
            System.out.println("AI_JVM_TEST_FAILED");
            System.exit(1);
        }
    }

    private static void run(Mode mode) throws Exception {
        for (String name : List.of("OPENAI_API_KEY", "HELPDESK_OPENAI_API_KEY", "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            require(System.getenv(name) == null);
        }
        AtomicInteger calls = new AtomicInteger();
        AiSuggestionProvider provider = (input, timeout, kind) -> {
            require(!TransactionSynchronizationManager.isActualTransactionActive());
            calls.incrementAndGet();
            return switch (mode) {
                case OBSERVE_UNKNOWN -> throw new AiProviderFailureException(Kind.OUTCOME_UNKNOWN);
                case OBSERVE_BLOCKED -> throw new AiProviderFailureException(
                        Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT, null);
                case PREPARE_RATE_WAIT -> throw new AiProviderFailureException(
                        Kind.TEMPORARY_REJECTION, Reason.RATE_LIMIT, Duration.ofSeconds(120));
                default -> VALID;
            };
        };
        boolean firstProcess = mode.isPreparation();
        boolean workerEnabled = mode != Mode.PREPARE_PENDING && mode != Mode.RESERVE_UNCONFIRMED;
        try (ConfigurableApplicationContext context = start(firstProcess, workerEnabled, provider)) {
            require(context.getBean(TicketRepository.class) instanceof JdbcTicketRepository);
            require(context.getBean(AiJobPolicy.class).maxGenerationAttempts() == (firstProcess ? 3 : 5));
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Ids ids;
            if (firstProcess) {
                TicketReceiptResult receipt = context.getBean(TicketReceiptApplicationService.class)
                        .receive("Synthetic saved question", ORIGINAL, "synthetic-author");
                ids = new Ids(receipt.ticket().id(), receipt.messageId(), receipt.jobId());
                switch (mode) {
                    case PREPARE_PENDING -> require(calls.get() == 0);
                    case RESERVE_UNCONFIRMED -> {
                        var claim = context.getBean(AiSuggestionJobClaimService.class).claimNextPending();
                        require(claim.isPresent() && claim.get().jobId() == ids.job());
                        require(calls.get() == 0);
                    }
                    case OBSERVE_UNKNOWN -> await(() -> hasResult(jdbc, ids.job(), "OUTCOME_UNKNOWN"));
                    case OBSERVE_BLOCKED -> await(() -> hasResult(jdbc, ids.job(), "AUTO_RETRY_BLOCKED"));
                    case PREPARE_RATE_WAIT -> await(() -> "PENDING".equals(status(jdbc, ids.job()))
                            && jdbc.queryForObject("SELECT next_attempt_at IS NOT NULL FROM ai_suggestion_jobs WHERE id = ?",
                                    Boolean.class, ids.job()));
                    default -> throw new IllegalStateException("AI_JVM_TEST_MODE_INVALID");
                }
                // Receipt, reservation and observed outcome are committed before the parent terminates us.
                emit("READY", ids, calls.get());
                waitForContinue(); // Parent uses this exact Process handle to terminate this JVM instead.
            } else {
                ids = new Ids(number("TICKET_ID"), number("MESSAGE_ID"), number("JOB_ID"));
                AiSuggestionJobWorker worker = context.getBean(AiSuggestionJobWorker.class);
                switch (mode) {
                    case RUN_TO_SUCCESS -> await(() -> "SUCCEEDED".equals(status(jdbc, ids.job())));
                    case CHECK_BLOCKED -> {
                        require(worker.runOnce().outcome() == Outcome.NO_JOB);
                        require("RUNNING".equals(status(jdbc, ids.job())));
                        require(hasResult(jdbc, ids.job(), "AUTO_RETRY_BLOCKED"));
                        require(calls.get() == 0);
                    }
                    case WAIT_AND_RESUME -> {
                        require(worker.runOnce().outcome() == Outcome.NO_JOB);
                        require(calls.get() == 0);
                        emit("WAITING", ids, calls.get());
                        waitForContinue(); // Parent moves only this test-owned due-time boundary.
                        await(() -> "SUCCEEDED".equals(status(jdbc, ids.job())));
                    }
                    default -> throw new IllegalStateException("AI_JVM_TEST_MODE_INVALID");
                }
                emit("DONE", ids, calls.get());
            }
        }
    }

    private static ConfigurableApplicationContext start(
            boolean firstProcess, boolean workerEnabled, AiSuggestionProvider provider) {
        return new SpringApplicationBuilder(HelpdeskApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("postgres")
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                            "isolated-jvm-worker-test", Map.ofEntries(
                                    Map.entry("server.address", "127.0.0.1"),
                                    Map.entry("server.port", "0"),
                                    Map.entry("spring.datasource.url", required("JDBC_URL")),
                                    Map.entry("spring.datasource.username", required("DB_USERNAME")),
                                    Map.entry("spring.datasource.password", required("DB_PASSWORD")),
                                    Map.entry("spring.main.banner-mode", "off"),
                                    Map.entry("logging.level.root", "OFF"),
                                    Map.entry("helpdesk.ai.worker.enabled", workerEnabled),
                                    Map.entry("helpdesk.ai.worker.poll-delay-ms", "100"),
                                    Map.entry("helpdesk.ai.job.policy-version", firstProcess ? "job-policy-v1" : "process-policy-v2"),
                                    Map.entry("helpdesk.ai.job.max-generation-attempts", firstProcess ? "3" : "5"))));
                    context.getBeanFactory().registerSingleton("controlledAiSuggestionProvider", provider);
                    context.getBeanFactory().registerSingleton("controlledAiPrivacyGuard",
                            new AiInputPrivacyGuard(List.of(new SensitiveFragment("SYNTHETIC_CONTACT", SensitiveType.EMAIL))));
                    context.getBeanFactory().registerSingleton("controlledAiOutputValidator", new AiSuggestionOutputValidator(200));
                }).run();
    }

    private static boolean hasResult(JdbcTemplate jdbc, long jobId, String result) {
        return jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM ai_suggestion_jobs j JOIN ai_suggestion_attempts a
                      ON a.job_id = j.id AND a.attempt_number = j.current_attempt
                    WHERE j.id = ? AND a.result_code = ?)
                """, Boolean.class, jobId, result);
    }

    private static String status(JdbcTemplate jdbc, long jobId) {
        return jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, jobId);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            require(System.nanoTime() < until);
            Thread.sleep(20);
        }
    }

    private static void waitForContinue() throws Exception {
        String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        require("CONTINUE".equals(line));
    }

    private static String required(String suffix) {
        String value = System.getenv("HELPDESK_JVM_TEST_" + suffix);
        require(value != null && !value.isBlank());
        return value;
    }

    private static long number(String suffix) {
        long value = Long.parseLong(required(suffix));
        require(value > 0);
        return value;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("AI_JVM_TEST_CONDITION_FAILED");
    }

    private static void emit(String phase, Ids ids, int calls) {
        System.out.printf("AI_JVM_TEST %s pid=%d ticket=%d message=%d job=%d calls=%d%n",
                phase, ProcessHandle.current().pid(), ids.ticket(), ids.message(), ids.job(), calls);
        System.out.flush();
    }

    enum Mode {
        PREPARE_PENDING, RESERVE_UNCONFIRMED, OBSERVE_UNKNOWN, OBSERVE_BLOCKED, PREPARE_RATE_WAIT,
        RUN_TO_SUCCESS, CHECK_BLOCKED, WAIT_AND_RESUME;

        boolean isPreparation() {
            return ordinal() < RUN_TO_SUCCESS.ordinal();
        }
    }

    private record Ids(long ticket, long message, long job) {
    }
}
