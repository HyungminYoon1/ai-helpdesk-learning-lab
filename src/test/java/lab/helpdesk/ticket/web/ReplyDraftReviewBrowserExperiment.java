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

/** Explicit local CLI experiment; fixture handoffs run through the existing transactional service. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","helpdesk.ai.provider.enabled=false","helpdesk.ai.worker.enabled=false",
        "helpdesk.support.receipt-service-code=synthetic-support","logging.level.root=WARN"})
@ActiveProfiles("postgres")
@Import(ReplyDraftReviewBrowserExperiment.Users.class)
@Testcontainers
class ReplyDraftReviewBrowserExperiment {
    static final String A="draft-review-agent-a",B="draft-review-agent-b",C="draft-review-customer";
    static final String SECRET=UUID.randomUUID().toString();
    static final List<String> PRESERVED=List.of("ai_suggestion_jobs","ai_suggestion_attempts","ticket_suggestions","ticket_suggestion_categories","support_service_memberships");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @Autowired TicketAssignmentService assignments;
    @MockitoBean AiSuggestionProvider provider;
    long ticket,originalReply,a,b;
    Map<String,List<Map<String,Object>>> before;
    List<Map<String,Object>> originalMessages,ticketBefore,eventBefore;
    @BeforeEach void fixture(){
        assertIsolated();a=IdentityFixtures.bind(jdbc,A);b=IdentityFixtures.bind(jdbc,B);IdentityFixtures.bind(jdbc,C);
        var received=receipts.receive("Reply draft review synthetic","  preserved original inquiry  ",IdentityFixtures.actor(C));
        ticket=received.ticket().id();assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(A));
        originalReply=conversations.reply(ticket,"  preserved original reply  ",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A)).message().id();
        before=snapshot();originalMessages=jdbc.queryForList("SELECT * FROM ticket_messages ORDER BY id");
        ticketBefore=ticketFields();eventBefore=jdbc.queryForList("SELECT * FROM ticket_assignment_events ORDER BY id");
    }
    @Test void explicit_review_replaces_only_rejected_drafts_and_unknown_corrections_keep_their_submission() throws Exception {
        Path artifacts=Path.of("output","playwright").toAbsolutePath();Files.createDirectories(artifacts);
        Path run=Files.createTempDirectory(artifacts,"week12-reply-draft-review-");
        var builder=new ProcessBuilder(System.getProperty("helpdesk.browser.pwsh","pwsh.exe"),"-NoProfile","-NonInteractive","-File",
                Path.of("scripts","Verify-Week12ReplyDraftReviewBrowser.ps1").toAbsolutePath().toString(),
                "-Port",environment.getRequiredProperty("local.server.port"),"-TicketId",Long.toString(ticket),
                "-OriginalReplyId",Long.toString(originalReply),"-RunDirectory",run.toString());
        var childEnvironment=builder.environment();var original=new HashMap<>(childEnvironment);childEnvironment.clear();
        for(var entry:original.entrySet())if(List.of("SystemRoot","WINDIR","PATH","PATHEXT","TEMP","TMP","USERPROFILE","APPDATA","LOCALAPPDATA",
                "ComSpec","ProgramFiles","ProgramFiles(x86)","ProgramW6432","SystemDrive").stream().anyMatch(name->name.equalsIgnoreCase(entry.getKey())))
            childEnvironment.put(entry.getKey(),entry.getValue());
        childEnvironment.put("PLAYWRIGHT_CLI_SESSIONS_ROOT",run.resolve("sessions").toString());
        childEnvironment.put("HELPDESK_BROWSER_AGENT_NAME",A);childEnvironment.put("HELPDESK_BROWSER_SECRET",SECRET);
        builder.redirectErrorStream(true);Process child=builder.start();var bytes=new java.io.ByteArrayOutputStream();
        Thread reader=Thread.ofVirtual().start(()->{try{child.getInputStream().transferTo(bytes);}catch(Exception ignored){}});
        try {
            awaitGate(run.resolve("draft-ready"),child,bytes);
            handoffRoundTrip(1);Files.writeString(run.resolve("assignment-3-committed"),"READY\n",java.nio.charset.StandardCharsets.UTF_8);
            awaitGate(run.resolve("unknown-reply-ready"),child,bytes);
            handoffRoundTrip(3);Files.writeString(run.resolve("assignment-5-committed"),"READY\n",java.nio.charset.StandardCharsets.UTF_8);
            assertThat(child.waitFor(Duration.ofMinutes(2).toMillis(),TimeUnit.MILLISECONDS)).isTrue();reader.join(2000);
            var result=evidence(bytes);
            if(!result.path("completed").asBoolean())throw new AssertionError("Reply review Browser stage="+result.path("stage").asString("NO_EVIDENCE"));
            assertThat(child.exitValue()).isZero();assertThat(result.path("postCount").asInt()).isEqualTo(4);
            assertThat(result.path("upstreamStatuses").toString()).isEqualTo("[409,201,201,200]");
            for(String flag:List.of("staleRejected","explicitBlankDraft","newUuid","csrfHeaderObserved","originalSubmissionReplayed","correctionPreserved"))
                assertThat(result.path(flag).asBoolean()).as(flag).isTrue();
            assertThat(jdbc.queryForList("SELECT * FROM ticket_messages WHERE id<=? ORDER BY id",originalReply)).isEqualTo(originalMessages);
            var replies=jdbc.queryForList("SELECT body,author_user_id,message_type,reply_assignment_version,corrects_message_id FROM ticket_messages WHERE id>? ORDER BY id",originalReply);
            assertThat(replies).hasSize(2);
            assertThat(replies.get(0).get("body")).isEqualTo("Synthetic reviewed reply.");assertThat(replies.get(0).get("corrects_message_id")).isNull();
            assertThat(replies.get(1).get("body")).isEqualTo("Synthetic saved correction.");assertThat(replies.get(1).get("corrects_message_id")).isEqualTo(originalReply);
            for(var reply:replies){assertThat(reply.get("author_user_id")).isEqualTo(a);assertThat(reply.get("reply_assignment_version")).isEqualTo(3L);assertThat(reply.get("message_type")).isEqualTo("OFFICIAL_REPLY");}
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT client_request_id) FROM ticket_messages WHERE id>?",Long.class,originalReply)).isEqualTo(2);
            assertThat(snapshot()).isEqualTo(before);assertThat(ticketFields()).isEqualTo(ticketBefore);
            assertThat(jdbc.queryForObject("SELECT assignment_version FROM tickets WHERE id=?",Long.class,ticket)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT assignee_user_id FROM tickets WHERE id=?",Long.class,ticket)).isEqualTo(a);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_assignment_events",Long.class)).isEqualTo(5);
            assertThat(jdbc.queryForList("SELECT * FROM ticket_assignment_events WHERE resulting_version=1 ORDER BY id")).isEqualTo(eventBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_customer_message_confirmations",Long.class)).isZero();verifyNoInteractions(provider);
            for(String file:List.of("rejected-reply-draft.png","empty-reviewed-reply-draft.png","unknown-reply-after-reassignment.png","reply-reconfirmed-without-duplicate.png"))assertThat(Files.exists(run.resolve(file))).isTrue();
            System.out.println("LOCAL_REPLY_DRAFT_REVIEW_BROWSER_POSTGRES completed=true statuses=409,201,201,200 newMessages=2 assignmentVersion=5 providerCalls=0");
        }finally{if(child.isAlive()){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();}reader.join(2000);}
    }
    private void handoffRoundTrip(long version){assertIsolated();assignments.transfer(ticket,UUID.randomUUID(),version,b,"Synthetic handoff to B.",IdentityFixtures.actor(A));assignments.transfer(ticket,UUID.randomUUID(),version+1,a,"Synthetic return to A.",IdentityFixtures.actor(B));}
    private void awaitGate(Path gate,Process child,java.io.ByteArrayOutputStream bytes) throws Exception {long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();while(!Files.exists(gate) && child.isAlive() && System.nanoTime()<deadline)Thread.sleep(50);if(!Files.exists(gate))throw new AssertionError("Browser did not reach reply gate; stage="+evidence(bytes).path("stage").asString("NO_EVIDENCE")+"; code="+evidence(bytes).path("failureCode").asString("NO_EVIDENCE"));}
    private void assertIsolated(){assertThat(jdbc.execute((ConnectionCallback<Boolean>) c->c.getMetaData().getURL().split("\\?",2)[0].equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();}
    private JsonNode evidence(java.io.ByteArrayOutputStream bytes){String line=bytes.toString(java.nio.charset.StandardCharsets.UTF_8).lines().filter(value->value.startsWith("{\"evidence\":")).findFirst().orElse("{}");return mapper.readTree(line);}
    private Map<String,List<Map<String,Object>>> snapshot(){var rows=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:PRESERVED)rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return rows;}
    private List<Map<String,Object>> ticketFields(){return jdbc.queryForList("SELECT id,title,status,requester_user_id,service_id FROM tickets ORDER BY id");}
    @TestConfiguration(proxyBeanMethods=false) static class Users{
        @Bean UserDetailsService replyReviewBrowserUsers(PasswordEncoder encoder){return new IdentityAwareInMemoryUserDetailsManager(IdentityFixtures.user(User.withUsername(A).password(encoder.encode(SECRET)).roles("USER","AGENT").build()));}
    }
}
