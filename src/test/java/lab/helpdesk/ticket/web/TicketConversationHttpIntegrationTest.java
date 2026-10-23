package lab.helpdesk.ticket.web;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lab.helpdesk.identity.*;
import lab.helpdesk.ticket.application.*;
import lab.helpdesk.ticket.repository.JdbcTicketConversationRepository;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"helpdesk.ai.provider.enabled=false", "helpdesk.ai.worker.enabled=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("postgres")
@WithIdentityFixtures
@Testcontainers
class TicketConversationHttpIntegrationTest {
    private static final String A = "conversation-user-a", B = "conversation-user-b", AGENT = "conversation-agent";
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @MockitoSpyBean JdbcTicketConversationRepository repository;
    @MockitoBean AiSuggestionProvider provider;
    long ticket;
    long inquiry;

    @BeforeEach
    void isolatedDatabase() {
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) connection -> connection.getMetaData().getURL()
                .split("\\?", 2)[0].equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]))).isTrue();
        for (String table : List.of("ticket_suggestion_categories", "ticket_suggestions", "ai_suggestion_attempts",
                "ai_suggestion_jobs", "ticket_messages", "tickets")) jdbc.update("DELETE FROM " + table);
        for (String name : List.of(A, B, AGENT)) IdentityFixtures.bind(jdbc, name);
        var receipt = receipts.receive("original title", "  original inquiry\nunchanged  ", IdentityFixtures.actor(A));
        ticket = receipt.ticket().id(); inquiry = receipt.messageId();
        clearInvocations(provider, repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "IN_PROGRESS", "RESOLVED"})
    void agent_reply_is_public_to_owner_without_state_or_ai_changes(String state) throws Exception {
        jdbc.update("UPDATE tickets SET status=? WHERE id=?", state, ticket);
        String original = "  <script>synthetic()</script>\n안내 원문  ";
        JsonNode reply = reply(ticket, original, UUID.randomUUID(), null, AGENT, 201);
        mvc.perform(get("/api/tickets/{id}/messages", ticket).with(as(A)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.capabilities.canReply").value(false))
                .andExpect(jsonPath("$.ticket.status").value(state))
                .andExpect(jsonPath("$.messages[1].body").value(original))
                .andExpect(jsonPath("$.messages[1].authorUsername").doesNotExist());
        long author = jdbc.queryForObject("SELECT author_user_id FROM ticket_messages WHERE id=?", Long.class, reply.path("id").asLong());
        assertThat(author).isEqualTo(new JdbcUserIdentityRepository(jdbc).findId(IdentityFixtures.key(AGENT)).orElseThrow());
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id=?", String.class, inquiry)).isEqualTo("  original inquiry\nunchanged  ");
        assertAiUnchanged();
    }

    @Test
    void role_ownership_head_and_csrf_are_independent_gates() throws Exception {
        mvc.perform(get("/api/tickets/{id}/messages", ticket)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tickets/{id}/messages", ticket).with(as(B))).andExpect(status().isNotFound());
        mvc.perform(head("/api/tickets/{id}/messages", ticket).with(as(B))).andExpect(status().isNotFound());
        mvc.perform(head("/api/tickets/{id}/messages", ticket).with(as(A))).andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}/ai-suggestion", ticket).with(as(A))).andExpect(status().isForbidden());
        reply(ticket, "denied", UUID.randomUUID(), null, A, 403);
        mvc.perform(post("/api/tickets/{id}/replies", ticket).with(as(AGENT))
                .contentType(MediaType.APPLICATION_JSON).content(payload("no csrf", UUID.randomUUID(), null)))
                .andExpect(status().isForbidden());
        assertThat(messageCount()).isOne();
        assertAiUnchanged();
    }

    @Test
    void replay_returns_original_but_body_and_target_changes_conflict() throws Exception {
        UUID key = UUID.randomUUID();
        long first = reply(ticket, "same", key, null, AGENT, 201).path("id").asLong();
        assertThat(reply(ticket, "same", key, null, AGENT, 200).path("id").asLong()).isEqualTo(first);
        reply(ticket, " same", key, null, AGENT, 409);
        reply(ticket, "same", key, first, AGENT, 409);
        assertThat(messageCount()).isEqualTo(2);
        assertAiUnchanged();
    }

    @Test
    void correction_preserves_original_and_rejects_inquiry_other_ticket_and_missing_targets() throws Exception {
        long original = reply(ticket, "first reply", UUID.randomUUID(), null, AGENT, 201).path("id").asLong();
        long correction = reply(ticket, "correction", UUID.randomUUID(), original, AGENT, 201).path("id").asLong();
        reply(ticket, "correct again", UUID.randomUUID(), correction, AGENT, 201);
        reply(ticket, "bad", UUID.randomUUID(), inquiry, AGENT, 400);
        reply(ticket, "bad", UUID.randomUUID(), Long.MAX_VALUE, AGENT, 400);
        var other = receipts.receive("other", "other body", IdentityFixtures.actor(B));
        reply(other.ticket().id(), "bad", UUID.randomUUID(), original, AGENT, 400);
        assertThat(jdbc.queryForObject("SELECT body FROM ticket_messages WHERE id=?", String.class, original)).isEqualTo("first reply");
        mvc.perform(get("/api/tickets/{id}/messages", ticket).with(as(A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[2].correctsMessageId").value(original));
    }

    @Test
    void missing_legacy_owner_allows_agent_read_only_without_guessing_from_author_name() throws Exception {
        jdbc.update("UPDATE tickets SET requester_user_id=NULL WHERE id=?", ticket);
        mvc.perform(get("/api/tickets/{id}/messages", ticket).with(as(AGENT)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.capabilities.canReply").value(false));
        mvc.perform(get("/api/tickets/{id}/messages", ticket).with(as(A))).andExpect(status().isNotFound());
        reply(ticket, "not published", UUID.randomUUID(), null, AGENT, 409);
        assertThat(messageCount()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"body\":\"ok\"}", "{\"body\":\" \"}",
            "{\"body\":\"ok\",\"clientRequestId\":\"bad\"}",
            "{\"body\":\"ok\",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"authorUsername\":\"spoofed\"}",
            "{\"body\":\"ok\",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"correctsMessageId\":0}",
            "{\"body\":17,\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\"}",
            "{\"body\":\"ok\",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"correctsMessageId\":\"1\"}",
            "{\"body\":\"ok\",\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"correctsMessageId\":9223372036854775808}"
    })
    void invalid_reply_payload_is_rejected_without_storage(String json) throws Exception {
        mvc.perform(post("/api/tickets/{id}/replies", ticket).with(as(AGENT)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        assertThat(messageCount()).isOne();
    }

    @Test
    void code_point_limit_is_enforced_and_original_body_is_preserved() throws Exception {
        reply(ticket, "😀".repeat(2000), UUID.randomUUID(), null, AGENT, 201);
        reply(ticket, "😀".repeat(2001), UUID.randomUUID(), null, AGENT, 400);
        assertThat(messageCount()).isEqualTo(2);
    }

    @Test
    void bounded_pages_are_ordered_and_read_only() throws Exception {
        for (int i=0;i<3;i++) reply(ticket, "reply " + i, UUID.randomUUID(), null, AGENT, 201);
        JsonNode first = getPage(0, 2);
        assertThat(first.path("messages").size()).isEqualTo(2);
        assertThat(first.path("page").path("hasMore").asBoolean()).isTrue();
        long cursor = first.path("page").path("nextAfterId").asLong();
        JsonNode second = getPage(cursor, 2);
        assertThat(second.path("messages").size()).isEqualTo(2);
        assertThat(second.path("messages").get(0).path("id").asLong()).isGreaterThan(cursor);
        assertThat(second.path("page").path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("page").path("nextAfterId").isNull()).isTrue();
        mvc.perform(get("/api/tickets/{id}/messages?limit=101", ticket).with(as(A))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets/{id}/messages?afterId=-1", ticket).with(as(A))).andExpect(status().isBadRequest());
        assertThat(messageCount()).isEqualTo(4);
        assertAiUnchanged();
    }

    @Test
    void missing_binding_or_plain_username_cannot_create_a_receipt_or_reply() throws Exception {
        var unknown = IdentityFixtures.user(User.withUsername("unlinked-user").password("fixture").roles("USER").build());
        mvc.perform(post("/api/tickets").with(user(unknown)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"not saved\",\"body\":\"not saved\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IDENTITY_UNLINKED"));
        mvc.perform(post("/api/tickets/{id}/replies", ticket).with(user("plain-agent").roles("AGENT")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload("not saved", UUID.randomUUID(), null)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets", Long.class)).isOne();
        assertThat(messageCount()).isOne();
    }

    @Test
    void old_session_key_does_not_adopt_reused_login_name() throws Exception {
        var oldPrincipal = IdentityFixtures.user(User.withUsername(A).password("fixture").roles("USER").build());
        var ids = new JdbcUserIdentityRepository(jdbc);
        ids.bind(IdentityFixtures.key(A), "renamed-conversation-user");
        UUID newKey = UUID.randomUUID();
        ids.bind(newKey, A);
        var newPrincipal = new AccountUserDetails(User.withUsername(A).password("fixture").roles("USER").build(), newKey);
        mvc.perform(get("/api/tickets/{id}/messages", ticket).with(user(oldPrincipal))).andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}/messages", ticket).with(user(newPrincipal))).andExpect(status().isNotFound());
        // Restore the explicit fixture binding so another test cannot inherit the deliberate rename.
        jdbc.update("UPDATE helpdesk_user_identities SET login_username='retired-name' WHERE account_key=?", newKey);
        ids.bind(IdentityFixtures.key(A), A);
    }

    @Test
    void concurrent_same_submission_is_stored_once() throws Exception {
        UUID request = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { start.await(); return conversations.reply(ticket,"concurrent",request,null,IdentityFixtures.actor(AGENT)); });
            var two = executor.submit(() -> { start.await(); return conversations.reply(ticket,"concurrent",request,null,IdentityFixtures.actor(AGENT)); });
            start.countDown();
            var a = one.get(10, TimeUnit.SECONDS); var b = two.get(10, TimeUnit.SECONDS);
            assertThat(a.message().id()).isEqualTo(b.message().id());
            assertThat(a.created()).isNotEqualTo(b.created());
        }
        assertThat(messageCount()).isEqualTo(2);
        assertAiUnchanged();
    }

    @Test
    void failure_after_insert_rolls_back_only_new_reply() throws Exception {
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("controlled failure"); })
                .when(repository).insertReply(anyLong(),anyLong(),anyString(),anyString(),any(UUID.class),nullable(Long.class));
        reply(ticket, "rolled back", UUID.randomUUID(), null, AGENT, 500);
        assertThat(messageCount()).isOne();
        assertAiUnchanged();
    }

    @Test
    void commit_failure_does_not_return_a_success_or_erase_committed_inquiry() throws Exception {
        jdbc.execute("CREATE TABLE conversation_commit_guard (message_id BIGINT REFERENCES ticket_messages(id) DEFERRABLE INITIALLY DEFERRED)");
        jdbc.execute("""
                CREATE FUNCTION conversation_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.message_type = 'OFFICIAL_REPLY' THEN
                        INSERT INTO conversation_commit_guard VALUES (9223372036854775807);
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER conversation_commit_failure AFTER INSERT ON ticket_messages FOR EACH ROW EXECUTE FUNCTION conversation_commit_failure()");
        try {
            reply(ticket, "commit rejected", UUID.randomUUID(), null, AGENT, 500);
            assertThat(messageCount()).isOne();
            assertAiUnchanged();
        } finally {
            jdbc.execute("DROP TRIGGER conversation_commit_failure ON ticket_messages");
            jdbc.execute("DROP FUNCTION conversation_commit_failure()");
            jdbc.execute("DROP TABLE conversation_commit_guard");
        }
    }

    private JsonNode reply(long id, String body, UUID key, Long correction, String user, int expected) throws Exception {
        var result = mvc.perform(post("/api/tickets/{id}/replies", id).with(as(user)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload(body,key,correction)))
                .andExpect(status().is(expected)).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }
    private String payload(String body, UUID key, Long correction) {
        var map = new java.util.LinkedHashMap<String,Object>();
        map.put("body",body);map.put("clientRequestId",key);map.put("correctsMessageId",correction);
        return mapper.writeValueAsString(map);
    }
    private JsonNode getPage(long after, int limit) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/tickets/{id}/messages", ticket).with(as(A))
                .param("afterId",String.valueOf(after)).param("limit",String.valueOf(limit)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private RequestPostProcessor as(String name) {
        return user(IdentityFixtures.user(User.withUsername(name).password("synthetic-unused")
                .roles(name.equals(AGENT) ? new String[]{"USER","AGENT"} : new String[]{"USER"}).build()));
    }
    private long messageCount() { return jdbc.queryForObject("SELECT count(*) FROM ticket_messages", Long.class); }
    private void assertAiUnchanged() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_suggestion_jobs", Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_suggestion_jobs", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT current_attempt + reserved_generation_count FROM ai_suggestion_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_suggestions", Long.class)).isZero();
        verifyNoInteractions(provider);
    }
}
