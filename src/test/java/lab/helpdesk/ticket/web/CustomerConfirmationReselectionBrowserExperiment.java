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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Explicit CLI experiment. Handoff fixture changes use the real application service and isolated DB. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","helpdesk.ai.provider.enabled=false","helpdesk.ai.worker.enabled=false",
        "helpdesk.support.receipt-service-code=synthetic-support","logging.level.root=WARN"})
@ActiveProfiles("postgres")
@Import(CustomerConfirmationReselectionBrowserExperiment.Users.class)
@Testcontainers
class CustomerConfirmationReselectionBrowserExperiment {
    static final String A="reselection-agent-a",B="reselection-agent-b",C="reselection-customer";
    static final String SECRET=UUID.randomUUID().toString();
    static final String LATE_BODY="  customer detail after selection  ";
    static final List<String> PRESERVED=List.of("ai_suggestion_jobs","ai_suggestion_attempts","ticket_suggestions","ticket_suggestion_categories","support_service_memberships");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketAssignmentService assignments;
    @Autowired CustomerMessageConfirmationService confirmations;
    @Autowired TicketConversationService conversations;
    @MockitoBean AiSuggestionProvider provider;
    long ticket,inquiry,a,b,customer;
    Map<String,List<Map<String,Object>>> before;
    List<Map<String,Object>> ticketBefore,eventBefore,originalMessages;
    @BeforeEach void fixture(){
        assertIsolated();a=IdentityFixtures.bind(jdbc,A);b=IdentityFixtures.bind(jdbc,B);customer=IdentityFixtures.bind(jdbc,C);
        var received=receipts.receive("Confirmation reselection synthetic","  preserved original inquiry  ",IdentityFixtures.actor(C));
        ticket=received.ticket().id();inquiry=received.messageId();assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(A));
        before=snapshot();ticketBefore=ticketFields();eventBefore=jdbc.queryForList("SELECT * FROM ticket_assignment_events ORDER BY id");
        originalMessages=jdbc.queryForList("SELECT * FROM ticket_messages ORDER BY id");
    }
    @Test void stale_selection_requires_explicit_refresh_and_reselection_without_reusing_its_submission() throws Exception {
        Path artifacts=Path.of("output","playwright").toAbsolutePath();Files.createDirectories(artifacts);
        Path run=Files.createTempDirectory(artifacts,"week12-confirmation-reselection-");
        var builder=new ProcessBuilder(System.getProperty("helpdesk.browser.pwsh","pwsh.exe"),"-NoProfile","-NonInteractive","-File",
                Path.of("scripts","Verify-Week12ConfirmationReselectionBrowser.ps1").toAbsolutePath().toString(),
                "-Port",environment.getRequiredProperty("local.server.port"),"-TicketId",Long.toString(ticket),
                "-InquiryId",Long.toString(inquiry),"-RunDirectory",run.toString());
        var childEnvironment=builder.environment();var original=new HashMap<>(childEnvironment);childEnvironment.clear();
        for(var entry:original.entrySet())if(List.of("SystemRoot","WINDIR","PATH","PATHEXT","TEMP","TMP","USERPROFILE","APPDATA","LOCALAPPDATA",
                "ComSpec","ProgramFiles","ProgramFiles(x86)","ProgramW6432","SystemDrive").stream().anyMatch(name->name.equalsIgnoreCase(entry.getKey())))
            childEnvironment.put(entry.getKey(),entry.getValue());
        childEnvironment.put("PLAYWRIGHT_CLI_SESSIONS_ROOT",run.resolve("sessions").toString());
        childEnvironment.put("HELPDESK_BROWSER_AGENT_NAME",A);childEnvironment.put("HELPDESK_BROWSER_SECRET",SECRET);
        builder.redirectErrorStream(true);Process child=builder.start();var bytes=new java.io.ByteArrayOutputStream();
        Thread reader=Thread.ofVirtual().start(()->{try{child.getInputStream().transferTo(bytes);}catch(Exception ignored){}});
        try {
            awaitGate(run.resolve("selection-ready"),child,bytes);
            assertIsolated();
            assignments.transfer(ticket,UUID.randomUUID(),1,b,"Synthetic handoff to B.",IdentityFixtures.actor(A));
            assignments.transfer(ticket,UUID.randomUUID(),2,a,"Synthetic return to A.",IdentityFixtures.actor(B));
            Files.writeString(run.resolve("assignment-updated"),"READY\n",java.nio.charset.StandardCharsets.UTF_8);
            awaitGate(run.resolve("fresh-target-selected"),child,bytes);
            assertIsolated();
            long latest=conversations.followUp(ticket,LATE_BODY,UUID.randomUUID(),IdentityFixtures.actor(C)).message().id();
            assertThat(latest).isGreaterThan(inquiry);
            Files.writeString(run.resolve("customer-message-committed"),Long.toString(latest),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(child.waitFor(Duration.ofMinutes(2).toMillis(),TimeUnit.MILLISECONDS)).isTrue();reader.join(2000);
            var result=evidence(bytes);
            if(!result.path("completed").asBoolean())throw new AssertionError("Reselection Browser stage="+result.path("stage").asString("NO_EVIDENCE"));
            assertThat(child.exitValue()).isZero();assertThat(result.path("postCount").asInt()).isEqualTo(2);
            assertThat(result.path("statuses").toString()).isEqualTo("[409,201]");
            for(String flag:List.of("oldVersionRetained","uuidChanged","resetReadOnly","emptySelectionRequired","csrfHeaderObserved",
                    "lateMessageStillNeedsConfirmation","newTargetNotAutoSelected"))
                assertThat(result.path(flag).asBoolean()).as(flag).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_customer_message_confirmations",Long.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT assignment_version FROM ticket_customer_message_confirmations",Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT through_message_id FROM ticket_customer_message_confirmations",Long.class)).isEqualTo(inquiry);
            assertThat(jdbc.queryForObject("SELECT actor_user_id FROM ticket_customer_message_confirmations",Long.class)).isEqualTo(a);
            var state=confirmations.read(ticket,IdentityFixtures.actor(A));assertThat(state.assignmentVersion()).isEqualTo(3);
            assertThat(state.effectiveThroughMessageId()).isEqualTo(inquiry);assertThat(state.latestCustomerMessageId()).isEqualTo(latest);
            assertThat(state.needsConfirmation()).isTrue();
            assertThat(jdbc.queryForList("SELECT * FROM ticket_messages WHERE id<=? ORDER BY id",inquiry)).isEqualTo(originalMessages);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE id>?",Long.class,inquiry)).isOne();
            var added=jdbc.queryForMap("SELECT ticket_id,body,author_user_id,message_type,corrects_message_id,reply_assignment_version FROM ticket_messages WHERE id=?",latest);
            assertThat(added.get("ticket_id")).isEqualTo(ticket);assertThat(added.get("body")).isEqualTo(LATE_BODY);
            assertThat(added.get("author_user_id")).isEqualTo(customer);assertThat(added.get("message_type")).isEqualTo("CUSTOMER_FOLLOWUP");
            assertThat(added.get("corrects_message_id")).isNull();assertThat(added.get("reply_assignment_version")).isNull();
            assertThat(snapshot()).isEqualTo(before);assertThat(ticketFields()).isEqualTo(ticketBefore);
            assertThat(jdbc.queryForObject("SELECT assignee_user_id FROM tickets WHERE id=?",Long.class,ticket)).isEqualTo(a);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_assignment_events",Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT * FROM ticket_assignment_events WHERE resulting_version=1 ORDER BY id")).isEqualTo(eventBefore);
            verifyNoInteractions(provider);
            for(String file:List.of("rejected-confirmation-selection.png","empty-confirmation-reselection.png","new-assignment-confirmation.png","new-customer-after-selection.png"))assertThat(Files.exists(run.resolve(file))).isTrue();
            System.out.println("LOCAL_CONFIRMATION_RESELECTION_BROWSER_POSTGRES completed=true statuses=409,201 httpPosts=2 confirmations=1 newCustomerMessages=1 needsConfirmation=true assignmentVersion=3 providerCalls=0");
        }finally{if(child.isAlive()){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();}reader.join(2000);}
    }
    private void awaitGate(Path gate,Process child,java.io.ByteArrayOutputStream bytes) throws Exception {long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();while(!Files.exists(gate) && child.isAlive() && System.nanoTime()<deadline)Thread.sleep(50);if(!Files.exists(gate))throw new AssertionError("Browser did not reach selection gate; stage="+evidence(bytes).path("stage").asString("NO_EVIDENCE"));}
    private void assertIsolated(){assertThat(jdbc.execute((ConnectionCallback<Boolean>) c->c.getMetaData().getURL().split("\\?",2)[0].equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();}
    private JsonNode evidence(java.io.ByteArrayOutputStream bytes){String line=bytes.toString(java.nio.charset.StandardCharsets.UTF_8).lines().filter(value->value.startsWith("{\"evidence\":")).findFirst().orElse("{}");return mapper.readTree(line);}
    private Map<String,List<Map<String,Object>>> snapshot(){var rows=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:PRESERVED)rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return rows;}
    private List<Map<String,Object>> ticketFields(){return jdbc.queryForList("SELECT id,title,status,requester_user_id,service_id FROM tickets ORDER BY id");}
    @TestConfiguration(proxyBeanMethods=false) static class Users{
        @Bean UserDetailsService reselectionBrowserUsers(PasswordEncoder encoder){return new IdentityAwareInMemoryUserDetailsManager(IdentityFixtures.user(User.withUsername(A).password(encoder.encode(SECRET)).roles("USER","AGENT").build()));}
    }
}
