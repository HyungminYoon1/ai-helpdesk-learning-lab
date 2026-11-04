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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Explicit local Session experiment; authority is changed only in this synthetic Testcontainer. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","helpdesk.ai.provider.enabled=false","helpdesk.ai.worker.enabled=false",
        "helpdesk.support.receipt-service-code=synthetic-support","logging.level.root=WARN"})
@ActiveProfiles("postgres")
@Import(CustomerConfirmationAuthorityBrowserExperiment.Users.class)
@Testcontainers
class CustomerConfirmationAuthorityBrowserExperiment {
    static final String A="authority-browser-agent-a",B="authority-browser-agent-b",C="authority-browser-customer";
    static final String SECRET=UUID.randomUUID().toString();
    static final List<String> PRESERVED=List.of("ticket_messages","ai_suggestion_jobs","ai_suggestion_attempts","ticket_suggestions","ticket_suggestion_categories");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketAssignmentService assignments;
    @Autowired CustomerMessageConfirmationService confirmations;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean AiSuggestionProvider provider;
    long ticket,inquiry,a,b,service;
    Map<String,List<Map<String,Object>>> before;
    List<Map<String,Object>> ticketBefore,eventBefore,otherMembersBefore;
    @BeforeEach void fixture(){
        assertIsolated();a=IdentityFixtures.bind(jdbc,A);b=IdentityFixtures.bind(jdbc,B);IdentityFixtures.bind(jdbc,C);
        service=jdbc.queryForObject("SELECT id FROM support_services WHERE code='synthetic-support'",Long.class);
        var receipt=receipts.receive("Confirmation authority synthetic","  original inquiry unchanged  ",IdentityFixtures.actor(C));
        ticket=receipt.ticket().id();inquiry=receipt.messageId();assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(A));
        before=snapshot();ticketBefore=ticketFields();eventBefore=jdbc.queryForList("SELECT * FROM ticket_assignment_events ORDER BY id");
        otherMembersBefore=jdbc.queryForList("SELECT * FROM support_service_memberships WHERE user_id<>? ORDER BY service_id,user_id",a);
    }
    @Test void retained_session_can_replay_after_handoff_but_not_after_committed_membership_revocation() throws Exception {
        Path artifacts=Path.of("output","playwright").toAbsolutePath();Files.createDirectories(artifacts);
        Path run=Files.createTempDirectory(artifacts,"week12-confirmation-authority-");
        var builder=new ProcessBuilder(System.getProperty("helpdesk.browser.pwsh","pwsh.exe"),"-NoProfile","-NonInteractive","-File",
                Path.of("scripts","Verify-Week12ConfirmationAuthorityBrowser.ps1").toAbsolutePath().toString(),
                "-Port",environment.getRequiredProperty("local.server.port"),"-TicketId",Long.toString(ticket),
                "-InquiryId",Long.toString(inquiry),"-ToAgentId",Long.toString(b),"-RunDirectory",run.toString());
        var childEnvironment=builder.environment();var original=new HashMap<>(childEnvironment);childEnvironment.clear();
        for(var entry:original.entrySet())if(List.of("SystemRoot","WINDIR","PATH","PATHEXT","TEMP","TMP","USERPROFILE","APPDATA","LOCALAPPDATA",
                "ComSpec","ProgramFiles","ProgramFiles(x86)","ProgramW6432","SystemDrive").stream().anyMatch(name->name.equalsIgnoreCase(entry.getKey())))
            childEnvironment.put(entry.getKey(),entry.getValue());
        childEnvironment.put("PLAYWRIGHT_CLI_SESSIONS_ROOT",run.resolve("sessions").toString());
        childEnvironment.put("HELPDESK_BROWSER_AGENT_NAME",A);childEnvironment.put("HELPDESK_BROWSER_SECRET",SECRET);
        builder.redirectErrorStream(true);Process child=builder.start();var bytes=new java.io.ByteArrayOutputStream();
        Thread reader=Thread.ofVirtual().start(()->{try{child.getInputStream().transferTo(bytes);}catch(Exception ignored){}});
        try {
            Path ready=run.resolve("ready-for-revocation");long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
            while(!Files.exists(ready) && child.isAlive() && System.nanoTime()<deadline)Thread.sleep(50);
            if(!Files.exists(ready))throw new AssertionError("Browser did not reach revocation gate; stage="+evidence(bytes).path("stage").asString("NO_EVIDENCE"));
            assertThat(jdbc.queryForObject("SELECT assignee_user_id FROM tickets WHERE id=?",Long.class,ticket)).isEqualTo(b);
            var stored=jdbc.queryForList("SELECT * FROM ticket_customer_message_confirmations ORDER BY id");assertThat(stored).hasSize(1);
            assertIsolated();
            new TransactionTemplate(manager).executeWithoutResult(status->{jdbc.execute("SET LOCAL lock_timeout='2s'");
                assertThat(jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,a)).isOne();});
            // Signal only after the revoke transaction has committed; no production test endpoint is added.
            Files.writeString(run.resolve("revocation-committed"),"READY\n",java.nio.charset.StandardCharsets.UTF_8);
            assertThat(child.waitFor(Duration.ofMinutes(2).toMillis(),TimeUnit.MILLISECONDS)).isTrue();reader.join(2000);
            var result=evidence(bytes);
            if(!result.path("completed").asBoolean())throw new AssertionError("Authority Browser stage="+result.path("stage").asString("NO_EVIDENCE"));
            assertThat(child.exitValue()).isZero();
            assertThat(result.path("upstreamStatuses").toString()).isEqualTo("[201,200,403,404]");
            for(String flag:List.of("sameSubmission","handoffStateSeparated","newWriteDenied","revokedReplayDenied","sessionStillAuthenticated","revokedReadDenied","csrfHeaderObserved"))
                assertThat(result.path(flag).asBoolean()).as(flag).isTrue();
            assertThat(jdbc.queryForList("SELECT * FROM ticket_customer_message_confirmations ORDER BY id")).isEqualTo(stored);
            assertThat(jdbc.queryForObject("SELECT assignment_version FROM ticket_customer_message_confirmations",Long.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT actor_user_id FROM ticket_customer_message_confirmations",Long.class)).isEqualTo(a);
            assertThat(jdbc.queryForObject("SELECT through_message_id FROM ticket_customer_message_confirmations",Long.class)).isEqualTo(inquiry);
            var current=confirmations.read(ticket,IdentityFixtures.actor(B));
            assertThat(current.assignmentVersion()).isEqualTo(2);assertThat(current.effectiveThroughMessageId()).isNull();assertThat(current.needsConfirmation()).isTrue();
            assertThat(snapshot()).isEqualTo(before);assertThat(ticketFields()).isEqualTo(ticketBefore);
            assertThat(jdbc.queryForList("SELECT * FROM ticket_assignment_events WHERE resulting_version=1 ORDER BY id")).isEqualTo(eventBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_assignment_events",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT * FROM support_service_memberships WHERE user_id<>? ORDER BY service_id,user_id",a)).isEqualTo(otherMembersBefore);
            assertThat(jdbc.queryForObject("SELECT active FROM support_service_memberships WHERE service_id=? AND user_id=?",Boolean.class,service,a)).isFalse();
            verifyNoInteractions(provider);
            for(String file:List.of("historical-confirmation-current-handoff.png","revoked-confirmation-read.png"))assertThat(Files.exists(run.resolve(file))).isTrue();
            System.out.println("LOCAL_CONFIRMATION_AUTHORITY_BROWSER_POSTGRES completed=true statuses=201,200,403,404 confirmations=1 assignmentVersion=2 providerCalls=0");
        } finally {if(child.isAlive()){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();}reader.join(2000);}
    }
    private void assertIsolated(){assertThat(jdbc.execute((ConnectionCallback<Boolean>) c->c.getMetaData().getURL().split("\\?",2)[0].equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();}
    private JsonNode evidence(java.io.ByteArrayOutputStream bytes){String line=bytes.toString(java.nio.charset.StandardCharsets.UTF_8).lines().filter(value->value.startsWith("{\"evidence\":")).findFirst().orElse("{}");return mapper.readTree(line);}
    private Map<String,List<Map<String,Object>>> snapshot(){var rows=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:PRESERVED)rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return rows;}
    private List<Map<String,Object>> ticketFields(){return jdbc.queryForList("SELECT id,title,status,requester_user_id,service_id FROM tickets ORDER BY id");}
    @TestConfiguration(proxyBeanMethods=false) static class Users{
        @Bean UserDetailsService authorityBrowserUsers(PasswordEncoder encoder){return new IdentityAwareInMemoryUserDetailsManager(IdentityFixtures.user(User.withUsername(A).password(encoder.encode(SECRET)).roles("USER","AGENT").build()));}
    }
}
