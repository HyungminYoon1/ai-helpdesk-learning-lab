package lab.helpdesk.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import experiment.helpdesk.privacy.PrivacyScannerProcessSupport;
import experiment.helpdesk.privacy.PrivacyScannerProcessSupport.Launch;
import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiPrivacyScanner;
import lab.helpdesk.ai.input.AiPrivacyScanner.ScanTimeoutException;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator;
import lab.helpdesk.ai.input.AiScannedInputPreparer;
import lab.helpdesk.ai.input.JdbcAiSuggestionInputRepository;
import lab.helpdesk.ai.input.ProcessAiPrivacyScanner;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.processing.AiSuggestionJobProcessor;
import lab.helpdesk.ai.processing.AiSuggestionJobWorker;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import tools.jackson.databind.json.JsonMapper;

/** Real PostgreSQL, Processor, Spring AI and loopback HTTP; scanner/model output is controlled. */
@lab.helpdesk.identity.WithIdentityFixtures
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.main.banner-mode=off",
        "helpdesk.ai.worker.enabled=false", "helpdesk.ai.provider.enabled=false"})
@ActiveProfiles("postgres")
@Testcontainers
class AiPrivacyScanPipelineIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TITLE = "합성 문의";
    private static final String BODY = "😀 연락 a@b.invalid; 새 링크로 로그인 성공. KNOWN_PRIVATE_MARKER";
    private static final String VALID_OUTPUT = """
            {"decision":"SUGGEST","summary":"새 링크로 로그인 성공 후 문의",
             "categories":["ACCOUNT"],"priority":"NORMAL"}
            """;
    private static final AiInputPrivacyGuard GUARD = new AiInputPrivacyGuard(List.of(
            new SensitiveFragment("KNOWN_PRIVATE_MARKER", SensitiveType.PASSWORD)));
    private static final List<String> TABLES = List.of("tickets", "ticket_messages", "ai_suggestion_jobs",
            "ai_suggestion_attempts", "ticket_suggestions", "ticket_suggestion_categories");

    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private AiSuggestionResultService results;
    @Autowired private JdbcAiSuggestionInputRepository inputs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @TempDir Path processTemporary;
    private final List<Path> processPidFiles = new ArrayList<>();
    private final AtomicInteger scans = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger providerInvocations = new AtomicInteger();
    private final AtomicReference<Duration> providerBudget = new AtomicReference<>();
    private final AtomicBoolean checkedRequest = new AtomicBoolean();
    private HttpServer server;
    private SpringAiOpenAiSuggestionProvider provider;
    private TicketReceiptResult receipt;

    @BeforeEach
    void isolatedDatabaseAndLiteralLoopbackOnly() throws Exception {
        Boolean isolated = jdbc.execute((ConnectionCallback<Boolean>) connection -> connection.getMetaData().getURL()
                .split("\\?", 2)[0].equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) throw new IllegalStateException("AI_PRIVACY_TEST_DATABASE_REQUIRED");
        for (String table : TABLES.reversed()) jdbc.update("DELETE FROM " + table);
        scans.set(0); requests.set(0); providerInvocations.set(0); providerBudget.set(null); checkedRequest.set(false);
        receipt = receipts.receive(TITLE, BODY, lab.helpdesk.identity.IdentityFixtures.actor("synthetic-author"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            var request = MAPPER.readTree(exchange.getRequestBody().readNBytes(32_769));
            var content = MAPPER.readTree(request.path("messages").get(1).path("content").asString());
            String transmitted = content.path("body").asString();
            checkedRequest.set(content.path("title").asString().equals(TITLE)
                    && !transmitted.contains("a@b.invalid") && !transmitted.contains("KNOWN_PRIVATE_MARKER")
                    && transmitted.contains("[EMAIL_REDACTED]") && transmitted.contains("[PASSWORD_REDACTED]")
                    && transmitted.contains("새 링크로 로그인 성공."));
            var message = MAPPER.createObjectNode().put("role", "assistant").put("content", VALID_OUTPUT).putNull("refusal");
            byte[] response = MAPPER.writeValueAsBytes(Map.of("id", "synthetic-scan-completion", "object", "chat.completion",
                    "created", 1_791_244_800, "model", "gpt-6-luna", "service_tier", "default",
                    "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", message)),
                    "usage", Map.of("prompt_tokens", 120, "completion_tokens", 40, "total_tokens", 160)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        provider = new SpringAiOpenAiSuggestionProvider(UUID.randomUUID().toString(), GUARD, 200,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", Clock.systemUTC());
    }

    @AfterEach
    void closeControlledResources() throws Exception {
        if (provider != null) provider.close();
        if (server != null) server.stop(0);
        for (Path file : processPidFiles) {
            PrivacyScannerProcessSupport.stopIfStillOwned(file);
        }
    }

    @Test
    void validatesThenMasksTheExactRequestAndStoresOneReviewProposal() {
        var processor = processor((title, body, timeout) -> validScan(), Clock.systemUTC());
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.STORED);
        assertThat(scans).hasValue(1);
        assertThat(requests).hasValue(1);
        assertThat(providerInvocations).hasValue(1);
        assertThat(checkedRequest).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId()))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?", Long.class, receipt.jobId()))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT review_status FROM ticket_suggestions WHERE job_id = ?", String.class, receipt.jobId()))
                .isEqualTo("PENDING_REVIEW");
        assertReceiptPreserved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"completed\":false,\"spans\":[]}",
            "{\"completed\":true,\"spans\":[{\"field\":\"body\",\"start\":\"3\",\"end\":5,\"type\":\"EMAIL\"}]}",
            "{\"completed\":true,\"spans\":[{\"field\":\"body\",\"start\":0,\"end\":9999,\"type\":\"EMAIL\"}]}",
            "{\"completed\":true,\"spans\":[],\"extra\":true}",
            "{\"completed\":false,\"completed\":true,\"spans\":[]}"})
    void invalidScanBlocksHttpAndPersistsTheTerminalFailureWithoutPollingRetry(String raw) {
        assertScanFailure((title, body, timeout) -> raw, "PRIVACY_SCAN_INVALID_RESULT");
    }

    @Test
    void observedScannerTimeoutBlocksHttpAndPersistsItsOwnCode() {
        assertScanFailure((title, body, timeout) -> { throw new ScanTimeoutException(); }, "PRIVACY_SCAN_TIMEOUT");
    }

    @Test
    void realLocalProcessOutputIsValidatedBeforeTheExactHttpBodyAndDatabaseResult() throws Exception {
        Launch launch = processLaunch("EMAIL_SPAN");
        var processor = processor(processScanner(launch), Clock.systemUTC());
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.STORED);
        assertProcessStopped(launch);
        assertThat(scans).hasValue(1);
        assertThat(requests).hasValue(1);
        assertThat(providerInvocations).hasValue(1);
        assertThat(checkedRequest).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId()))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?", Long.class, receipt.jobId()))
                .isOne();
        assertReceiptPreserved();
    }

    @Test
    void realHangingProcessIsReapedBeforeItsTimeoutFailureIsCommitted() throws Exception {
        Launch launch = processLaunch("HANG");
        assertScanFailure(processScanner(launch), "PRIVACY_SCAN_TIMEOUT", Duration.ofSeconds(2));
        assertProcessStopped(launch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_UTF8", "FLOOD_STDOUT"})
    void realInvalidProcessOutputStopsWithoutHttpOrSuggestionAndKeepsTheReceipt(String mode) throws Exception {
        Launch launch = processLaunch(mode);
        assertScanFailure(processScanner(launch), "PRIVACY_SCAN_INVALID_RESULT");
        assertProcessStopped(launch);
    }

    @Test
    void realNonzeroProcessExitIsNotRelabeledAsATimeoutOrValidEmptyScan() throws Exception {
        Launch launch = processLaunch("NONZERO");
        var processor = processor(processScanner(launch), Clock.systemUTC());
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.FAILED);
        assertProcessStopped(launch);
        assertThat(jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId()))
                .isEqualTo("ADAPTER_CONFIGURATION_ERROR");
        assertThat(requests).hasValue(0);
        assertThat(providerInvocations).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions", Long.class)).isZero();
        assertReceiptPreserved();
    }

    @Test
    void aScannerImplementationBugIsNotFalselyClassifiedAsAResultOrTimeout() {
        var processor = processor((title, body, timeout) -> { throw new IllegalStateException("SYNTHETIC_PRIVATE_ERROR"); }, Clock.systemUTC());
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId()))
                .isEqualTo("ADAPTER_CONFIGURATION_ERROR");
        assertThat(requests).hasValue(0);
        assertReceiptPreserved();
    }

    @Test
    void failedFailureRecordingDoesNotClaimFailedOrLeaveAHalfWrittenAttempt() {
        var processor = processor((title, body, timeout) -> { throw new ScanTimeoutException(); }, Clock.systemUTC());
        jdbc.execute("ALTER TABLE ai_suggestion_jobs ADD CONSTRAINT scan_pipeline_test_rejection "
                + "CHECK (last_failure_code IS DISTINCT FROM 'PRIVACY_SCAN_TIMEOUT')");
        try {
            assertThatThrownBy(processor::processNextPending).isInstanceOf(IllegalStateException.class)
                    .hasMessage("AI_PRIVACY_FAILURE_RECORD_FAILED").hasNoCause();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId()))
                    .isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("SELECT last_failure_code IS NULL FROM ai_suggestion_jobs WHERE id = ?", Boolean.class, receipt.jobId()))
                    .isTrue();
            assertThat(jdbc.queryForObject("SELECT result_code FROM ai_suggestion_attempts WHERE job_id = ?", String.class, receipt.jobId()))
                    .isEqualTo("UNCONFIRMED");
            assertThat(jdbc.queryForObject("SELECT reserved_generation_count FROM ai_suggestion_jobs WHERE id = ?", Integer.class, receipt.jobId()))
                    .isOne();
            assertThat(requests).hasValue(0);
            assertReceiptPreserved();
        } finally {
            jdbc.execute("ALTER TABLE ai_suggestion_jobs DROP CONSTRAINT scan_pipeline_test_rejection");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aNewOwnerDuringScanningPreventsBothLateFailureAndLateSending(boolean invalidResult) {
        var afterNewOwner = new AtomicReference<List<String>>();
        var processor = processor((title, body, timeout) -> {
            jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 hour' WHERE id = ?", receipt.jobId());
            assertThat(claims.claimRecoveryAfterResultCheck(receipt.jobId(), 1).orElseThrow().attemptNumber()).isEqualTo(2);
            afterNewOwner.set(snapshot());
            return invalidResult ? "{" : validScan();
        }, Clock.systemUTC());

        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.NOT_CURRENT);
        assertThat(snapshot()).isEqualTo(afterNewOwner.get());
        assertThat(requests).hasValue(0);
        assertReceiptPreserved();
    }

    @Test
    void expirationOfTheLeaseDuringScanningPreventsSendingWithoutAReplacementWorker() {
        var processor = processor((title, body, timeout) -> {
            jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 hour' WHERE id = ?", receipt.jobId());
            return validScan();
        }, Clock.systemUTC());
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.NOT_CURRENT);
        assertThat(requests).hasValue(0);
    }

    @Test
    void expiredWholeJobTimeAfterScanningPreventsSending() {
        var clock = new MutableClock();
        var processor = processor((title, body, timeout) -> { clock.advance(Duration.ofHours(1)); return validScan(); }, clock);
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId()))
                .isEqualTo("JOB_PROCESSING_DEADLINE_EXCEEDED");
        assertThat(requests).hasValue(0);
        assertReceiptPreserved();
    }

    @Test
    void providerBudgetIsRecalculatedAfterScanningRatherThanReusingTheOldBudget() {
        var clock = new MutableClock();
        var processor = processor((title, body, timeout) -> {
            clock.now = jdbc.queryForObject("SELECT processing_deadline_at FROM ai_suggestion_jobs WHERE id = ?",
                    OffsetDateTime.class, receipt.jobId()).toInstant().minusSeconds(15);
            return validScan();
        }, clock);
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.STORED);
        assertThat(providerBudget).hasValue(Duration.ofSeconds(15));
        assertThat(requests).hasValue(1);
    }

    private void assertScanFailure(AiPrivacyScanner scanner, String code) {
        assertScanFailure(scanner, code, Duration.ofSeconds(10));
    }

    private void assertScanFailure(AiPrivacyScanner scanner, String code, Duration scanLimit) {
        var processor = processor(scanner, Clock.systemUTC(), scanLimit);
        assertThat(processor.processNextPending().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(jdbc.queryForObject("SELECT status = 'FAILED' AND last_failure_code = ? FROM ai_suggestion_jobs WHERE id = ?",
                Boolean.class, code, receipt.jobId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT result_code FROM ai_suggestion_attempts WHERE job_id = ?", String.class, receipt.jobId()))
                .isEqualTo("AUTO_RETRY_BLOCKED");
        assertThat(jdbc.queryForObject("SELECT reserved_generation_count FROM ai_suggestion_jobs WHERE id = ?", Integer.class, receipt.jobId()))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions", Long.class)).isZero();
        assertThat(requests).hasValue(0);
        assertThat(providerInvocations).hasValue(0);
        assertReceiptPreserved();
        var before = snapshot();
        var worker = new AiSuggestionJobWorker(processor, claims);
        for (int i = 0; i < 3; i++) assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(scans).hasValue(1);
        assertThat(requests).hasValue(0);
    }

    private AiSuggestionJobProcessor processor(AiPrivacyScanner scanner, Clock clock) {
        return processor(scanner, clock, Duration.ofSeconds(10));
    }

    private AiSuggestionJobProcessor processor(AiPrivacyScanner scanner, Clock clock, Duration scanLimit) {
        AiPrivacyScanner observed = (title, body, timeout) -> {
            scans.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(title.equals(TITLE) && body.equals(BODY)).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts WHERE job_id = ?", Long.class, receipt.jobId()))
                    .isOne();
            // A second transaction can acquire the row: no claim lock remains during scanning.
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    jdbc.queryForObject("SELECT id FROM ai_suggestion_jobs WHERE id = ? FOR UPDATE NOWAIT", Long.class, receipt.jobId()));
            return scanner.scan(title, body, timeout);
        };
        return new AiSuggestionJobProcessor(claims, inputs, new AiScannedInputPreparer(observed,
                new AiPrivacyScanResultValidator(4_096, 16), GUARD, scanLimit),
                (prepared, timeout, kind) -> {
                    providerInvocations.incrementAndGet();
                    providerBudget.set(timeout);
                    return provider.generate(prepared, timeout, kind);
                }, new AiSuggestionOutputValidator(200), results, clock);
    }

    private String validScan() {
        int index = BODY.indexOf("a@b.invalid");
        int start = BODY.codePointCount(0, index);
        return MAPPER.writeValueAsString(Map.of("completed", true, "spans", List.of(
                Map.of("field", "body", "start", start, "end", start + 11, "type", "EMAIL"))));
    }

    private Launch processLaunch(String mode) throws Exception {
        Launch launch = PrivacyScannerProcessSupport.prepare(processTemporary, mode);
        processPidFiles.add(launch.pidFile());
        return launch;
    }

    private ProcessAiPrivacyScanner processScanner(Launch launch) {
        return new ProcessAiPrivacyScanner(launch.command(), 8_192, 4_096, 4_096, Duration.ofSeconds(3));
    }

    private void assertProcessStopped(Launch launch) throws Exception {
        assertThat(Files.isRegularFile(launch.pidFile())).isTrue();
        assertThat(PrivacyScannerProcessSupport.stillOwned(launch.pidFile()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    private void assertReceiptPreserved() {
        assertThat(jdbc.queryForObject("SELECT title = ? AND status = 'OPEN' FROM tickets WHERE id = ?", Boolean.class,
                TITLE, receipt.ticket().id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT body = ? AND author_username = ? FROM ticket_messages WHERE id = ?", Boolean.class,
                BODY, "synthetic-author", receipt.messageId())).isTrue();
    }

    private List<String> snapshot() {
        return TABLES.stream().map(table -> jdbc.queryForObject(
                "SELECT md5(COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text)::text, '[]')) FROM " + table + " r",
                String.class)).toList();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();
        void advance(Duration time) { now = now.plus(time); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
