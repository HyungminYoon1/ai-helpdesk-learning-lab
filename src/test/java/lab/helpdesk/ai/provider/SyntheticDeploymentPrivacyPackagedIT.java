package lab.helpdesk.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;

import com.sun.net.httpserver.HttpServer;
import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiScannedInputPreparer;
import lab.helpdesk.ai.input.JdbcAiSuggestionInputRepository;
import lab.helpdesk.ai.input.synthetic.SyntheticDeploymentPrivacyScanner;
import lab.helpdesk.ai.input.synthetic.SyntheticPrivacyScannerProgram;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in after package: real child JAR + Spring privacy Beans + PostgreSQL, loopback AI only. */
@lab.helpdesk.identity.WithIdentityFixtures
@SpringBootTest(properties = {"logging.level.root=WARN", "spring.main.banner-mode=off",
        "helpdesk.ai.worker.enabled=false", "helpdesk.ai.provider.enabled=false",
        "helpdesk.ai.synthetic-scan.enabled=true", "helpdesk.ai.synthetic-scan.timeout-ms=20000",
        "helpdesk.ai.synthetic-scan.cleanup-timeout-ms=3000",
        "helpdesk.deployment.user.username=synthetic-user", "helpdesk.deployment.user.password=test-only-user-password",
        "helpdesk.deployment.agent.username=synthetic-agent", "helpdesk.deployment.agent.password=test-only-agent-password"})
