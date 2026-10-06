package lab.helpdesk.ai.provider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lab.helpdesk.HelpdeskApplication;
import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.processing.AiSuggestionWorkerScheduler;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test-only assembly; never enables a paid Provider in an ordinary Application. */
final class Week7WorkerBrowserExperiment {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String TITLE = "로그인 링크 만료 이유 문의";
    private static final String BODY = "제 계정에서 로그인 링크가 만료됐습니다. 새 링크로 로그인에는 성공했습니다. "
            + "급한 문의는 아니며 만료 이유를 알고 싶습니다. 이메일 <합성_이메일>.";
    private static final String CONTROLLED = """
            {"decision":"SUGGEST","summary":"로그인 링크가 만료됐으나 새 링크로 로그인에 성공했고, 만료 이유를 문의합니다.",
             "categories":["ACCOUNT"],"priority":"NORMAL"}
            """;
    private static final List<String> TABLES = List.of("tickets", "ticket_messages", "ai_suggestion_jobs",
            "ai_suggestion_attempts", "ticket_suggestions", "ticket_suggestion_categories");

    private Week7WorkerBrowserExperiment() {
    }

    static void run(boolean live) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evidence", live ? "LIVE_WORKER_BROWSER_POSTGRES" : "CONTROLLED_WORKER_BROWSER_POSTGRES");
        report.put("protocol", live ? "chat-completions" : "test-double");
        report.put("manualContentReview", "NOT_SCORED");
        report.put("browserE2e", false);
        report.put("jobStatus", "EXPERIMENT_FAILED");
        report.put("experimentStage", "SETUP");
        var invocations = new AtomicInteger();
        SpringAiOpenAiSuggestionProvider real = null;
        Path sessions = null;
        try {
            if (live) requireDailyApproval();
            var privacy = new AiInputPrivacyGuard(List.of(new AiInputPrivacyGuard.SensitiveFragment(
                    "<합성_이메일>", AiInputPrivacyGuard.SensitiveType.EMAIL)));
            if (live) {
                String key = System.getenv("HELPDESK_OPENAI_API_KEY");
                check(key != null && !key.isBlank(), "HELPDESK_SCOPED_KEY_REQUIRED");
                real = new SpringAiOpenAiSuggestionProvider(key, privacy, 200);
            }
            final var selected = real;
            var expected = privacy.prepare(TITLE, BODY);
            AiSuggestionProvider provider = (input, timeout, kind) -> {
                // Even accidental extra intake cannot spend another request in this fixture.
                check(invocations.incrementAndGet() == 1 && expected.equals(input), "EXPERIMENT_INPUT_OR_CALL_LIMIT");
                check(!TransactionSynchronizationManager.isActualTransactionActive(), "PROVIDER_INSIDE_TRANSACTION");
                return selected == null ? CONTROLLED : selected.generate(input, timeout, kind);
            };
            Path artifacts = Path.of("output", "playwright").toAbsolutePath().normalize();
            Files.createDirectories(artifacts);
            Path runDirectory = Files.createTempDirectory(artifacts, "week7-worker-");
            sessions = Files.createDirectory(runDirectory.resolve(".sessions"));
            String suffix = UUID.randomUUID().toString();
            Map<String, String> credentials = Map.of(
                    "HELPDESK_BROWSER_USER_NAME", "user-" + suffix,
                    "HELPDESK_BROWSER_USER_PASSWORD", UUID.randomUUID().toString(),
                    "HELPDESK_BROWSER_AGENT_NAME", "agent-" + suffix,
                    "HELPDESK_BROWSER_AGENT_PASSWORD", UUID.randomUUID().toString());
            try (var postgres = new PostgreSQLContainer("postgres:17.6-alpine")) {
                report.put("experimentStage", "POSTGRES_START");
                postgres.start();
                report.put("experimentStage", "APPLICATION_START");
                try (var context = start(postgres, provider, privacy, credentials)) {
                    var jdbc = context.getBean(JdbcTemplate.class);
                    report.put("workerScheduled", context.getBean(AiSuggestionWorkerScheduler.class) != null);
                    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                    report.put("experimentStage", "BROWSER_RECEIPT");
                    JsonNode receipt = browser("Receipt", port, sessions, 1, credentials);
                    long ticketId = receipt.path("ticketId").asLong();
                    check(ticketId > 0, "BROWSER_RECEIPT_MISSING");
                    for (String field : List.of("receiptStatus", "missingCsrfStatus", "anonymousQueryStatus",
                            "userQueryStatus", "sessionCookieObserved", "csrfHeaderObserved")) {
                        report.put(field, field.endsWith("Observed")
                                ? receipt.path(field).asBoolean() : receipt.path(field).asInt());
                    }
                    report.put("ticketId", ticketId);
                    Long jobId = jdbc.queryForObject("""
                            SELECT j.id FROM ai_suggestion_jobs j
                            JOIN ticket_messages m ON m.id = j.input_message_id
                            WHERE m.ticket_id = ?
                            """, Long.class, ticketId);
                    check(jobId != null, "COMMITTED_JOB_MISSING");
                    report.put("experimentStage", "WORKER_RESULT");
                    String status = awaitTerminal(jdbc, jobId);
                    report.put("jobStatus", status);
                    report.put("originalPreserved", BODY.equals(jdbc.queryForObject(
                            "SELECT body FROM ticket_messages WHERE ticket_id = ?", String.class, ticketId)));
                    report.put("authorFromAuthentication", credentials.get("HELPDESK_BROWSER_USER_NAME").equals(
                            jdbc.queryForObject("SELECT author_username FROM ticket_messages WHERE ticket_id = ?",
                                    String.class, ticketId)));
                    report.put("reservedGenerationCount", jdbc.queryForObject(
                            "SELECT reserved_generation_count FROM ai_suggestion_jobs WHERE id = ?", Integer.class, jobId));
                    report.put("suggestionCount", jdbc.queryForObject(
                            "SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?", Long.class, jobId));
                    report.put("ticketStatus", jdbc.queryForObject(
                            "SELECT status FROM tickets WHERE id = ?", String.class, ticketId));
                    check("SUCCEEDED".equals(status), "LIVE_JOB_NOT_SUCCEEDED");
                    var stored = context.getBean(AiSuggestionResultService.class).findStoredResult(jobId).orElseThrow();
                    var suggestion = stored.suggestion().orElseThrow();
                    report.put("categoryCount", suggestion.categories().size());
                    report.put("reviewStatus", suggestion.reviewStatus().name());
                    List<Object> signature = List.of(ticketId, jobId, suggestion.id(), suggestion.summary(),
                            suggestion.categories().stream().map(Enum::name).sorted().toList(),
                            suggestion.priority().name(), suggestion.reviewStatus().name());
                    var before = databaseFingerprint(jdbc);
                    report.put("experimentStage", "BROWSER_AGENT_READ");
                    JsonNode read = browser("Read", port, sessions, ticketId, credentials);
                    report.put("agentQueryStatus", read.path("agentQueryStatus").asInt());
                    report.put("databaseAndUiAgree", digest(MAPPER.writeValueAsString(signature))
                            .equals(read.path("uiResponseDigest").asString()));
                    report.put("readOnlyQueryPreserved", before.equals(databaseFingerprint(jdbc)));
                    check(Boolean.TRUE.equals(report.get("databaseAndUiAgree"))
                            && Boolean.TRUE.equals(report.get("readOnlyQueryPreserved")), "BROWSER_DATABASE_MISMATCH");
                    Path screenshot = runDirectory.resolve("agent-suggestion.png");
                    // The launcher wrote only Browser session artifacts; move the one safe screenshot out.
                    Files.move(sessions.resolve("agent-suggestion.png"), screenshot);
                    report.put("screenshot", Path.of("").toAbsolutePath().relativize(screenshot).toString().replace('\\', '/'));
                    report.put("browserE2e", true);
                    report.put("experimentStage", "COMPLETED");
                }
            }
        } catch (Exception exception) {
            report.put("failureType", exception.getClass().getSimpleName());
            if (exception.getMessage() != null && exception.getMessage().startsWith("BROWSER_DIAGNOSTIC:")) {
                report.put("browserDiagnostic", exception.getMessage().substring("BROWSER_DIAGNOSTIC:".length()));
            }
            // Never forward Browser diagnostics, response content, properties or Provider causes.
            throw new IllegalStateException("WORKER_BROWSER_EXPERIMENT_FAILED");
        } finally {
            report.put("providerInvocations", invocations.get());
            var call = real == null ? new AiProviderCallEvidence(0, null, null) : real.lastCallEvidence();
            report.put("httpAttempts", call.httpAttempts());
            report.put("httpStatus", call.httpStatus());
            report.put("usage", call.usage());
            report.put("expectedTariffConfirmed", call.expectedTariffConfirmed());
            if (real != null) real.close();
            if (sessions != null) removeDisposableSessions(sessions);
            System.out.println("HELPDESK_WORKER_BROWSER_EVIDENCE " + MAPPER.writeValueAsString(report));
        }
    }

    private static ConfigurableApplicationContext start(PostgreSQLContainer postgres, AiSuggestionProvider provider,
            AiInputPrivacyGuard privacy, Map<String, String> credentials) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("logging.level.root", "WARN");
        properties.put("spring.main.banner-mode", "off");
        properties.put("helpdesk.ai.worker.enabled", "true");
        properties.put("helpdesk.ai.worker.poll-delay-ms", "200");
        properties.put("helpdesk.ai.job.policy-version", "worker-browser-one-call-v1");
        properties.put("helpdesk.ai.job.max-generation-attempts", "1");
        properties.put("helpdesk.ai.job.max-output-repair-attempts", "0");
        // Required local-browser setting; this experiment uses same-Origin requests only.
        properties.put("helpdesk.local.cors.allowed-origin", "http://127.0.0.1:1");
        properties.put("helpdesk.local.user.username", credentials.get("HELPDESK_BROWSER_USER_NAME"));
        properties.put("helpdesk.local.user.password", credentials.get("HELPDESK_BROWSER_USER_PASSWORD"));
        properties.put("helpdesk.local.agent.username", credentials.get("HELPDESK_BROWSER_AGENT_NAME"));
        properties.put("helpdesk.local.agent.password", credentials.get("HELPDESK_BROWSER_AGENT_PASSWORD"));
        return new SpringApplicationBuilder(HelpdeskApplication.class).profiles("postgres", "local-browser")
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("worker-browser-fixture", properties));
                    context.getBeanFactory().registerSingleton("experimentProvider", provider);
                    context.getBeanFactory().registerSingleton("experimentPrivacy", privacy);
                    context.getBeanFactory().registerSingleton("experimentValidator", new AiSuggestionOutputValidator(200));
                }).run();
    }

    private static JsonNode browser(String mode, int port, Path sessions, long ticketId,
            Map<String, String> credentials) throws Exception {
        String shell = "C:\\Program Files\\WindowsApps\\Microsoft.PowerShell_7.6.6.0_x64__8wekyb3d8bbwe\\pwsh.exe";
        Path script = Path.of("scripts", "Verify-Week7WorkerBrowser.ps1").toAbsolutePath();
        Path safeOutput = sessions.resolve(mode + ".out.log");
        var builder = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-File", script.toString(),
                "-Mode", mode, "-Port", String.valueOf(port), "-RunDirectory", sessions.toString(),
                "-TicketId", String.valueOf(ticketId)).redirectErrorStream(true).redirectOutput(safeOutput.toFile());
        var host = Map.copyOf(builder.environment());
        builder.environment().clear();
        for (String name : List.of("PATH", "PATHEXT", "COMSPEC", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "USERPROFILE",
                "HOMEDRIVE", "HOMEPATH", "APPDATA", "LOCALAPPDATA")) {
            if (host.containsKey(name)) builder.environment().put(name, host.get(name));
        }
        builder.environment().putAll(credentials);
        builder.environment().put("HELPDESK_BROWSER_TITLE", TITLE);
        builder.environment().put("HELPDESK_BROWSER_BODY", BODY);
        Process process = builder.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("BROWSER_LAUNCHER_TIMED_OUT");
        }
        var output = Files.readAllLines(safeOutput, StandardCharsets.UTF_8);
        for (String line : output) {
            if (line.matches("HELPDESK_BROWSER_STEP_FAILED (OPEN|ANONYMOUS_READ|USER_LOGIN|RECEIPT_POST|AGENT_LOGIN|AGENT_READ) "
                    + "(UNKNOWN|SyntaxError|TimeoutError|TypeError|ReferenceError|LOGIN_FAILED|SECURITY_CONTROL_FAILED|RECEIPT_FAILED)")) {
                throw new IllegalStateException("BROWSER_DIAGNOSTIC:" + line.substring("HELPDESK_BROWSER_STEP_FAILED ".length()));
            }
        }
        var lines = output.stream()
                .filter(line -> line.startsWith("HELPDESK_BROWSER_EVIDENCE ")).toList();
        check(process.exitValue() == 0 && lines.size() == 1, "BROWSER_STEP_FAILED");
        return MAPPER.readTree(lines.getFirst().substring("HELPDESK_BROWSER_EVIDENCE ".length()));
    }

    private static String awaitTerminal(JdbcTemplate jdbc, long jobId) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status;
        do {
            status = jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, jobId);
            if (List.of("SUCCEEDED", "FAILED", "ABSTAINED").contains(status)) return status;
            Thread.sleep(200);
        } while (System.nanoTime() < until);
        return status;
    }

    private static Map<String, String> databaseFingerprint(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String, String>();
        for (String table : TABLES) {
            // Six literal test table names, never user input. No raw row is printed.
            result.put(table, jdbc.queryForObject("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text, '[]')) FROM "
                    + table + " t", String.class));
        }
        return result;
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void requireDailyApproval() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        Path file = Path.of("local", "ai-experiments", today + "-budget.json");
        check("true".equals(System.getenv("HELPDESK_AI_LIVE_CONFIRMED"))
                && "true".equals(System.getenv("HELPDESK_AI_COST_LIMIT_WAIVER_CONFIRMED"))
                && today.equals(System.getenv("HELPDESK_AI_LIVE_DAY")), "DAILY_LIVE_APPROVAL_REQUIRED");
        JsonNode ledger = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        check(Files.exists(Path.of(file + ".lock")) && today.equals(ledger.path("day").asString())
                && !ledger.path("blocked").asBoolean()
                && ledger.path("pendingReservationUsd").asDouble() >= 0.009004
                && today.equals(ledger.path("costLimitWaiver").path("day").asString())
                && "USER_APPROVED_NO_COST_LIMIT".equals(ledger.path("costLimitWaiver").path("reason").asString()),
                "DAILY_RESERVATION_REQUIRED");
    }

    private static void removeDisposableSessions(Path sessions) {
        try {
            Path resolved = sessions.toAbsolutePath().normalize();
            Path allowed = Path.of("output", "playwright").toAbsolutePath().normalize();
            check(resolved.startsWith(allowed) && ".sessions".equals(resolved.getFileName().toString())
                    && resolved.getParent().getFileName().toString().startsWith("week7-worker-"), "UNSAFE_SESSION_CLEANUP");
            try (var paths = Files.walk(resolved)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("DISPOSABLE_BROWSER_SESSION_CLEANUP_FAILED");
        }
    }

    private static void check(boolean condition, String code) {
        if (!condition) throw new IllegalStateException(code);
    }
}
