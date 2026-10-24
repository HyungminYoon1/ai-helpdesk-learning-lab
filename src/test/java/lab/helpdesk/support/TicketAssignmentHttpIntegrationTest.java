package lab.helpdesk.support;

import java.util.*;
import java.util.concurrent.*;
import lab.helpdesk.identity.*;
import lab.helpdesk.ticket.application.*;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"helpdesk.ai.worker.enabled=false","helpdesk.ai.provider.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("postgres")
@WithIdentityFixtures
@Testcontainers
class TicketAssignmentHttpIntegrationTest {
    static final String A="assignment-agent-a",B="assignment-agent-b",S="assignment-agent-supervisor",C="assignment-customer",OUT="other-agent";
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @Autowired TicketAssignmentService assignments;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean JdbcSupportRepository repository;
    @MockitoBean AiSuggestionProvider provider;
    long ticket,service,a,b,s,other;
    List<List<Map<String,Object>>> original;

    @BeforeEach void setup() {
        assertThat(POSTGRES.isRunning()).isTrue();
        for(String table:List.of("ticket_suggestion_categories","ticket_suggestions","ai_suggestion_attempts","ai_suggestion_jobs",
                "ticket_messages","ticket_assignment_events","tickets")) jdbc.update("DELETE FROM "+table);
        for(String name:List.of(A,B,S,C,OUT)) IdentityFixtures.bind(jdbc,name);
        service=repository.service("synthetic-support").orElseThrow().id();
        a=id(A);b=id(B);s=id(S);
        jdbc.update("UPDATE support_service_memberships SET active=true,support_role='AGENT' WHERE service_id=?",service);
        jdbc.update("UPDATE support_service_memberships SET support_role='SUPERVISOR' WHERE service_id=? AND user_id=?",service,s);
        other=repository.registerService("other-service","Other service");
        repository.registerMember(other,id(OUT),"Other","SUPERVISOR");
        jdbc.update("DELETE FROM support_service_memberships WHERE service_id=? AND user_id=?",service,id(OUT));
        ticket=receipts.receive("original title","  unchanged original inquiry  ",IdentityFixtures.actor(C)).ticket().id();
        original=preserved();clearInvocations(provider,repository);
    }
    @AfterEach void originalAndAiPreserved() {
        assertThat(preserved()).isEqualTo(original);
        verifyNoInteractions(provider);
    }
    @Test void claim_replay_transfer_and_old_replay_return_event_separately_from_current_assignment() throws Exception {
        UUID key=UUID.randomUUID();
        var first=claim(A,key,0,201);
        assertThat(first.path("assignment").path("assigneeAgentId").asLong()).isEqualTo(a);
        claim(A,key,0,200);
        claim(A,key,1,409);
        claim(A,UUID.randomUUID(),1,409);
        transfer(A,UUID.randomUUID(),1,b,"  original reason 😀  ",201);
        var replay=claim(A,key,0,200);
        assertThat(replay.path("event").path("toAgentId").asLong()).isEqualTo(a);
        assertThat(replay.path("assignment").path("assigneeAgentId").asLong()).isEqualTo(b);
        assertThat(replay.path("assignment").path("version").asLong()).isEqualTo(2);
        mvc.perform(get("/api/tickets/{id}/assignment-events?limit=1",ticket).with(as(A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].type").value("CLAIM"));
        assertThat(events()).isEqualTo(2);
    }
    @Test void only_owner_replies_and_supervisor_must_explicitly_transfer_to_self() throws Exception {
        claim(A,UUID.randomUUID(),0,201);
        reply(S,1L,UUID.randomUUID(),403);
        transfer(B,UUID.randomUUID(),1,b,"take over",403);
        transfer(S,UUID.randomUUID(),1,s,"supervisor takeover",201);
        reply(S,2L,UUID.randomUUID(),201);
        mvc.perform(get("/api/tickets/{id}/messages",ticket).with(as(A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.canReply").value(false))
                .andExpect(jsonPath("$.capabilities.expectedAssignmentVersion").isEmpty());
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id=?",String.class,ticket)).isEqualTo("OPEN");
    }
    @Test void a_to_b_to_a_does_not_revive_old_draft_but_saved_reply_can_be_reconfirmed() throws Exception {
        claim(A,UUID.randomUUID(),0,201);
        UUID submission=UUID.randomUUID();reply(A,1L,submission,201);
        transfer(A,UUID.randomUUID(),1,b,"handoff",201);
        reply(A,1L,submission,200);
        reply(A,1L,UUID.randomUUID(),403);
        transfer(B,UUID.randomUUID(),2,a,"return",201);
        reply(A,1L,UUID.randomUUID(),409);
        reply(A,3L,submission,409);
        reply(A,null,submission,400);
        reply(A,3L,UUID.randomUUID(),201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE message_type='OFFICIAL_REPLY'",Long.class)).isEqualTo(2);
    }
    @Test void role_csrf_scope_and_all_existing_read_paths_are_protected() throws Exception {
        mvc.perform(get("/api/services")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/services").with(as(C))).andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/{id}/assignment/claim",ticket).with(as(A)).contentType(MediaType.APPLICATION_JSON)
                .content(payload(UUID.randomUUID(),0))).andExpect(status().isForbidden());
        for(String suffix:List.of("","/messages","/ai-suggestion","/assignment","/assignment-events")) {
            mvc.perform(get("/api/tickets/"+ticket+suffix).with(as(OUT))).andExpect(status().isNotFound());
            mvc.perform(head("/api/tickets/"+ticket+suffix).with(as(OUT))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/services/{id}/agents",other).with(as(A))).andExpect(status().isNotFound());
        mvc.perform(get("/api/services/{id}/assignment-queue",service).with(as(A))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.items[0].id").value(ticket));
        mvc.perform(get("/api/services/{id}/agents",service).with(as(A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].displayName").exists()).andExpect(jsonPath("$.items[0].username").doesNotExist());
        assertThat(events()).isZero();
    }
    @Test void revocation_blocks_old_session_new_reads_writes_and_replays_without_erasing_assignment() throws Exception {
        UUID key=UUID.randomUUID();claim(A,key,0,201);
        var sameSession=as(A);
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,a);
        for(String suffix:List.of("","/messages","/ai-suggestion","/assignment","/assignment-events"))
            mvc.perform(get("/api/tickets/"+ticket+suffix).with(sameSession)).andExpect(status().isNotFound());
        claim(A,key,0,404);reply(A,1L,UUID.randomUUID(),404);
        assertThat(assignments.read(ticket,IdentityFixtures.actor(S)).assigneeAgentId()).isEqualTo(a);
        transfer(S,UUID.randomUUID(),1,b,"revoked agent work",201);
        mvc.perform(get("/api/tickets/{id}/messages",ticket).with(as(C))).andExpect(status().isOk());
        assertThat(events()).isEqualTo(2);
    }
    @Test void revoked_staff_still_reads_own_customer_conversation_but_not_staff_endpoints() throws Exception {
        jdbc.update("UPDATE tickets SET requester_user_id=? WHERE id=?",a,ticket);
        original=preserved();
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,a);
        mvc.perform(get("/api/tickets/{id}/messages",ticket).with(as(A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.canReply").value(false));
        mvc.perform(get("/api/tickets/{id}",ticket).with(as(A))).andExpect(status().isNotFound());
    }
    @Test void invalid_targets_and_transfer_replay_changes_do_not_mutate_history() throws Exception {
        transfer(S,UUID.randomUUID(),0,b,"first assign not transfer",409);
        claim(A,UUID.randomUUID(),0,201);
        transfer(A,UUID.randomUUID(),1,a,"self",400);
        transfer(A,UUID.randomUUID(),1,id(OUT),"cross service",400);
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,b);
        transfer(A,UUID.randomUUID(),1,b,"inactive",400);
        jdbc.update("UPDATE support_service_memberships SET active=true WHERE service_id=? AND user_id=?",service,b);
        UUID key=UUID.randomUUID();transfer(A,key,1,b,"reason",201);
        transfer(A,key,1,b,"reason",200);transfer(A,key,1,b,"changed",409);
        claim(A,key,1,409);
        assertThat(events()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings={"{}","[]",
            "{\"clientRequestId\":\"bad\",\"expectedAssignmentVersion\":0}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":\"0\"}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":-1}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":0,\"actorId\":1}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":0.0}"})
    void strict_claim_inputs_are_rejected(String json) throws Exception {
        mvc.perform(post("/api/tickets/{id}/assignment/claim",ticket).with(as(A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        assertThat(events()).isZero();
    }
    @Test void simultaneous_claims_commit_exactly_one_assignment() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(() -> raceClaim(A,start));var two=pool.submit(() -> raceClaim(B,start));start.countDown();
            assertThat(List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED","ASSIGNMENT_VERSION_CONFLICT");
        }
        assertThat(events()).isOne();assertThat(assignments.read(ticket,IdentityFixtures.actor(S)).version()).isEqualTo(1);
    }
    @Test void rollback_of_first_claim_allows_waiting_second_claim() throws Exception {
        var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(invocation -> {
            Object value=invocation.callRealMethod();
            if (invocation.<Long>getArgument(1)==a) { inserted.countDown();await(release);throw new IllegalStateException("controlled assignment failure"); }
            return value;
        }).when(repository).append(any(),anyLong(),any(),anyString(),anyLong(),nullable(String.class));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> assertThatThrownBy(() -> assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(A))).isInstanceOf(IllegalStateException.class));
            await(inserted);
            var second=pool.submit(() -> assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(B)));
            release.countDown();first.get(10,TimeUnit.SECONDS);assertThat(second.get(10,TimeUnit.SECONDS).created()).isTrue();
        } finally { release.countDown(); }
        assertThat(assignments.read(ticket,IdentityFixtures.actor(S)).assigneeAgentId()).isEqualTo(b);assertThat(events()).isOne();
    }
    @Test void commit_failure_rolls_back_assignment_and_event_without_201() throws Exception {
        jdbc.execute("CREATE TABLE assignment_commit_guard (id BIGINT REFERENCES tickets(id) DEFERRABLE INITIALLY DEFERRED)");
        jdbc.execute("CREATE FUNCTION assignment_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO assignment_commit_guard VALUES (9223372036854775807); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER assignment_commit_failure AFTER INSERT ON ticket_assignment_events FOR EACH ROW EXECUTE FUNCTION assignment_commit_failure()");
        try { claim(A,UUID.randomUUID(),0,500);assertThat(events()).isZero();assertThat(assignments.read(ticket,IdentityFixtures.actor(A)).version()).isZero(); }
        finally { jdbc.execute("DROP TRIGGER assignment_commit_failure ON ticket_assignment_events");jdbc.execute("DROP FUNCTION assignment_commit_failure()");jdbc.execute("DROP TABLE assignment_commit_guard"); }
    }
    @Test void reply_commits_before_waiting_transfer_and_is_preserved() throws Exception {
        claim(A,UUID.randomUUID(),0,201);
        var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                conversations.reply(ticket,"committed before handoff",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A));
                inserted.countDown();await(release);
            }));
            await(inserted);
            var second=pool.submit(() -> assignments.transfer(ticket,UUID.randomUUID(),1,b,"handoff",IdentityFixtures.actor(A)));
            try { awaitDatabaseWaiter(); } finally { release.countDown(); }
            first.get(10,TimeUnit.SECONDS);assertThat(second.get(10,TimeUnit.SECONDS).created()).isTrue();
        } finally { release.countDown(); }
        reply(A,1L,UUID.randomUUID(),403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE message_type='OFFICIAL_REPLY'",Long.class)).isOne();
    }
    @Test void transfer_commits_before_waiting_old_owner_reply_which_is_rejected() throws Exception {
        claim(A,UUID.randomUUID(),0,201);
        var changed=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                assignments.transfer(ticket,UUID.randomUUID(),1,b,"handoff",IdentityFixtures.actor(A));
                changed.countDown();await(release);
            }));
            await(changed);
            var second=pool.submit(() -> conversations.reply(ticket,"not committed",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A)));
            try { awaitDatabaseWaiter(); } finally { release.countDown(); }
            first.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(SupportException.class);
        } finally { release.countDown(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE message_type='OFFICIAL_REPLY'",Long.class)).isZero();
    }
    @Test void transfer_rejects_blank_or_overlong_original_reason_without_storage() throws Exception {
        claim(A,UUID.randomUUID(),0,201);
        transfer(A,UUID.randomUUID(),1,b,"  ",400);
        transfer(A,UUID.randomUUID(),1,b,"😀".repeat(501),400);
        transfer(A,UUID.randomUUID(),1,b,"😀".repeat(500),201);
    }
    @Test void legacy_reply_replay_requires_null_version_and_unmapped_staff_access_is_closed() throws Exception {
        UUID key=UUID.randomUUID();
        jdbc.update("INSERT INTO ticket_messages(ticket_id,body,author_username,author_user_id,message_type,client_request_id) VALUES (?,'reply',?,?, 'OFFICIAL_REPLY',?)",ticket,A,a,key);
        reply(A,null,key,200);reply(A,1L,key,409);reply(A,null,UUID.randomUUID(),400);
        jdbc.update("UPDATE tickets SET service_id=NULL WHERE id=?",ticket);
        mvc.perform(get("/api/tickets/{id}/assignment",ticket).with(as(A))).andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/{id}/messages",ticket).with(as(C))).andExpect(status().isOk());
    }
    @Test void membership_update_blocks_new_staff_request_until_revocation_commits() throws Exception {
        var changed=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,a);
                changed.countDown();await(release);
            }));
            await(changed);
            // The same service membership is locked by revocation. The request is not authorized from Session alone.
            var reader=pool.submit(() -> assignments.read(ticket,IdentityFixtures.actor(A)));
            release.countDown();writer.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(() -> reader.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(SupportException.class);
        } finally { release.countDown(); }
    }
    @Test void lock_wait_timeout_returns_safe_503_and_no_assignment() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var writer=pool.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM tickets WHERE id=? FOR UPDATE",ticket);locked.countDown();await(release);
            }));
            await(locked);
            try { claim(A,UUID.randomUUID(),0,503);assertThat(events()).isZero(); }
            finally { release.countDown();writer.get(10,TimeUnit.SECONDS); }
        } finally { release.countDown(); }
    }
    private String raceClaim(String name,CountDownLatch start) {
        await(start);try { assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(name));return "CREATED"; }
        catch(SupportException e) { return e.code().name(); }
    }
    private JsonNode claim(String who,UUID key,long version,int status) throws Exception { return postJson("claim",who,payload(key,version),status); }
    private JsonNode transfer(String who,UUID key,long version,long to,String reason,int status) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("clientRequestId",key);body.put("expectedAssignmentVersion",version);body.put("toAgentId",to);body.put("reason",reason);
        return postJson("transfers",who,mapper.writeValueAsString(body),status);
    }
    private JsonNode postJson(String path,String who,String json,int expected) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/tickets/"+ticket+"/assignment/"+path).with(as(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
    private void reply(String who,Long version,UUID key,int expected) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("body","reply");body.put("clientRequestId",key);body.put("expectedAssignmentVersion",version);
        mvc.perform(post("/api/tickets/{id}/replies",ticket).with(as(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().is(expected));
    }
    private String payload(UUID key,long version) { return mapper.writeValueAsString(Map.of("clientRequestId",key,"expectedAssignmentVersion",version)); }
    private org.springframework.test.web.servlet.request.RequestPostProcessor as(String who) {
        return user(IdentityFixtures.user(User.withUsername(who).password("unused-synthetic").roles(who.equals(C)?new String[]{"USER"}:new String[]{"USER","AGENT"}).build()));
    }
    private long id(String name) { return new JdbcUserIdentityRepository(jdbc).findId(IdentityFixtures.key(name)).orElseThrow(); }
    private long events() { return jdbc.queryForObject("SELECT count(*) FROM ticket_assignment_events WHERE ticket_id=?",Long.class,ticket); }
    private List<List<Map<String,Object>>> preserved() {
        return List.of(jdbc.queryForList("SELECT title,status,requester_user_id FROM tickets WHERE id=?",ticket),
                jdbc.queryForList("SELECT * FROM ticket_messages WHERE ticket_id=? AND message_type='INQUIRY'",ticket),
                jdbc.queryForList("SELECT * FROM ai_suggestion_jobs ORDER BY id"),jdbc.queryForList("SELECT * FROM ai_suggestion_attempts ORDER BY job_id,attempt_number"),
                jdbc.queryForList("SELECT * FROM ticket_suggestions ORDER BY id"));
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(8,TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timeout"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("test interrupted"); }
    }
    private void awaitDatabaseWaiter() throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock')",Boolean.class))) return;
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("expected independent PostgreSQL lock waiter");
    }
}
