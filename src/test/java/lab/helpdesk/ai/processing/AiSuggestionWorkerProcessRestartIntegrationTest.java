package lab.helpdesk.ai.processing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import lab.helpdesk.ai.processing.AiWorkerProcessTestApplication.Mode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Different native JVM PIDs; the same PostgreSQL stays alive. No live AI or Browser. */
@Testcontainers
class AiSuggestionWorkerProcessRestartIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @TempDir
    Path temporary;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clear_only_the_verified_testcontainer_database() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        String url = new JdbcTemplate(dataSource).execute(
                (org.springframework.jdbc.core.ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        if (url == null || !url.split("\\?", 2)[0].equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0])) {
            throw new IllegalStateException("AI_JVM_TEST_DATABASE_REQUIRED");
        }
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("ticket_suggestion_categories", "ticket_suggestions", "ai_suggestion_attempts",
                "ai_suggestion_jobs", "ticket_messages", "tickets")) {
            jdbc.update("DELETE FROM " + table); // Literal table list, this verified isolated test database only.
        }
    }

    @Test
    void a_new_jvm_processes_the_committed_pending_receipt() throws Exception {
        Checkpoint ready;
        Policy policy;
        try (Child first = start(Mode.PREPARE_PENDING, null)) {
            ready = first.await("READY");
            assertThat(ready.calls()).isZero();
            assertState(ready, "PENDING", 0, 0);
            policy = policy(ready.job());
            first.terminate();
        }
        try (Child second = start(Mode.RUN_TO_SUCCESS, ready)) {
            Checkpoint done = second.await("DONE");
            assertNewProcess(ready, done);
            assertThat(done.calls()).isOne();
            assertState(done, "SUCCEEDED", 1, 1);
            assertThat(policy(done.job())).isEqualTo(policy);
            assertThat(timeline(done.job()).deadline()).isNotNull();
            second.awaitSuccessfulExit();
        }
        assertOriginal(ready);
    }

    @Test
    void a_reserved_but_uncalled_attempt_survives_termination_and_is_recovered_conditionally() throws Exception {
        Checkpoint ready;
        Policy policy;
        Timeline timeline;
        try (Child first = start(Mode.RESERVE_UNCONFIRMED, null)) {
            ready = first.await("READY");
            assertThat(ready.calls()).isZero();
            assertState(ready, "RUNNING", 1, 0);
            assertResult(ready.job(), 1, "UNCONFIRMED");
            policy = policy(ready.job());
            timeline = timeline(ready.job());
            first.terminate();
        }
        expireTestLease(ready.job());
        try (Child second = start(Mode.RUN_TO_SUCCESS, ready)) {
            Checkpoint done = second.await("DONE");
            assertNewProcess(ready, done);
            assertThat(done.calls()).isOne(); // Two reservations do not mean two controlled Provider calls.
            assertState(done, "SUCCEEDED", 2, 1);
            assertResult(done.job(), 1, "UNCONFIRMED");
            assertRecoveryReservation(done.job());
            assertThat(policy(done.job())).isEqualTo(policy);
            assertThat(timeline(done.job())).isEqualTo(timeline);
            second.awaitSuccessfulExit();
        }
        assertOriginal(ready);
    }

    @Test
    void an_observed_unknown_result_survives_termination_without_resetting_the_original_deadline() throws Exception {
        Checkpoint ready;
        Policy policy;
        Timeline timeline;
        try (Child first = start(Mode.OBSERVE_UNKNOWN, null)) {
            ready = first.await("READY");
            assertThat(ready.calls()).isOne();
            assertState(ready, "RUNNING", 1, 0);
            assertResult(ready.job(), 1, "OUTCOME_UNKNOWN");
            policy = policy(ready.job());
            timeline = timeline(ready.job());
            first.terminate();
        }
        expireTestLease(ready.job());
        try (Child second = start(Mode.RUN_TO_SUCCESS, ready)) {
            Checkpoint done = second.await("DONE");
            assertNewProcess(ready, done);
            assertThat(done.calls()).isOne();
            assertState(done, "SUCCEEDED", 2, 1);
            assertResult(done.job(), 1, "OUTCOME_UNKNOWN");
            assertRecoveryReservation(done.job());
            assertThat(policy(done.job())).isEqualTo(policy);
            assertThat(timeline(done.job())).isEqualTo(timeline);
            second.awaitSuccessfulExit();
        }
        assertOriginal(ready);
    }

    @Test
    void a_rate_limit_without_retry_after_stays_blocked_in_the_next_jvm() throws Exception {
        Checkpoint ready;
        Policy policy;
        Timeline timeline;
        try (Child first = start(Mode.OBSERVE_BLOCKED, null)) {
            ready = first.await("READY");
            assertThat(ready.calls()).isOne();
            assertState(ready, "RUNNING", 1, 0);
            assertResult(ready.job(), 1, "AUTO_RETRY_BLOCKED");
            policy = policy(ready.job());
            timeline = timeline(ready.job());
            first.terminate();
        }
        expireTestLease(ready.job());
        try (Child second = start(Mode.CHECK_BLOCKED, ready)) {
            Checkpoint done = second.await("DONE");
            assertNewProcess(ready, done);
            assertThat(done.calls()).isZero(); // The child also ran an explicit Worker tick, not just a sleep.
            assertState(done, "RUNNING", 1, 0);
            assertResult(done.job(), 1, "AUTO_RETRY_BLOCKED");
            assertThat(policy(done.job())).isEqualTo(policy);
            assertThat(timeline(done.job())).isEqualTo(timeline);
            second.awaitSuccessfulExit();
        }
        assertOriginal(ready);
    }

    @Test
    void a_persisted_rate_limit_wait_is_kept_before_the_next_jvm_resumes_processing() throws Exception {
        Checkpoint ready;
        Policy policy;
        Timeline timeline;
        OffsetDateTime due;
        try (Child first = start(Mode.PREPARE_RATE_WAIT, null)) {
            ready = first.await("READY");
            assertThat(ready.calls()).isOne();
            assertState(ready, "PENDING", 1, 0);
            assertResult(ready.job(), 1, "AUTO_RETRY_BLOCKED");
            policy = policy(ready.job());
            timeline = timeline(ready.job());
            due = due(ready.job());
            assertThat(due).isAfter(OffsetDateTime.now().plusSeconds(30));
            first.terminate();
        }
        try (Child second = start(Mode.WAIT_AND_RESUME, ready)) {
            Checkpoint waiting = second.await("WAITING");
            assertNewProcess(ready, waiting);
            assertThat(waiting.calls()).isZero();
            assertState(waiting, "PENDING", 1, 0);
            assertThat(due(waiting.job())).isEqualTo(due);
            assertThat(policy(waiting.job())).isEqualTo(policy);
            assertThat(timeline(waiting.job())).isEqualTo(timeline);
            // Future-time behavior is verified above. Advance only this test-owned boundary, not 120s of wall time.
            jdbc.update("UPDATE ai_suggestion_jobs SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' WHERE id = ?",
                    waiting.job());
            second.proceed();
            Checkpoint done = second.await("DONE");
            assertThat(done.pid()).isEqualTo(waiting.pid());
            assertThat(done.calls()).isOne();
            assertState(done, "SUCCEEDED", 2, 1);
            assertThat(policy(done.job())).isEqualTo(policy);
            assertThat(timeline(done.job())).isEqualTo(timeline);
            assertThat(jdbc.queryForObject("SELECT reserved_output_repair_count FROM ai_suggestion_jobs WHERE id = ?",
                    Integer.class, done.job())).isZero();
            second.awaitSuccessfulExit();
        }
        assertOriginal(ready);
    }

    private void expireTestLease(long jobId) {
        jdbc.update("UPDATE ai_suggestion_jobs SET lease_expires_at = clock_timestamp() - INTERVAL '1 minute' WHERE id = ?",
                jobId);
    }

    private void assertState(Checkpoint ids, String status, int attempts, int suggestions) {
        assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs WHERE id = ?", String.class, ids.job()))
                .isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT current_attempt FROM ai_suggestion_jobs WHERE id = ?", Integer.class, ids.job()))
                .isEqualTo(attempts);
        assertThat(jdbc.queryForObject("SELECT reserved_generation_count FROM ai_suggestion_jobs WHERE id = ?",
                Integer.class, ids.job())).isEqualTo(attempts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts WHERE job_id = ?", Long.class, ids.job()))
                .isEqualTo(attempts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestions WHERE job_id = ?", Long.class, ids.job()))
                .isEqualTo(suggestions);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_suggestion_categories", Long.class))
                .isEqualTo(suggestions);
    }

    private void assertResult(long jobId, int attempt, String code) {
        assertThat(jdbc.queryForObject("SELECT result_code FROM ai_suggestion_attempts WHERE job_id = ? AND attempt_number = ?",
                String.class, jobId, attempt)).isEqualTo(code);
    }

    private void assertRecoveryReservation(long jobId) {
        assertThat(jdbc.queryForObject("SELECT request_kind FROM ai_suggestion_attempts WHERE job_id = ? AND attempt_number = 2",
                String.class, jobId)).isEqualTo("RECOVERY");
    }

    private void assertOriginal(Checkpoint ids) {
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id = ? AND ticket_id = ?",
                String.class, ids.message(), ids.ticket())).isEqualTo(AiWorkerProcessTestApplication.ORIGINAL);
        assertThat(jdbc.queryForObject("SELECT author_username FROM ticket_messages WHERE id = ?",
                String.class, ids.message())).isEqualTo("synthetic-author");
        assertThat(jdbc.queryForObject("SELECT input_message_id FROM ai_suggestion_jobs WHERE id = ?", Long.class, ids.job()))
                .isEqualTo(ids.message());
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, ids.ticket())).isEqualTo("OPEN");
        for (String table : List.of("tickets", "ticket_messages", "ai_suggestion_jobs")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isOne();
        }
    }

    private Policy policy(long jobId) {
        return jdbc.queryForObject("SELECT * FROM ai_suggestion_jobs WHERE id = ?", (row, index) -> new Policy(
                row.getString("policy_version"), row.getString("policy_snapshot_source"),
                row.getInt("max_generation_attempts"), row.getInt("max_output_repair_attempts"),
                row.getLong("request_timeout_ms"), row.getLong("attempt_lease_ms"),
                row.getLong("retry_backoff_ms"), row.getLong("job_processing_timeout_ms")), jobId);
    }

    private Timeline timeline(long jobId) {
        return jdbc.queryForObject("SELECT first_started_at, processing_deadline_at FROM ai_suggestion_jobs WHERE id = ?",
                (row, index) -> new Timeline(row.getObject("first_started_at", OffsetDateTime.class),
                        row.getObject("processing_deadline_at", OffsetDateTime.class)), jobId);
    }

    private OffsetDateTime due(long jobId) {
        return jdbc.queryForObject("SELECT next_attempt_at FROM ai_suggestion_jobs WHERE id = ?", OffsetDateTime.class, jobId);
    }

    private void assertNewProcess(Checkpoint first, Checkpoint second) {
        assertThat(second.pid()).isNotEqualTo(first.pid());
        assertThat(second.ticket()).isEqualTo(first.ticket());
        assertThat(second.message()).isEqualTo(first.message());
        assertThat(second.job()).isEqualTo(first.job());
        System.out.printf("AI_JVM_RESTART_VERIFIED firstPid=%d secondPid=%d job=%d%n", first.pid(), second.pid(), first.job());
    }

    private Child start(Mode mode, Checkpoint ids) throws IOException {
        String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path executable = Path.of(System.getProperty("java.home"), "bin", javaName);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        if (classpath.contains("\n") || classpath.contains("\r")) {
            throw new IllegalStateException("AI_JVM_TEST_CLASSPATH_INVALID");
        }
        Path arguments = Files.createTempFile(temporary, "worker-", ".args");
        // @argfile avoids Windows command-length limits. Only classpath and mode, never credentials.
        String quotedClasspath = "\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        Files.writeString(arguments, "-Xmx256m\n-Dfile.encoding=UTF-8\n-classpath\n" + quotedClasspath + "\n"
                + AiWorkerProcessTestApplication.class.getName() + "\n" + mode.name() + "\n", StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "@" + arguments).redirectErrorStream(true);
        var environment = builder.environment();
        environment.clear();
        // Required OS plumbing only. Do not inherit API keys, JVM injection flags or Spring DB settings.
        for (String name : List.of("SystemRoot", "WINDIR", "SystemDrive", "TEMP", "TMP", "ComSpec", "PATHEXT")) {
            String value = System.getenv(name);
            if (value != null) environment.put(name, value);
        }
        environment.put("HELPDESK_JVM_TEST_JDBC_URL", POSTGRES.getJdbcUrl());
        environment.put("HELPDESK_JVM_TEST_DB_USERNAME", POSTGRES.getUsername());
        environment.put("HELPDESK_JVM_TEST_DB_PASSWORD", POSTGRES.getPassword());
        if (ids != null) {
            environment.put("HELPDESK_JVM_TEST_TICKET_ID", Long.toString(ids.ticket()));
            environment.put("HELPDESK_JVM_TEST_MESSAGE_ID", Long.toString(ids.message()));
            environment.put("HELPDESK_JVM_TEST_JOB_ID", Long.toString(ids.job()));
        }
        return new Child(builder.start());
    }

    private record Policy(String version, String source, int generationLimit, int repairLimit,
            long requestTimeoutMs, long attemptLeaseMs, long backoffMs, long processingTimeoutMs) {
    }

    private record Timeline(OffsetDateTime firstStarted, OffsetDateTime deadline) {
    }

    private record Checkpoint(String phase, long pid, long ticket, long message, long job, int calls) {
    }

    private static final class Child implements AutoCloseable {

        private static final Pattern PROTOCOL = Pattern.compile(
                "AI_JVM_TEST (READY|WAITING|DONE) pid=(\\d+) ticket=(\\d+) message=(\\d+) job=(\\d+) calls=(\\d+)");
        private final Process process;
        private final BlockingQueue<Checkpoint> checkpoints = new LinkedBlockingQueue<>();
        private final Thread outputPump;

        private Child(Process process) {
            this.process = process;
            outputPump = Thread.ofVirtual().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        var matcher = PROTOCOL.matcher(line);
                        if (matcher.matches()) {
                            checkpoints.add(new Checkpoint(matcher.group(1), Long.parseLong(matcher.group(2)),
                                    Long.parseLong(matcher.group(3)), Long.parseLong(matcher.group(4)),
                                    Long.parseLong(matcher.group(5)), Integer.parseInt(matcher.group(6))));
                        } else if (line.equals("AI_JVM_TEST_FAILED")) {
                            checkpoints.add(new Checkpoint("FAILED", process.pid(), 0, 0, 0, 0));
                        }
                        // Drain all other output without exposing raw application logs on assertion failure.
                    }
                } catch (IOException ignored) {
                    // Closing our own terminated Process stream is expected during cleanup.
                } finally {
                    checkpoints.add(new Checkpoint("EOF", process.pid(), 0, 0, 0, 0));
                }
            });
        }

        private Checkpoint await(String phase) throws InterruptedException {
            Checkpoint value = checkpoints.poll(40, TimeUnit.SECONDS);
            assertThat(value).as("AI_JVM_TEST_CHECKPOINT_REQUIRED").isNotNull();
            assertThat(value.phase()).as("AI_JVM_TEST_CHECKPOINT_PHASE").isEqualTo(phase);
            assertThat(value.pid()).isEqualTo(process.pid());
            return value;
        }

        private void proceed() throws IOException {
            var writer = process.outputWriter(StandardCharsets.UTF_8);
            writer.write("CONTINUE\n");
            writer.flush();
        }

        private void awaitSuccessfulExit() throws InterruptedException {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("AI_JVM_TEST_EXIT_REQUIRED").isTrue();
            assertThat(process.exitValue()).isZero();
        }

        private void terminate() throws InterruptedException {
            process.destroyForcibly(); // Only the exact child handle that this test created.
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("AI_JVM_TEST_TERMINATION_REQUIRED").isTrue();
            assertThat(process.isAlive()).isFalse();
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) terminate();
            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();
            outputPump.join(1000);
        }
    }
}
