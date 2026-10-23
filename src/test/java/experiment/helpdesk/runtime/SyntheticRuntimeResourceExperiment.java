package experiment.helpdesk.runtime;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import lab.helpdesk.HelpdeskApplication;
import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiPrivacyScanner;
import lab.helpdesk.ai.input.AiScannedInputPreparer;
import lab.helpdesk.ai.input.ProcessAiPrivacyScanner;
import lab.helpdesk.ai.input.synthetic.SyntheticDeploymentPrivacyScanner;
import lab.helpdesk.ai.input.synthetic.SyntheticPrivacyScannerProgram;
import lab.helpdesk.ai.processing.AiSuggestionJobWorker;
import lab.helpdesk.ai.processing.AiSuggestionWorkerScheduler;
import lab.helpdesk.ai.provider.SpringAiOpenAiSuggestionProvider;
import lab.helpdesk.ai.provider.SyntheticRuntimeProviderBridge;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Mounted test launcher: packaged App + automatic Worker + child JVM + isolated PostgreSQL. */
public final class SyntheticRuntimeResourceExperiment {
    public static final String PREFIX = "WEEK8_SYNTHETIC_RUNTIME_RESULT:";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Path CGROUP = Path.of("/sys/fs/cgroup");
    private static final String AUTHOR = "synthetic-runtime-fixture";
    private static final String TITLE = SyntheticDeploymentPrivacyScanner.TITLE;
    private static final String BODY = SyntheticDeploymentPrivacyScanner.BODY;
    private static final String OUTSIDE = BODY + " 허용 목록 밖의 추가 문장";
    private static final Path CHILD_WITNESS = Path.of("/tmp/helpdesk-synthetic-scan-witness");

    enum ScanMode { NORMAL, TIMEOUT, NON_ZERO_EXIT }

    private SyntheticRuntimeResourceExperiment() { }

