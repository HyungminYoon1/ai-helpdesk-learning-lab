package lab.helpdesk.ticket.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lab.helpdesk.identity.*;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Explicit local CLI experiment, not included in the default *Test suite. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "helpdesk.ai.provider.enabled=false", "helpdesk.ai.worker.enabled=false",
        "logging.level.root=WARN"})
@ActiveProfiles("postgres")
@Import(ConversationBrowserExperiment.Users.class)
@Testcontainers
class ConversationBrowserExperiment {
    private static final String A="browser-user-a", B="browser-user-b", AGENT="browser-agent";
    private static final String SECRET=UUID.randomUUID().toString();
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired Environment environment;
    int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AiSuggestionProvider provider;

    @BeforeEach
    void provisionSyntheticAccounts() {
        port=environment.getRequiredProperty("local.server.port",Integer.class);
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) connection -> connection.getMetaData().getURL()
                .split("\\?",2)[0].equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();
        for(String name:List.of(A,B,AGENT)) IdentityFixtures.bind(jdbc,name);
    }

    @Test
    void browser_session_csrf_reply_correction_owner_isolation_and_database_agree() throws Exception {
        Path artifacts=Path.of("output","playwright").toAbsolutePath();
        Files.createDirectories(artifacts);
        Path run=Files.createTempDirectory(artifacts,"week10-conversation-");
        String shell=System.getProperty("helpdesk.browser.pwsh","pwsh.exe");
        var builder=new ProcessBuilder(shell,"-NoProfile","-NonInteractive","-File",
                Path.of("scripts","Verify-Week10ConversationBrowser.ps1").toAbsolutePath().toString(),
                "-Port",String.valueOf(port),"-RunDirectory",run.toString());
        var environment=builder.environment();
        var original=new java.util.HashMap<>(environment);
        environment.clear();
        for(var entry:original.entrySet()) {
            if(List.of("SystemRoot","WINDIR","PATH","PATHEXT","TEMP","TMP","USERPROFILE","APPDATA","LOCALAPPDATA","ComSpec",
                    "ProgramFiles","ProgramFiles(x86)","ProgramW6432","SystemDrive")
                    .stream().anyMatch(name -> name.equalsIgnoreCase(entry.getKey()))) {
                environment.put(entry.getKey(),entry.getValue());
            }
        }
        environment.put("PLAYWRIGHT_CLI_SESSIONS_ROOT",run.resolve("sessions").toString());
        environment.put("HELPDESK_BROWSER_A_NAME",A); environment.put("HELPDESK_BROWSER_B_NAME",B);
        environment.put("HELPDESK_BROWSER_AGENT_NAME",AGENT); environment.put("HELPDESK_BROWSER_SECRET",SECRET);
        builder.redirectErrorStream(true);
        Process child=builder.start();
        var output=new java.io.ByteArrayOutputStream();
        Thread reader=Thread.ofVirtual().start(() -> {try{child.getInputStream().transferTo(output);}catch(Exception ignored){}});
        try {
            assertThat(child.waitFor(Duration.ofMinutes(4).toMillis(),TimeUnit.MILLISECONDS)).isTrue();
            reader.join(2000);
            String raw=output.toString(java.nio.charset.StandardCharsets.UTF_8);
            // Emit only whitelisted stage/counters, never raw Browser/CLI/credential output.
            String line=raw.lines().filter(value -> value.startsWith("{\"evidence\":")).findFirst().orElse("{}");
            var evidence=mapper.readTree(line);
            if(!evidence.path("completed").asBoolean()) throw new AssertionError("Browser stage="+evidence.path("stage").asString("NO_EVIDENCE"));
            assertThat(child.exitValue()).isZero();
            long ticket=evidence.path("ticketId").asLong(),reply=evidence.path("replyId").asLong();
            assertThat(jdbc.queryForObject("SELECT title FROM tickets WHERE id=?",String.class,ticket)).isEqualTo("Conversation Browser synthetic");
            assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id=?",String.class,ticket)).isEqualTo("OPEN");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE ticket_id=?",Long.class,ticket)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE ticket_id=? AND message_type='INQUIRY'",String.class,ticket)).isEqualTo("Original inquiry preserved.");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE ticket_id=? AND corrects_message_id=?",Long.class,ticket,reply)).isOne();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs",String.class)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT reserved_generation_count FROM ai_suggestion_jobs",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_suggestions",Long.class)).isZero();
            assertThat(Files.exists(run.resolve("conversation-agent.png"))).isTrue();
            verifyNoInteractions(provider);
            System.out.println("LOCAL_CONVERSATION_BROWSER_POSTGRES completed=true messages=3 providerCalls=0");
        } finally {
            if(child.isAlive()){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();}
            reader.join(2000);
        }
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class Users {
        @Bean UserDetailsService conversationBrowserUsers(PasswordEncoder encoder) {
            return new IdentityAwareInMemoryUserDetailsManager(
                    IdentityFixtures.user(User.withUsername(A).password(encoder.encode(SECRET)).roles("USER").build()),
                    IdentityFixtures.user(User.withUsername(B).password(encoder.encode(SECRET)).roles("USER").build()),
                    IdentityFixtures.user(User.withUsername(AGENT).password(encoder.encode(SECRET)).roles("USER","AGENT").build()));
        }
    }
}
