package lab.helpdesk.ticket.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.identity.*;
import lab.helpdesk.support.TicketAssignmentService;
import lab.helpdesk.ticket.application.*;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Explicit synthetic experiment. Default *Test regression does not run a personal Browser. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","helpdesk.ai.provider.enabled=false","helpdesk.ai.worker.enabled=false",
        "helpdesk.support.receipt-service-code=synthetic-support","logging.level.root=WARN"})
@ActiveProfiles("postgres")
@Import(CustomerConfirmationRecoveryBrowserExperiment.Users.class)
@Testcontainers
class CustomerConfirmationRecoveryBrowserExperiment {
    static final String AGENT="confirmation-recovery-agent",CUSTOMER="confirmation-recovery-customer";
    static final String SECRET=UUID.randomUUID().toString();
    static final List<String> PRESERVED=List.of("tickets","ticket_messages","ticket_assignment_events",
            "ai_suggestion_jobs","ai_suggestion_attempts","ticket_suggestions","ticket_suggestion_categories");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @Autowired TicketAssignmentService assignments;
    @MockitoBean AiSuggestionProvider provider;
    long ticket,inquiry,followup,actor;
    Map<String,List<Map<String,Object>>> before;

    @BeforeEach void isolatedFixture() {
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) c -> c.getMetaData().getURL().split("\\?",2)[0]
                .equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();
        actor=IdentityFixtures.bind(jdbc,AGENT);IdentityFixtures.bind(jdbc,CUSTOMER);
        var received=receipts.receive("Confirmation recovery synthetic","  preserved inquiry  ",IdentityFixtures.actor(CUSTOMER));
        ticket=received.ticket().id();inquiry=received.messageId();
        followup=conversations.followUp(ticket,"  preserved customer followup  ",UUID.randomUUID(),IdentityFixtures.actor(CUSTOMER)).message().id();
        assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(AGENT));
        before=snapshot();
    }

    @Test void committed_response_loss_replays_once_and_failed_post_save_get_only_retries_the_read() throws Exception {
        Path artifacts=Path.of("output","playwright").toAbsolutePath();Files.createDirectories(artifacts);
        Path run=Files.createTempDirectory(artifacts,"week12-confirmation-recovery-");
        var builder=new ProcessBuilder(System.getProperty("helpdesk.browser.pwsh","pwsh.exe"),"-NoProfile","-NonInteractive","-File",
                Path.of("scripts","Verify-Week12ConfirmationRecoveryBrowser.ps1").toAbsolutePath().toString(),
                "-Port",environment.getRequiredProperty("local.server.port"),"-TicketId",Long.toString(ticket),
                "-InquiryId",Long.toString(inquiry),"-FollowupId",Long.toString(followup),"-RunDirectory",run.toString());
        var childEnvironment=builder.environment();var original=new HashMap<>(childEnvironment);childEnvironment.clear();
        for(var entry:original.entrySet())if(List.of("SystemRoot","WINDIR","PATH","PATHEXT","TEMP","TMP","USERPROFILE","APPDATA","LOCALAPPDATA",
                "ComSpec","ProgramFiles","ProgramFiles(x86)","ProgramW6432","SystemDrive").stream().anyMatch(name->name.equalsIgnoreCase(entry.getKey())))
            childEnvironment.put(entry.getKey(),entry.getValue());
        childEnvironment.put("PLAYWRIGHT_CLI_SESSIONS_ROOT",run.resolve("sessions").toString());
        childEnvironment.put("HELPDESK_BROWSER_AGENT_NAME",AGENT);childEnvironment.put("HELPDESK_BROWSER_SECRET",SECRET);
        builder.redirectErrorStream(true);Process child=builder.start();var bytes=new java.io.ByteArrayOutputStream();
        Thread reader=Thread.ofVirtual().start(()->{try{child.getInputStream().transferTo(bytes);}catch(Exception ignored){}});
        try {
            assertThat(child.waitFor(Duration.ofMinutes(4).toMillis(),TimeUnit.MILLISECONDS)).as("bounded recovery Browser CLI completion").isTrue();reader.join(2000);
            String line=bytes.toString(java.nio.charset.StandardCharsets.UTF_8).lines().filter(value->value.startsWith("{\"evidence\":")).findFirst().orElse("{}");
            var evidence=mapper.readTree(line);
            if(!evidence.path("completed").asBoolean())throw new AssertionError("Recovery Browser stage="+evidence.path("stage").asString("NO_EVIDENCE"));
            assertThat(child.exitValue()).isZero();
            assertThat(evidence.path("confirmationPosts").asInt()).isEqualTo(3);
            assertThat(evidence.path("lostCommittedResponses").asInt()).isOne();
            assertThat(evidence.path("failedCurrentReads").asInt()).isOne();
            for(String flag:List.of("sameSubmission","newSubmissionDistinct","postSaveReadOnlyRetry","sessionCookieObserved","csrfHeaderObserved"))
                assertThat(evidence.path(flag).asBoolean()).as(flag).isTrue();
            assertThat(evidence.path("upstreamStatuses").toString()).isEqualTo("[201,200,201]");
            assertThat(jdbc.queryForList("SELECT assignment_version FROM ticket_customer_message_confirmations ORDER BY id",Long.class)).containsExactly(1L,1L);
            assertThat(jdbc.queryForList("SELECT through_message_id FROM ticket_customer_message_confirmations ORDER BY id",Long.class)).containsExactly(inquiry,followup);
            assertThat(jdbc.queryForList("SELECT actor_user_id FROM ticket_customer_message_confirmations ORDER BY id",Long.class)).containsExactly(actor,actor);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT client_request_id) FROM ticket_customer_message_confirmations",Long.class)).isEqualTo(2);
            assertThat(snapshot()).isEqualTo(before);verifyNoInteractions(provider);
            for(String file:List.of("unknown-confirmation.png","saved-refresh-failed.png","recovered-confirmation.png"))assertThat(Files.exists(run.resolve(file))).isTrue();
            System.out.println("LOCAL_CONFIRMATION_RECOVERY_BROWSER_POSTGRES completed=true httpPosts=3 confirmations=2 statuses=201,200,201 providerCalls=0");
        } finally {if(child.isAlive()){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();}reader.join(2000);}
    }
    private Map<String,List<Map<String,Object>>> snapshot(){var rows=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:PRESERVED)rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return rows;}

    @TestConfiguration(proxyBeanMethods=false)
    static class Users {
        @Bean UserDetailsService confirmationRecoveryUsers(PasswordEncoder encoder){return new IdentityAwareInMemoryUserDetailsManager(
                IdentityFixtures.user(User.withUsername(AGENT).password(encoder.encode(SECRET)).roles("USER","AGENT").build()));}
    }
}
