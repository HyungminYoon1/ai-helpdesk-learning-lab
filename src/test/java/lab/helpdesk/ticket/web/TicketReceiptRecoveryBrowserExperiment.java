package lab.helpdesk.ticket.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.identity.IdentityAwareInMemoryUserDetailsManager;
import lab.helpdesk.identity.IdentityFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "helpdesk.ai.provider.enabled=false",
        "helpdesk.ai.worker.enabled=false",
        "helpdesk.support.receipt-service-code=synthetic-support",
        "logging.level.root=WARN"
})
@ActiveProfiles("postgres")
@Import(TicketReceiptRecoveryBrowserExperiment.Users.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TicketReceiptRecoveryBrowserExperiment {

    private static final String CUSTOMER = "receipt-browser-customer";
    private static final String OTHER_CUSTOMER = "receipt-browser-other";
    private static final String SYNTHETIC_PASSWORD = UUID.randomUUID().toString();
    private static final Set<String> CHILD_ENVIRONMENT = Set.of(
            "SystemRoot", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP", "USERPROFILE",
            "APPDATA", "LOCALAPPDATA", "ComSpec", "ProgramFiles", "ProgramFiles(x86)",
            "ProgramW6432", "SystemDrive");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private AiSuggestionProvider provider;

    @Test
    void committed_reply_loss_is_confirmed_once_and_another_session_cannot_resend_the_draft() throws Exception {
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]))).isTrue();
        long customerId = IdentityFixtures.bind(jdbc, CUSTOMER);
        IdentityFixtures.bind(jdbc, OTHER_CUSTOMER);

        Path artifacts = Path.of("output", "playwright").toAbsolutePath();
        Files.createDirectories(artifacts);
        Path run = Files.createTempDirectory(artifacts, "pilot-ticket-receipt-");
        var builder = new ProcessBuilder(
                System.getProperty("helpdesk.browser.pwsh", "pwsh.exe"), "-NoProfile", "-NonInteractive", "-File",
                Path.of("scripts", "Verify-PilotTicketReceiptBrowser.ps1").toAbsolutePath().toString(),
                "-Port", environment.getRequiredProperty("local.server.port"), "-RunDirectory", run.toString());
        var childEnv = builder.environment();
        var inherited = new HashMap<>(childEnv);
        childEnv.clear();
        inherited.forEach((name, value) -> {
            if (CHILD_ENVIRONMENT.stream().anyMatch(name::equalsIgnoreCase)) {
                childEnv.put(name, value);
            }
        });
        childEnv.put("PLAYWRIGHT_CLI_SESSIONS_ROOT", run.resolve("sessions").toString());
        childEnv.put("HELPDESK_RECEIPT_BROWSER_CUSTOMER", CUSTOMER);
        childEnv.put("HELPDESK_RECEIPT_BROWSER_OTHER", OTHER_CUSTOMER);
        childEnv.put("HELPDESK_RECEIPT_BROWSER_PASSWORD", SYNTHETIC_PASSWORD);
        builder.redirectErrorStream(true);

        Process child = builder.start();
        var captured = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var stream = child.getInputStream()) {
                stream.transferTo(captured);
            } catch (java.io.IOException ignored) {
                // The subprocess may be terminated after a timeout.
            }
        });
        try {
            assertThat(child.waitFor(4, TimeUnit.MINUTES)).as("bounded Browser CLI run").isTrue();
            reader.join(2000);
            String line = captured.toString(StandardCharsets.UTF_8).lines()
                    .filter(value -> value.startsWith("{\"evidence\":")).findFirst().orElse("{}");
            var evidence = mapper.readTree(line);
            if (!evidence.path("completed").asBoolean()) {
                throw new AssertionError("Receipt Browser stage=" + evidence.path("stage").asString("NO_EVIDENCE")
                        + " code=" + evidence.path("failureCode").asString("NO_CODE"));
            }
            assertThat(child.exitValue()).isZero();
            assertThat(evidence.path("statuses").toString()).isEqualTo("[201,200,201]");
            for (String flag : List.of("sameSubmission", "sameLocation", "sameSessionCsrf",
                    "sessionSwitchNoPost", "reloadNoPost", "ownerListMatches")) {
                assertThat(evidence.path(flag).asBoolean()).as(flag).isTrue();
            }

            long confirmedId = evidence.path("confirmedId").asLong();
            long freshId = evidence.path("freshId").asLong();
            assertThat(confirmedId).isPositive();
            assertThat(freshId).isPositive().isNotEqualTo(confirmedId);
            assertThat(jdbc.queryForList("SELECT id FROM tickets ORDER BY id", Long.class))
                    .containsExactly(confirmedId, freshId);
            assertThat(jdbc.queryForList("SELECT requester_user_id FROM tickets ORDER BY id", Long.class))
                    .containsExactly(customerId, customerId);
            assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT receipt_request_id) FROM tickets", Long.class))
                    .isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT body FROM ticket_messages ORDER BY id", String.class))
                    .containsExactly("  합성 문의 원문\n공백을 보존합니다.  ", "  재로그인 확인용 합성 문의  ");
            assertThat(jdbc.queryForList("SELECT author_user_id FROM ticket_messages ORDER BY id", Long.class))
                    .containsExactly(customerId, customerId);
            assertThat(jdbc.queryForList("""
                    SELECT m.ticket_id FROM ticket_messages m
                    JOIN ai_suggestion_jobs j ON j.input_message_id = m.id
                    WHERE j.status = 'PENDING' AND j.current_attempt = 0 AND j.reserved_generation_count = 0
                    ORDER BY m.ticket_id
                    """, Long.class)).containsExactly(confirmedId, freshId);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_suggestion_jobs", Long.class)).isEqualTo(2);
            verifyNoInteractions(provider);

            Files.writeString(run.resolve("evidence.json"), line + "\n", StandardCharsets.UTF_8);
            System.out.println("PILOT_RECEIPT_BROWSER_POSTGRES completed=true statuses=201,200,201 "
                    + "tickets=2 messages=2 jobs=2 providerCalls=0 evidence=output/playwright/"
                    + run.getFileName() + "/evidence.json");
        } finally {
            if (child.isAlive()) {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
            }
            reader.join(2000);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Users {

        @Bean
        UserDetailsService receiptBrowserUsers(PasswordEncoder encoder) {
            String password = encoder.encode(SYNTHETIC_PASSWORD);
            return new IdentityAwareInMemoryUserDetailsManager(
                    IdentityFixtures.user(User.withUsername(CUSTOMER).password(password).roles("USER").build()),
                    IdentityFixtures.user(User.withUsername(OTHER_CUSTOMER).password(password).roles("USER").build()));
        }
    }
}
