package lab.helpdesk.ticket.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.identity.*;
import lab.helpdesk.ticket.application.*;
import lab.helpdesk.ticket.repository.JdbcTicketConversationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"helpdesk.ai.provider.enabled=false", "helpdesk.ai.worker.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("postgres")
@WithIdentityFixtures
@Testcontainers
class TicketCustomerFollowupHttpIntegrationTest {
    private static final String A="followup-user-a", B="followup-user-b", AGENT="followup-agent-a", OTHER="followup-agent-b";
    private static final List<String> TABLES=List.of("ticket_suggestion_categories", "ticket_suggestions",
            "ai_suggestion_attempts", "ai_suggestion_jobs", "ticket_messages", "ticket_assignment_events", "tickets");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @Autowired lab.helpdesk.support.TicketAssignmentService assignments;
    @Autowired lab.helpdesk.ai.job.AiSuggestionJobClaimService claims;
    @Autowired lab.helpdesk.ai.suggestion.AiSuggestionResultService results;
    @MockitoSpyBean JdbcTicketConversationRepository repository;
    @MockitoBean AiSuggestionProvider provider;
    long ticket;

    @BeforeEach
    void isolatedDatabase() {
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) c -> c.getMetaData().getURL().split("\\?",2)[0]
                .equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();
        for(String table:TABLES) jdbc.update("DELETE FROM "+table);
        for(String name:List.of(A,B,AGENT,OTHER)) {
            long id=IdentityFixtures.bind(jdbc,name);
            // Test-local reset; production bootstrap deliberately does not reactivate memberships.
            if(name.contains("agent"))jdbc.update("UPDATE support_service_memberships SET active=true WHERE user_id=?",id);
        }
        ticket=receipts.receive("original title", "  original inquiry\nunchanged  ", IdentityFixtures.actor(A)).ticket().id();
        clearInvocations(provider,repository);
    }

    @AfterEach
    void noProviderCalls() { verifyNoInteractions(provider); }

    @ParameterizedTest
    @ValueSource(strings={"OPEN","IN_PROGRESS","RESOLVED"})
    void owner_can_store_original_text_in_every_state_without_assignment_or_ai_changes(String state) throws Exception {
        jdbc.update("UPDATE tickets SET status=? WHERE id=?",state,ticket);
        var originalTicket=jdbc.queryForList("SELECT * FROM tickets WHERE id=?",ticket);
        var originalInquiry=jdbc.queryForList("SELECT * FROM ticket_messages WHERE ticket_id=?",ticket);
        var originalJob=jdbc.queryForList("SELECT * FROM ai_suggestion_jobs");
        String body="  <script>synthetic()</script>\n추가 경과를 확인해주세요.  ";
        JsonNode created=follow(ticket,body,UUID.randomUUID(),A,201);
        assertThat(created.path("type").asString()).isEqualTo("CUSTOMER_FOLLOWUP");
        assertThat(created.path("body").asString()).isEqualTo(body);
        assertThat(created.path("correctsMessageId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT author_user_id FROM ticket_messages WHERE id=?",Long.class,
                created.path("id").asLong())).isEqualTo(new JdbcUserIdentityRepository(jdbc).findId(IdentityFixtures.key(A)).orElseThrow());
        mvc.perform(get("/api/tickets/{id}/messages",ticket).with(as(A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.status").value(state))
                .andExpect(jsonPath("$.messages[1].body").value(body))
                .andExpect(jsonPath("$.messages[1].authorUsername").doesNotExist())
                .andExpect(jsonPath("$.messages[1].authorUserId").doesNotExist());
        mvc.perform(get("/api/tickets/{id}/messages",ticket).with(as(AGENT))).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[1].type").value("CUSTOMER_FOLLOWUP"));
        assertThat(jdbc.queryForList("SELECT * FROM tickets WHERE id=?",ticket)).isEqualTo(originalTicket);
        assertThat(jdbc.queryForList("SELECT * FROM ticket_messages WHERE ticket_id=? AND message_type='INQUIRY'",ticket)).isEqualTo(originalInquiry);
        assertThat(jdbc.queryForList("SELECT * FROM ai_suggestion_jobs")).isEqualTo(originalJob);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_suggestion_attempts",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_suggestions",Long.class)).isZero();
    }

    @Test
    void owner_identity_and_csrf_are_required_even_for_staff() throws Exception {
        var before=snapshot();
        follow(ticket,"denied",UUID.randomUUID(),B,404);
        follow(ticket,"denied",UUID.randomUUID(),AGENT,404);
        mvc.perform(post("/api/tickets/{id}/customer-messages",ticket).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload("denied",UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tickets/{id}/customer-messages",ticket).with(as(A))
                .contentType(MediaType.APPLICATION_JSON).content(payload("denied",UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/{id}/customer-messages",ticket).with(user(A).roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload("denied",UUID.randomUUID())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IDENTITY_UNLINKED"));
        assertThat(snapshot()).isEqualTo(before);
        jdbc.update("UPDATE tickets SET requester_user_id=NULL WHERE id=?",ticket);
        follow(ticket,"denied",UUID.randomUUID(),A,404);
        assertThat(messageCount()).isOne();
    }

    @Test
    void exact_replay_is_200_changed_body_is_409_and_new_uuid_is_a_new_submission() throws Exception {
        UUID request=UUID.randomUUID();
        long first=follow(ticket,"same",request,A,201).path("id").asLong();
        assertThat(follow(ticket,"same",request,A,200).path("id").asLong()).isEqualTo(first);
        assertThat(follow(ticket," same",request,A,409).path("code").asString()).isEqualTo("FOLLOWUP_REQUEST_CONFLICT");
        assertThat(messageCount()).isEqualTo(2);
        assertThat(follow(ticket,"same",UUID.randomUUID(),A,201).path("id").asLong()).isGreaterThan(first);
        assertThat(messageCount()).isEqualTo(3);
    }

    @Test
    void shared_uuid_rejects_different_message_kinds_in_both_directions() throws Exception {
        long own=receipts.receive("staff own inquiry","original",IdentityFixtures.actor(AGENT)).ticket().id();
        assignments.claim(own,UUID.randomUUID(),0,IdentityFixtures.actor(AGENT));
        UUID official=UUID.randomUUID();
        conversations.reply(own,"same",official,null,1L,IdentityFixtures.actor(AGENT));
        follow(own,"same",official,AGENT,409);
        UUID customer=UUID.randomUUID();
        follow(own,"same",customer,AGENT,201);
        // Missing expected version previously made a customer result resemble a legacy reply replay.
        mvc.perform(post("/api/tickets/{id}/replies",own).with(as(AGENT)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload("same",customer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPLY_REQUEST_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_messages WHERE ticket_id=?",Long.class,own)).isEqualTo(3);
    }

    @Test
    void staff_owner_can_follow_up_after_handoff_and_staff_membership_revocation() throws Exception {
        long own=receipts.receive("staff own inquiry","original",IdentityFixtures.actor(AGENT)).ticket().id();
        assignments.claim(own,UUID.randomUUID(),0,IdentityFixtures.actor(AGENT));
        long otherId=new JdbcUserIdentityRepository(jdbc).findId(IdentityFixtures.key(OTHER)).orElseThrow();
        assignments.transfer(own,UUID.randomUUID(),1,otherId,"synthetic handoff",IdentityFixtures.actor(AGENT));
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE user_id=?",
                new JdbcUserIdentityRepository(jdbc).findId(IdentityFixtures.key(AGENT)).orElseThrow());
        follow(own,"customer ownership survives",UUID.randomUUID(),AGENT,201);
        assertThat(jdbc.queryForObject("SELECT assignment_version FROM tickets WHERE id=?",Long.class,own)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","[]","{","{\"body\":17}",
            "{\"body\":\"ok\",\"clientRequestId\":\"bad\"}",
            "{\"body\":\"ok\",\"clientRequestId\":null}",
            "{\"body\":\" \",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\"}",
            "{\"body\":\"ok\",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"authorUsername\":\"spoofed\"}",
            "{\"body\":\"ok\",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"correctsMessageId\":null}",
            "{\"body\":\"ok\",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":1}",
            "{\"body\":\"ok\",\"body\":\"different\",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\"}",
            "{\"body\":\"ok\",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\"} {}"})
    void invalid_payloads_are_rejected_before_storage(String json) throws Exception {
        mvc.perform(post("/api/tickets/{id}/customer-messages",ticket).with(as(A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        assertThat(messageCount()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings={"0","-1","9223372036854775808"})
    void invalid_ticket_id_does_not_write(String id) throws Exception {
        mvc.perform(post("/api/tickets/"+id+"/customer-messages").with(as(A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload("invalid",UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        assertThat(messageCount()).isOne();
    }

    @Test
    void byte_bound_is_checked_on_actual_stream_and_escaped_unicode_fits() throws Exception {
        String json=payload("bounded",UUID.randomUUID());
        int padding=CreateCustomerFollowupRequest.MAX_BODY_BYTES-json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        mvc.perform(post("/api/tickets/{id}/customer-messages",ticket).with(as(A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json+" ".repeat(padding))).andExpect(status().isCreated());
        mvc.perform(post("/api/tickets/{id}/customer-messages",ticket).with(as(A)).with(csrf())
                .header("Content-Length","1").contentType(MediaType.APPLICATION_JSON).content(json+" ".repeat(padding+1)))
                .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        String escaped="{\"body\":\""+"\\ud83d\\ude00".repeat(2000)+"\",\"clientRequestId\":\""+UUID.randomUUID()+"\"}";
        mvc.perform(post("/api/tickets/{id}/customer-messages",ticket).with(as(A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(escaped)).andExpect(status().isCreated());
        follow(ticket,"😀".repeat(2001),UUID.randomUUID(),A,400);
        assertThat(messageCount()).isEqualTo(3);
    }

    @Test
    void concurrent_same_submission_is_stored_once() throws Exception {
        UUID request=UUID.randomUUID();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> {start.await();return conversations.followUp(ticket,"concurrent",request,IdentityFixtures.actor(A));});
            var b=executor.submit(() -> {start.await();return conversations.followUp(ticket,"concurrent",request,IdentityFixtures.actor(A));});
            start.countDown();var one=a.get(10,TimeUnit.SECONDS);var two=b.get(10,TimeUnit.SECONDS);
            assertThat(one.message().id()).isEqualTo(two.message().id());
            assertThat(one.created()).isNotEqualTo(two.created());
        }
        assertThat(messageCount()).isEqualTo(2);
    }

    @Test
    void failure_after_insert_preserves_existing_reply_assignment_and_nonempty_ai_result() throws Exception {
        assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(AGENT));
        conversations.reply(ticket,"already committed reply",UUID.randomUUID(),null,1L,IdentityFixtures.actor(AGENT));
        var claim=claims.claimNextPending().orElseThrow();
        var output=new lab.helpdesk.ai.validation.AiSuggestionOutputValidator(200).validate("""
                {"decision":"SUGGEST","summary":"Synthetic original summary","categories":["ACCOUNT"],"priority":"NORMAL"}
                """);
        assertThat(results.complete(claim,output)).isEqualTo(lab.helpdesk.ai.suggestion.AiSuggestionCompletion.STORED);
        var before=snapshot();
        doAnswer(call -> {call.callRealMethod();throw new IllegalStateException("controlled failure");})
                .when(repository).insertCustomerFollowup(anyLong(),anyLong(),anyString(),anyString(),any(UUID.class));
        follow(ticket,"rolled back",UUID.randomUUID(),A,500);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void actual_deferred_commit_failure_never_returns_201_or_erases_prior_rows() throws Exception {
        var before=snapshot();
        jdbc.execute("CREATE TABLE followup_commit_guard(message_id BIGINT REFERENCES ticket_messages(id) DEFERRABLE INITIALLY DEFERRED)");
        jdbc.execute("""
                CREATE FUNCTION followup_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.message_type='CUSTOMER_FOLLOWUP' THEN
                        INSERT INTO followup_commit_guard VALUES(9223372036854775807);
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER followup_commit_failure AFTER INSERT ON ticket_messages FOR EACH ROW EXECUTE FUNCTION followup_commit_failure()");
        try {
            follow(ticket,"commit rejected",UUID.randomUUID(),A,500);
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER followup_commit_failure ON ticket_messages");
            jdbc.execute("DROP FUNCTION followup_commit_failure()");
            jdbc.execute("DROP TABLE followup_commit_guard");
        }
    }

    private JsonNode follow(long id,String body,UUID key,String user,int expected) throws Exception {
        var result=mvc.perform(post("/api/tickets/{id}/customer-messages",id).with(as(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload(body,key)))
                .andExpect(status().is(expected));
        if(expected==200 || expected==201)result.andExpect(header().string("Cache-Control","no-store"));
        return mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }
    private String payload(String body,UUID key) {return mapper.writeValueAsString(Map.of("body",body,"clientRequestId",key));}
    private RequestPostProcessor as(String name) {
        return user(IdentityFixtures.user(User.withUsername(name).password("synthetic-unused")
                .roles(name.contains("agent")?new String[]{"USER","AGENT"}:new String[]{"USER"}).build()));
    }
    private long messageCount() {return jdbc.queryForObject("SELECT count(*) FROM ticket_messages",Long.class);}
    private Map<String,List<Map<String,Object>>> snapshot() {
        var all=new java.util.LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:TABLES)all.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        return all;
    }
}