    public static void main(String[] args) {
        var report = new LinkedHashMap<String, Object>();
        report.put("evidence", "LINUX_PACKAGED_APP_AUTOMATIC_WORKER_SYNTHETIC_SCAN");
        report.put("realAiCalled", false);
        report.put("providerMode", "SPRING_AI_LITERAL_LOOPBACK_FIXTURE");
        report.put("mainProviderConfigurationUsed", false);
        report.put("browserE2e", false);
        String stage = "STARTUP";
        boolean passed = false;
        long started = System.nanoTime();
        try {
            var application = new SpringApplication(HelpdeskApplication.class, ControlledAiConfiguration.class,
                    lab.helpdesk.identity.IdentityFixtures.Configuration.class);
            application.setBannerMode(Banner.Mode.OFF);
            application.setLogStartupInfo(false);
            try (var context = application.run()) {
                var environment = context.getEnvironment();
                var scanMode = scanMode(environment);
                report.put("scanMode", scanMode.name());
                report.put("testScannerOverride", scanMode != ScanMode.NORMAL);
                require(List.of(environment.getActiveProfiles()).containsAll(List.of("postgres", "deployment", "synthetic-ai")));
                require("jdbc:postgresql://db:5432/helpdesk_local".equals(environment.getProperty("spring.datasource.url")));
                require("false".equals(environment.getProperty("helpdesk.ai.provider.enabled")));
                context.getBean(AiScannedInputPreparer.class);
                context.getBean(AiSuggestionJobWorker.class);
                context.getBean(AiSuggestionWorkerScheduler.class);
                requirePackaged(AiScannedInputPreparer.class);
                requirePackaged(AiSuggestionJobWorker.class);
                requirePackaged(SyntheticDeploymentPrivacyScanner.class);
                requirePackaged(ProcessAiPrivacyScanner.class);
                requirePackaged(SyntheticPrivacyScannerProgram.class);
                report.put("mainClassesFromImageJar", true);
                report.put("automaticSchedulerRegistered", true);
                report.put("appReadySeconds", seconds(started));
                report.put("beforeJobs", resources());

                stage = "HEALTH_AND_DATABASE";
                var jdbc = context.getBean(JdbcTemplate.class);
                require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT count(*) = 8 AND bool_and(success) "
                        + "AND max(version::integer) = 9 FROM flyway_schema_history", Boolean.class)));
                require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_database() = 'helpdesk_local' "
                        + "AND current_user = 'helpdesk_app'", Boolean.class)));
                int port = environment.getRequiredProperty("local.server.port", Integer.class);
                try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                    var health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                            .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                    require(health.statusCode() == 200 && "UP".equals(MAPPER.readTree(health.body()).path("status").asString()));
                    report.put("healthStatus", 200);
                }

                stage = "AUTOMATIC_JOBS";
                var receipts = context.getBean(TicketReceiptApplicationService.class);
                var fixture = context.getBean(LoopbackAi.class);
                if (scanMode == ScanMode.NORMAL) {
                    var accepted = new ArrayList<TicketReceiptResult>();
                    var snapshots = new ArrayList<Map<String, Object>>();
                    for (int index = 0; index < 3; index++) {
                        long jobStarted = System.nanoTime();
                        var receipt = receipts.receive(TITLE, BODY, lab.helpdesk.identity.IdentityFixtures.actor(AUTHOR));
                        awaitTerminal(jdbc, receipt.jobId(), "SUCCEEDED");
                        assertReceipt(jdbc, receipt, BODY);
                        require(jdbc.queryForObject("SELECT count(*) FROM ticket_suggestions WHERE job_id = ?", Integer.class,
                                receipt.jobId()) == 1);
                        requireNoScannerResources();
                        accepted.add(receipt);
                        var snapshot = resources();
                        snapshot.put("receiptToObservedTerminalSeconds", seconds(jobStarted));
                        snapshots.add(snapshot);
                    }
                    var rejected = receipts.receive(TITLE, OUTSIDE, lab.helpdesk.identity.IdentityFixtures.actor(AUTHOR));
                    awaitTerminal(jdbc, rejected.jobId(), "FAILED");
                    assertReceipt(jdbc, rejected, OUTSIDE);
                    require("ADAPTER_CONFIGURATION_ERROR".equals(jdbc.queryForObject(
                            "SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?", String.class, rejected.jobId())));
                    require(jdbc.queryForObject("SELECT count(*) FROM ticket_suggestions WHERE job_id = ?", Integer.class, rejected.jobId()) == 0);
                    // Several scheduled idle ticks must not turn the terminal failure into another call.
                    Thread.sleep(1_500);
                    requireNoScannerResources();
                    require(fixture.requests.get() == 3 && fixture.validMaskedRequests.get() == 3);
                    require(jdbc.queryForObject("SELECT count(*) FROM tickets", Integer.class) == 4);
                    require(jdbc.queryForObject("SELECT count(*) FROM ticket_messages", Integer.class) == 4);
                    require(jdbc.queryForObject("SELECT count(*) FROM ai_suggestion_attempts", Integer.class) == 4);
                    require(jdbc.queryForObject("SELECT count(*) FROM ticket_suggestions", Integer.class) == 3);
                    report.put("acceptedJobs", accepted.size());
                    report.put("rejectedJobs", 1);
                    report.put("providerHttpRequests", fixture.requests.get());
                    report.put("exactMaskedRequests", fixture.validMaskedRequests.get());
                    report.put("originalsAuthorsAndReservationsPreserved", true);
                    report.put("liveChildProcessesAfterJobs", 0);
                    report.put("livePrivacyIoThreadsAfterJobs", 0);
                    report.put("afterEachAcceptedJob", snapshots);
                } else {
                    stage = "CONTROLLED_SCANNER_FAILURE";
                    verifyScannerFailure(jdbc, receipts, fixture, scanMode, report);
                }
                report.put("afterJobs", resources());
                stage = "CLOSE_APPLICATION";
            }
            requireNoScannerResources();
            report.put("afterApplicationClose", resources());
            passed = true;
        } catch (Throwable failure) {
            // Fixed stage/code only; no source text, credential, path, exception or stack trace.
            report.put("failureCode", "SYNTHETIC_RUNTIME_VERIFICATION_FAILED");
        }
        report.put("stage", stage);
        report.put("outcome", passed ? "PASS" : "FAIL");
        report.put("experimentSeconds", seconds(started));
        System.out.println(PREFIX + MAPPER.writeValueAsString(report));
        if (!passed) System.exit(1);
    }

    private static void verifyScannerFailure(JdbcTemplate jdbc, TicketReceiptApplicationService receipts,
            LoopbackAi fixture, ScanMode mode, Map<String, Object> report) throws Exception {
        require(!Files.exists(CHILD_WITNESS));
        long started = System.nanoTime();
        var receipt = receipts.receive(TITLE, BODY, lab.helpdesk.identity.IdentityFixtures.actor(AUTHOR));
        awaitTerminal(jdbc, receipt.jobId(), "FAILED");
        assertReceipt(jdbc, receipt, BODY);
        String expectedCode = mode == ScanMode.TIMEOUT ? "PRIVACY_SCAN_TIMEOUT" : "ADAPTER_CONFIGURATION_ERROR";
        require(expectedCode.equals(jdbc.queryForObject("SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?",
                String.class, receipt.jobId())));
        // The child writes this only after the packaged program emitted its valid JSON.
        // Thus a timeout before child startup cannot count as this experiment's success.
        String witness = Files.readString(CHILD_WITNESS).trim();
        require(witness.matches("[1-9][0-9]*"));
        long childPid = Long.parseLong(witness);
        require(ProcessHandle.of(childPid).map(handle -> !handle.isAlive()).orElse(true));
        requireNoScannerResources();
        var failureResources = resources();
        double failureSeconds = seconds(started);
        require(jdbc.queryForObject("SELECT count(*) FROM ticket_suggestions", Integer.class) == 0);
        require(fixture.requests.get() == 0);

        var rejected = receipts.receive(TITLE, OUTSIDE, lab.helpdesk.identity.IdentityFixtures.actor(AUTHOR));
        awaitTerminal(jdbc, rejected.jobId(), "FAILED");
        assertReceipt(jdbc, rejected, OUTSIDE);
        require("ADAPTER_CONFIGURATION_ERROR".equals(jdbc.queryForObject(
                "SELECT last_failure_code FROM ai_suggestion_jobs WHERE id = ?", String.class, rejected.jobId())));
        Thread.sleep(1_500); // Observe terminal failures through several automatic idle ticks.
        require(Files.readString(CHILD_WITNESS).trim().equals(witness));
        requireNoScannerResources();
        require(fixture.requests.get() == 0);
        require(jdbc.queryForObject("SELECT count(*) FROM tickets", Integer.class) == 2);
        require(jdbc.queryForObject("SELECT count(*) FROM ticket_messages", Integer.class) == 2);
        require(jdbc.queryForObject("SELECT count(*) FROM ai_suggestion_attempts", Integer.class) == 2);
        require(jdbc.queryForObject("SELECT count(*) FROM ticket_suggestions", Integer.class) == 0);
        require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT bool_and(current_attempt = 1 "
                + "AND reserved_generation_count = 1 AND status = 'FAILED') FROM ai_suggestion_jobs", Boolean.class)));
        report.put("acceptedJobs", 0);
        report.put("rejectedJobs", 2);
        report.put("controlledScannerFailureJobs", 1);
        report.put("expectedScannerFailureCode", expectedCode);
        report.put("childReachedAfterJsonWrite", true);
        report.put("ownedChildConfirmedDead", true);
        report.put("providerHttpRequests", 0);
        report.put("originalsAuthorsAndReservationsPreserved", true);
        report.put("liveChildProcessesAfterJobs", 0);
        report.put("livePrivacyIoThreadsAfterJobs", 0);
        report.put("afterControlledFailure", failureResources);
        report.put("receiptToObservedFailureSeconds", failureSeconds);
    }

    static ScanMode scanMode(Environment environment) {
        return ScanMode.valueOf(environment.getRequiredProperty("helpdesk.runtime.probe.scan-mode"));
    }

    private static void awaitTerminal(JdbcTemplate jdbc, long id, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, id);
            if (List.of("SUCCEEDED", "FAILED", "ABSTAINED").contains(status)) { require(expected.equals(status)); return; }
            Thread.sleep(100);
        }
        require(false);
    }

    private static void assertReceipt(JdbcTemplate jdbc, TicketReceiptResult receipt, String body) {
        require(Boolean.TRUE.equals(jdbc.queryForObject("SELECT t.title = ? AND t.status = 'OPEN' AND m.body = ? "
                + "AND m.author_username = ? AND j.current_attempt = 1 AND j.reserved_generation_count = 1 "
                + "FROM tickets t JOIN ticket_messages m ON m.ticket_id = t.id "
                + "JOIN ai_suggestion_jobs j ON j.input_message_id = m.id WHERE j.id = ?",
                Boolean.class, TITLE, body, AUTHOR, receipt.jobId())));
    }

    private static void requirePackaged(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        require(type.getResource(resource).toExternalForm().contains("/app/helpdesk.jar"));
    }

    private static void requireNoScannerResources() {
        require(ProcessHandle.current().descendants().noneMatch(ProcessHandle::isAlive));
        require(Thread.getAllStackTraces().keySet().stream().noneMatch(thread ->
                thread.isAlive() && thread.getName().startsWith("helpdesk-privacy-io-")));
    }

    private static Map<String, Object> resources() throws IOException {
        var result = new LinkedHashMap<String, Object>();
        for (String name : List.of("memory.current", "memory.peak", "memory.max", "memory.swap.max")) {
            result.put(name, Long.parseLong(Files.readString(CGROUP.resolve(name)).trim()));
        }
        var counters = new LinkedHashMap<String, Long>();
        for (String name : List.of("memory.events", "cpu.stat")) {
            for (String line : Files.readAllLines(CGROUP.resolve(name))) {
                String[] parts = line.trim().split("\\s+");
                require(parts.length == 2 && parts[0].matches("[a-z_]+") && parts[1].matches("[0-9]+"));
                counters.put(name + "." + parts[0], Long.parseLong(parts[1]));
            }
        }
        require(counters.get("memory.events.oom") == 0 && counters.get("memory.events.oom_kill") == 0);
        result.put("counters", counters);
        return result;
    }

    private static double seconds(long started) { return Math.round((System.nanoTime() - started) / 1_000_000.0) / 1_000.0; }
    private static void require(boolean value) { if (!value) throw new IllegalStateException("SYNTHETIC_RUNTIME_ASSERTION_FAILED"); }

    @Configuration(proxyBeanMethods = false)
    static class ControlledAiConfiguration {
        @Bean @Primary
        AiPrivacyScanner runtimeProbeScanner(@Qualifier("aiPrivacyScanner") AiPrivacyScanner packagedScanner,
                Environment environment) {
            var mode = scanMode(environment);
            if (mode == ScanMode.NORMAL) return packagedScanner;
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
            var command = List.of(java, "-Xms16m", "-Xmx64m", "-XX:ActiveProcessorCount=1",
                    "-Dfile.encoding=UTF-8", "-Dloader.path=/opt/probe",
                    "-Dloader.main=" + FaultScannerProgram.class.getName(), "-cp", "/app/helpdesk.jar",
                    "org.springframework.boot.loader.launch.PropertiesLauncher", mode.name());
            return new SyntheticDeploymentPrivacyScanner(new ProcessAiPrivacyScanner(command, 4_096, 1_024, 1_024,
                    Duration.ofMillis(environment.getRequiredProperty(
                            "helpdesk.ai.synthetic-scan.cleanup-timeout-ms", Long.class))));
        }
        @Bean(destroyMethod = "close") LoopbackAi loopbackAi() throws IOException { return new LoopbackAi(); }
        @Bean(destroyMethod = "close") SpringAiOpenAiSuggestionProvider controlledProvider(Environment environment,
                AiInputPrivacyGuard guard, LoopbackAi fixture) {
            return SyntheticRuntimeProviderBridge.create(environment, guard, fixture.server.getAddress().getPort());
        }
        @Bean AiSuggestionOutputValidator controlledValidator(Environment environment) {
            return SyntheticRuntimeProviderBridge.validator(environment);
        }
    }

    /** Test-only foreground child; no user-selected mode or new Main configuration. */
    public static final class FaultScannerProgram {
        private FaultScannerProgram() { }
        public static void main(String[] args) throws Exception {
            if (args.length != 1 || !List.of("TIMEOUT", "NON_ZERO_EXIT").contains(args[0])) System.exit(9);
            SyntheticPrivacyScannerProgram.main(new String[0]);
            Files.writeString(CHILD_WITNESS, Long.toString(ProcessHandle.current().pid()), StandardOpenOption.CREATE_NEW);
            if ("NON_ZERO_EXIT".equals(args[0])) System.exit(7);
            Thread.sleep(120_000); // Keep stdout open after valid JSON; the parent must enforce its deadline.
        }
    }

    static final class LoopbackAi implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger validMaskedRequests = new AtomicInteger();
        LoopbackAi() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                requests.incrementAndGet();
                var root = MAPPER.readTree(exchange.getRequestBody().readNBytes(32_769));
                var input = MAPPER.readTree(root.path("messages").get(1).path("content").asString());
                if (TITLE.equals(input.path("title").asString()) && BODY.replace(SyntheticDeploymentPrivacyScanner.EMAIL,
                        "[EMAIL_REDACTED]").equals(input.path("body").asString())) validMaskedRequests.incrementAndGet();
                var message = MAPPER.createObjectNode().put("role", "assistant").put("content",
                        "{\"decision\":\"SUGGEST\",\"summary\":\"새 링크로 로그인 성공 후 안내 요청\","
                        + "\"categories\":[\"ACCOUNT\"],\"priority\":\"NORMAL\"}").putNull("refusal");
                byte[] response = MAPPER.writeValueAsBytes(Map.of("id", "synthetic-runtime", "object", "chat.completion",
                        "created", 1_791_244_800, "model", "gpt-6-luna", "service_tier", "default",
                        "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", message)),
                        "usage", Map.of("prompt_tokens", 120, "completion_tokens", 40, "total_tokens", 160)));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var stream = exchange.getResponseBody()) { stream.write(response); }
            });
            server.start();
        }
        @Override public void close() { server.stop(0); }
    }
}
