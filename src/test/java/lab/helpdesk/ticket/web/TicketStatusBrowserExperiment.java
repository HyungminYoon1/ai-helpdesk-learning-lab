package lab.helpdesk.ticket.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.identity.*;
import lab.helpdesk.support.TicketAssignmentService;
import lab.helpdesk.ticket.TicketStatusAction;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Opt-in local Browser experiment; controlled fixture writes use transactional services. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1","helpdesk.ai.provider.enabled=false","helpdesk.ai.worker.enabled=false",
        "helpdesk.support.receipt-service-code=synthetic-support","logging.level.root=WARN"})
@ActiveProfiles("postgres")
@Import(TicketStatusBrowserExperiment.Users.class)
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TicketStatusBrowserExperiment {
    static final String A="status-browser-agent-a",B="status-browser-agent-b",C="status-browser-customer";
    static final String SECRET=UUID.randomUUID().toString();
    static final List<String> PRESERVED=List.of("ai_suggestion_jobs","ai_suggestion_attempts","ticket_suggestions","ticket_suggestion_categories","support_service_memberships");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @Autowired TicketAssignmentService assignments;
    @Autowired CustomerMessageConfirmationService confirmations;
    @Autowired TicketStatusService statuses;
    @MockitoBean AiSuggestionProvider provider;
    long ticket,inquiry,reply,a,b,late;
    Map<String,List<Map<String,Object>>> preserved;
    List<Map<String,Object>> originalMessages;
    @BeforeEach void fixture(){
        assertIsolated();a=IdentityFixtures.bind(jdbc,A);b=IdentityFixtures.bind(jdbc,B);IdentityFixtures.bind(jdbc,C);
        var received=receipts.receive("Status Browser synthetic","  preserved status inquiry  ",IdentityFixtures.actor(C));
        ticket=received.ticket().id();
        inquiry=jdbc.queryForObject("SELECT id FROM ticket_messages WHERE ticket_id=?",Long.class,ticket);
        assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(A));
        reply=conversations.reply(ticket,"  preserved official reply  ",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A)).message().id();
        confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(A));
        preserved=snapshot();originalMessages=jdbc.queryForList("SELECT * FROM ticket_messages ORDER BY id");
    }
    @Test void status_ui_preserves_explicit_ranges_and_replays_lost_commits_without_overwriting_current_state() throws Exception {
        Path artifacts=Path.of("output","playwright").toAbsolutePath();Files.createDirectories(artifacts);
        Path run=Files.createTempDirectory(artifacts,"week12-ticket-status-");
        var bridge=credentialBridge();
        var builder=new ProcessBuilder(System.getProperty("helpdesk.browser.pwsh","pwsh.exe"),"-NoProfile","-NonInteractive","-File",
                Path.of("scripts","Verify-Week12TicketStatusBrowser.ps1").toAbsolutePath().toString(),
                "-Port",environment.getRequiredProperty("local.server.port"),"-TicketId",Long.toString(ticket),
                "-InquiryId",Long.toString(inquiry),"-ReplyId",Long.toString(reply),"-RunDirectory",run.toString(),
                "-CredentialPort",Integer.toString(bridge.getAddress().getPort()));
        var env=builder.environment();var inherited=new HashMap<>(env);env.clear();
        for(var entry:inherited.entrySet())if(List.of("SystemRoot","WINDIR","PATH","PATHEXT","TEMP","TMP","USERPROFILE","APPDATA","LOCALAPPDATA",
                "ComSpec","ProgramFiles","ProgramFiles(x86)","ProgramW6432","SystemDrive").stream().anyMatch(name->name.equalsIgnoreCase(entry.getKey())))
            env.put(entry.getKey(),entry.getValue());
        env.put("PLAYWRIGHT_CLI_SESSIONS_ROOT",run.resolve("sessions").toString());
        builder.redirectErrorStream(true);Process child;
        try{child=builder.start();bridge.start();}catch(Exception failed){bridge.stop(0);throw failed;}
        var bytes=new java.io.ByteArrayOutputStream();
        Thread reader=Thread.ofVirtual().start(()->{try{child.getInputStream().transferTo(bytes);}catch(Exception ignored){}});
        try{
            awaitGate(run.resolve("draft-selected"),child,bytes);
            assertThat(jdbc.queryForObject("SELECT status_version FROM tickets WHERE id=?",Long.class,ticket)).isEqualTo(1);
            late=conversations.followUp(ticket,"  new detail after resolution selection  ",UUID.randomUUID(),IdentityFixtures.actor(C)).message().id();
            confirmations.confirm(ticket,UUID.randomUUID(),1,late,IdentityFixtures.actor(A));
            Files.writeString(run.resolve("new-message-committed"),Long.toString(late),StandardCharsets.UTF_8);
            awaitGate(run.resolve("unknown-resolve-ready"),child,bytes);
            assertThat(jdbc.queryForObject("SELECT status_version FROM tickets WHERE id=?",Long.class,ticket)).isEqualTo(2);
            statuses.reopenRequester(ticket,UUID.randomUUID(),2,"Synthetic explicit owner reopening.",IdentityFixtures.actor(C));
            assignments.transfer(ticket,UUID.randomUUID(),1,b,"Synthetic handoff.",IdentityFixtures.actor(A));
            assignments.transfer(ticket,UUID.randomUUID(),2,a,"Synthetic return.",IdentityFixtures.actor(B));
            signal(run,"state-changed");
            awaitGate(run.resolve("staff-replayed-ready"),child,bytes);
            confirmations.confirm(ticket,UUID.randomUUID(),3,late,IdentityFixtures.actor(A));
            statuses.changeStaff(ticket,new TicketStatusService.Command(UUID.randomUUID(),TicketStatusAction.RESOLVE,4,3L,
                    "Synthetic second resolution.",reply,late),IdentityFixtures.actor(A));
            signal(run,"second-resolution-ready");
            assertThat(child.waitFor(Duration.ofMinutes(2).toMillis(),TimeUnit.MILLISECONDS)).isTrue();reader.join(2000);
            var result=evidence(bytes);
            if(!result.path("completed").asBoolean())throw new AssertionError("Status Browser stage="+result.path("stage").asString("NO_EVIDENCE")+"; code="+result.path("failureCode").asString("NO_EVIDENCE"));
            assertThat(child.exitValue()).isZero();assertThat(result.path("postCount").asInt()).isEqualTo(6);
            assertThat(result.path("upstreamStatuses").toString()).isEqualTo("[201,409,201,200,201,201]");
            for(String flag:List.of("getOnlyRecovery","staleRangeRejected","explicitReset","sameSubmissionReplayed","historySeparateFromCurrent","ownerProjection","csrfHeaderObserved"))
                assertThat(result.path(flag).asBoolean()).as(flag).isTrue();
            assertThat(snapshot()).isEqualTo(preserved);
            assertThat(jdbc.queryForList("SELECT * FROM ticket_messages WHERE id<=? ORDER BY id",reply)).isEqualTo(originalMessages);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_messages",Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id=?",String.class,late)).isEqualTo("  new detail after resolution selection  ");
            assertThat(jdbc.queryForList("SELECT operation FROM ticket_status_events ORDER BY id",String.class)).containsExactly("START","RESOLVE","REOPEN","START","RESOLVE","REOPEN");
            assertThat(jdbc.queryForList("SELECT resulting_status_version FROM ticket_status_events ORDER BY id",Long.class)).containsExactly(1L,2L,3L,4L,5L,6L);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT client_request_id) FROM ticket_status_events",Long.class)).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id=?",String.class,ticket)).isEqualTo("OPEN");
            assertThat(jdbc.queryForObject("SELECT status_version FROM tickets WHERE id=?",Long.class,ticket)).isEqualTo(6);
            assertThat(jdbc.queryForObject("SELECT assignment_version FROM tickets WHERE id=?",Long.class,ticket)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT assignee_user_id FROM tickets WHERE id=?",Long.class,ticket)).isEqualTo(a);
            verifyNoInteractions(provider);
            for(String file:List.of("stale-resolution-rejected.png","unknown-resolution.png","history-and-current.png","customer-reopened.png"))assertThat(Files.exists(run.resolve(file))).isTrue();
            System.out.println("LOCAL_TICKET_STATUS_BROWSER_POSTGRES completed=true statuses=201,409,201,200,201,201 statusEvents=6 statusVersion=6 providerCalls=0");
        }finally{bridge.stop(0);if(child.isAlive()){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();}reader.join(2000);}
    }
    private void signal(Path run,String name)throws Exception{Files.writeString(run.resolve(name),"READY\n",StandardCharsets.UTF_8);}
    private void awaitGate(Path gate,Process child,java.io.ByteArrayOutputStream bytes)throws Exception{
        long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        while(!Files.exists(gate)&&child.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
        if(!Files.exists(gate))throw new AssertionError("Status Browser gate="+gate.getFileName()+"; stage="+evidence(bytes).path("stage").asString("NO_EVIDENCE")+"; code="+evidence(bytes).path("failureCode").asString("NO_EVIDENCE"));
    }
    private void assertIsolated(){assertThat(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getURL().split("\\?",2)[0].equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();}
    private JsonNode evidence(java.io.ByteArrayOutputStream bytes){String line=bytes.toString(StandardCharsets.UTF_8).lines().filter(v->v.startsWith("{\"evidence\":")).findFirst().orElse("{}");return mapper.readTree(line);}
    private Map<String,List<Map<String,Object>>> snapshot(){var rows=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:PRESERVED)rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return rows;}
    // One-use loopback fixture delivery keeps the synthetic password out of CLI code, argv and logs.
    private com.sun.net.httpserver.HttpServer credentialBridge()throws Exception{
        var bridge=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        for(var entry:Map.of("/agent",A,"/customer",C).entrySet()){
            var used=new java.util.concurrent.atomic.AtomicBoolean();
            bridge.createContext(entry.getKey(),exchange->{
                try(exchange){
                    if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress() || !exchange.getRequestMethod().equals("POST")
                            || !exchange.getRequestURI().getPath().equals(entry.getKey()) || !used.compareAndSet(false,true)){
                        exchange.sendResponseHeaders(404,-1);return;
                    }
                    var body=mapper.writeValueAsBytes(Map.of("username",entry.getValue(),"password",SECRET));
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Cache-Control","no-store");
                    exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                }
            });
        }
        return bridge;
    }
    @TestConfiguration(proxyBeanMethods=false) static class Users{
        @Bean UserDetailsService statusBrowserUsers(PasswordEncoder encoder){return new IdentityAwareInMemoryUserDetailsManager(
                IdentityFixtures.user(User.withUsername(A).password(encoder.encode(SECRET)).roles("USER","AGENT").build()),
                IdentityFixtures.user(User.withUsername(C).password(encoder.encode(SECRET)).roles("USER").build()));}
    }
}