@ActiveProfiles({"postgres", "deployment", "synthetic-ai"})
@Testcontainers
class SyntheticDeploymentPrivacyPackagedIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> TABLES = List.of("tickets", "ticket_messages", "ai_suggestion_jobs",
            "ai_suggestion_attempts", "ticket_suggestions", "ticket_suggestion_categories");
    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private AiSuggestionJobClaimService claims;
    @Autowired private AiSuggestionResultService results;
    @Autowired private JdbcAiSuggestionInputRepository inputs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiScannedInputPreparer privacy;
    @Autowired private AiInputPrivacyGuard guard;
    private final AtomicInteger httpRequests = new AtomicInteger();
    private final AtomicInteger providerInvocations = new AtomicInteger();
    private final AtomicBoolean exactMaskedRequest = new AtomicBoolean();
    private HttpServer server;
    private SpringAiOpenAiSuggestionProvider provider;
    private AiSuggestionJobWorker worker;

    @DynamicPropertySource
    static void packagedJar(DynamicPropertyRegistry registry) {
        String value = System.getProperty("helpdesk.synthetic.test-jar");
        if (value == null || !Path.of(value).isAbsolute()) throw new IllegalStateException("PACKAGED_TEST_JAR_REQUIRED");
        registry.add("helpdesk.ai.synthetic-scan.scanner-jar", () -> value);
    }

    @BeforeEach
    void isolatedDatabaseAndCurrentPackagedProgram() throws Exception {
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) connection -> connection.getMetaData().getURL()
                .split("\\?", 2)[0].equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]))).isTrue();
        for (String table : TABLES.reversed()) jdbc.update("DELETE FROM " + table);
        // Prevent a stale packaged fixture from being mistaken for the current source.
        try (var jar = new JarFile(System.getProperty("helpdesk.synthetic.test-jar"))) {
            for (Class<?> type : List.of(SyntheticPrivacyScannerProgram.class, SyntheticDeploymentPrivacyScanner.class)) {
                String entry = type.getName().replace('.', '/') + ".class";
                try (var packaged = jar.getInputStream(jar.getJarEntry("BOOT-INF/classes/" + entry));
                        var current = type.getResourceAsStream("/" + entry)) {
                    assertThat(packaged.readAllBytes()).isEqualTo(current.readAllBytes());
                }
            }
        }
        httpRequests.set(0);
        providerInvocations.set(0);
        exactMaskedRequest.set(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            httpRequests.incrementAndGet();
            var raw = MAPPER.readTree(exchange.getRequestBody().readNBytes(32_769));
            var input = MAPPER.readTree(raw.path("messages").get(1).path("content").asString());
            exactMaskedRequest.set(input.path("title").asString().equals(SyntheticDeploymentPrivacyScanner.TITLE)
                    && input.path("body").asString().equals(SyntheticDeploymentPrivacyScanner.BODY
                    .replace(SyntheticDeploymentPrivacyScanner.EMAIL, "[EMAIL_REDACTED]")));
            var message = MAPPER.createObjectNode().put("role", "assistant").put("content",
                    "{\"decision\":\"SUGGEST\",\"summary\":\"새 링크로 로그인 성공 후 안내 요청\","
                    + "\"categories\":[\"ACCOUNT\"],\"priority\":\"NORMAL\"}").putNull("refusal");
            byte[] response = MAPPER.writeValueAsBytes(Map.of("id", "synthetic-deployment-completion", "object", "chat.completion",
                    "created", 1_791_244_800, "model", "gpt-6-luna", "service_tier", "default",
                    "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", message)),
                    "usage", Map.of("prompt_tokens", 120, "completion_tokens", 40, "total_tokens", 160)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        provider = new SpringAiOpenAiSuggestionProvider(UUID.randomUUID().toString(), guard, 200,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", Clock.systemUTC());
        var processor = new AiSuggestionJobProcessor(claims, inputs, privacy, (prepared, timeout, kind) -> {
            providerInvocations.incrementAndGet();
            return provider.generate(prepared, timeout, kind);
        },
                new AiSuggestionOutputValidator(200), results, Clock.systemUTC());
        worker = new AiSuggestionJobWorker(processor, claims); // Explicit tick, not an automatic Scheduler test.
    }

    @AfterEach
    void closeControlledResources() {
        if (provider != null) provider.close();
        if (server != null) server.stop(0);
    }

    @Test
    void packagedChildMasksTheAllowedCaseBeforeHttpAndStoresOneProposal() {
        var receipt = receipts.receive(SyntheticDeploymentPrivacyScanner.TITLE, SyntheticDeploymentPrivacyScanner.BODY, lab.helpdesk.identity.IdentityFixtures.actor("synthetic-author"));
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.STORED);
        assertThat(providerInvocations).hasValue(1);
        assertThat(httpRequests).hasValue(1);
        assertThat(exactMaskedRequest).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, receipt.jobId()))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?", Long.class, receipt.jobId())).isOne();
        assertReceiptAndReservation(receipt, SyntheticDeploymentPrivacyScanner.BODY);
    }

    @Test
    void outOfScopeInputFailsWithoutHttpWhileCommittedReceiptAndReservationRemain() {
        String body = SyntheticDeploymentPrivacyScanner.BODY + " 허용 목록 밖의 추가 문장";
        var receipt = receipts.receive(SyntheticDeploymentPrivacyScanner.TITLE, body, lab.helpdesk.identity.IdentityFixtures.actor("synthetic-author"));
        assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.FAILED);
        assertThat(providerInvocations).hasValue(0);
        assertThat(httpRequests).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT status = 'FAILED' AND last_failure_code = 'ADAPTER_CONFIGURATION_ERROR' "
                + "FROM ai_suggestion_jobs WHERE id = ?", Boolean.class, receipt.jobId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions", Long.class)).isZero();
        assertReceiptAndReservation(receipt, body);
        for (int i = 0; i < 3; i++) assertThat(worker.runOnce().outcome()).isEqualTo(Outcome.NO_JOB);
        assertThat(providerInvocations).hasValue(0);
        assertThat(httpRequests).hasValue(0);
        assertReceiptAndReservation(receipt, body);
    }

    private void assertReceiptAndReservation(TicketReceiptResult receipt, String body) {
        assertThat(jdbc.queryForObject("SELECT title = ? AND status = 'OPEN' FROM tickets WHERE id = ?", Boolean.class,
                SyntheticDeploymentPrivacyScanner.TITLE, receipt.ticket().id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT body = ? AND author_username = 'synthetic-author' FROM ticket_messages WHERE id = ?",
                Boolean.class, body, receipt.messageId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_attempt = 1 AND reserved_generation_count = 1 FROM ai_suggestion_jobs WHERE id = ?",
                Boolean.class, receipt.jobId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts WHERE job_id = ?", Long.class, receipt.jobId())).isOne();
    }
}
