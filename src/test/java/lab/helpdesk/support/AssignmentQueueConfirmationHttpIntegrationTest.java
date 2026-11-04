package lab.helpdesk.support;

import org.springframework.test.annotation.DirtiesContext;
import lab.helpdesk.testing.ClassOwnedContextCleanupVerifier;
import org.springframework.test.context.TestExecutionListeners;
import java.util.*;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.identity.*;
import lab.helpdesk.ticket.application.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"helpdesk.ai.worker.enabled=false","helpdesk.ai.provider.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("postgres")
@WithIdentityFixtures
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ClassOwnedContextCleanupVerifier.class, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class AssignmentQueueConfirmationHttpIntegrationTest {
    static final String A="queue-agent-a",B="queue-agent-b",C="queue-customer",OUT="queue-agent-other";
    static final List<String> TABLES=List.of("ticket_customer_message_confirmations","ticket_suggestion_categories",
            "ticket_suggestions","ai_suggestion_attempts","ai_suggestion_jobs","ticket_messages","ticket_assignment_events","tickets");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @Autowired TicketAssignmentService assignments;
    @Autowired CustomerMessageConfirmationService confirmations;
    @MockitoBean AiSuggestionProvider provider;
    long service,ticket,inquiry;

    @BeforeEach void fixture() {
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) c -> c.getMetaData().getURL().split("\\?",2)[0]
                .equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();
        for(String table:TABLES)jdbc.update("DELETE FROM "+table);
        for(String name:List.of(A,B,C,OUT))IdentityFixtures.bind(jdbc,name);
        service=jdbc.queryForObject("SELECT id FROM support_services WHERE code='synthetic-support'",Long.class);
        for(String name:List.of(A,B))jdbc.update("UPDATE support_service_memberships SET active=true,support_role='AGENT' WHERE service_id=? AND user_id=?",service,id(name));
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,id(OUT));
        var received=receipts.receive("queue fixture","  original customer text  ",IdentityFixtures.actor(C));
        ticket=received.ticket().id();inquiry=received.messageId();
    }
    @AfterEach void noProvider(){verifyNoInteractions(provider);}

    @Test void unassigned_snapshot_is_read_only_and_never_grants_confirmation() throws Exception {
        var before=snapshot();var item=queue(A,"UNASSIGNED",0,50).path("items").get(0);
        assertThat(item.size()).isEqualTo(10);
        assertThat(item.path("id").asLong()).isEqualTo(ticket);
        assertThat(item.path("assignmentVersion").asLong()).isZero();
        assertThat(item.path("latestCustomerMessageId").asLong()).isEqualTo(inquiry);
        assertThat(item.path("latestCustomerMessageAt").asString()).isNotBlank();
        assertThat(item.path("effectiveThroughMessageId").isNull()).isTrue();
        assertThat(item.path("needsConfirmation").asBoolean()).isTrue();
        assertThat(item.path("canConfirm").asBoolean()).isFalse();
        mvc.perform(head(path()).with(as(A))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void latest_customer_uses_message_id_and_excludes_later_official_replies_even_on_resolved_tickets() throws Exception {
        claim();long later=follow();
        conversations.reply(ticket,"staff reply",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A));
        jdbc.update("UPDATE ticket_messages SET created_at='2020-01-01T00:00:00Z' WHERE id=?",later);
        jdbc.update("UPDATE tickets SET status='RESOLVED' WHERE id=?",ticket);
        var before=snapshot();var item=queue(A,"MINE",0,50).path("items").get(0);
        assertThat(item.path("status").asString()).isEqualTo("RESOLVED");
        assertThat(item.path("latestCustomerMessageId").asLong()).isEqualTo(later);
        assertThat(item.path("latestCustomerMessageAt").asString()).isEqualTo("2020-01-01T00:00:00Z");
        assertThat(item.path("needsConfirmation").asBoolean()).isTrue();assertThat(item.path("canConfirm").asBoolean()).isTrue();
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void lower_late_confirmation_does_not_regress_and_a_new_customer_message_needs_confirmation() throws Exception {
        claim();long lower=follow(),higher=follow();
        confirmations.confirm(ticket,UUID.randomUUID(),1,higher,IdentityFixtures.actor(A));
        confirmations.confirm(ticket,UUID.randomUUID(),1,lower,IdentityFixtures.actor(A));
        var complete=queue(A,"MINE",0,50).path("items").get(0);
        assertThat(complete.path("effectiveThroughMessageId").asLong()).isEqualTo(higher);
        assertThat(complete.path("needsConfirmation").asBoolean()).isFalse();
        assertThat(complete.path("canConfirm").asBoolean()).isTrue();
        long latest=follow();var before=snapshot();var changed=queue(A,"MINE",0,50).path("items").get(0);
        assertThat(changed.path("effectiveThroughMessageId").asLong()).isEqualTo(higher);
        assertThat(changed.path("latestCustomerMessageId").asLong()).isEqualTo(latest);
        assertThat(changed.path("needsConfirmation").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_customer_message_confirmations",Long.class)).isEqualTo(2);
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void handoff_and_return_start_distinct_confirmation_scopes_and_mine_views() throws Exception {
        claim();confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(A));
        assignments.transfer(ticket,UUID.randomUUID(),1,id(B),"handoff",IdentityFixtures.actor(A));
        assertThat(queue(A,"MINE",0,50).path("items").size()).isZero();
        var second=queue(B,"MINE",0,50).path("items").get(0);
        assertThat(second.path("assignmentVersion").asLong()).isEqualTo(2);
        assertThat(second.path("effectiveThroughMessageId").isNull()).isTrue();
        assertThat(second.path("needsConfirmation").asBoolean()).isTrue();
        assignments.transfer(ticket,UUID.randomUUID(),2,id(A),"return",IdentityFixtures.actor(B));
        var third=queue(A,"MINE",0,50).path("items").get(0);
        assertThat(third.path("assignmentVersion").asLong()).isEqualTo(3);
        assertThat(third.path("effectiveThroughMessageId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_customer_message_confirmations",Long.class)).isOne();
    }
    @Test void id_pages_preserve_unassigned_and_mine_filters_and_resolved_items() throws Exception {
        long second=receipts.receive("second","body",IdentityFixtures.actor(C)).ticket().id();
        long third=receipts.receive("third","body",IdentityFixtures.actor(C)).ticket().id();
        jdbc.update("UPDATE tickets SET status='RESOLVED' WHERE id=?",second);
        var first=queue(A,"UNASSIGNED",0,1);
        assertThat(first.path("items").get(0).path("id").asLong()).isEqualTo(ticket);
        assertThat(first.path("hasMore").asBoolean()).isTrue();assertThat(first.path("nextAfterId").asLong()).isEqualTo(ticket);
        var next=queue(A,"UNASSIGNED",ticket,1);
        assertThat(next.path("items").get(0).path("id").asLong()).isEqualTo(second);
        var last=queue(A,"UNASSIGNED",second,1);
        assertThat(last.path("items").get(0).path("id").asLong()).isEqualTo(third);
        assertThat(last.path("hasMore").asBoolean()).isFalse();assertThat(last.path("nextAfterId").isNull()).isTrue();
        assignments.claim(second,UUID.randomUUID(),0,IdentityFixtures.actor(B));
        assertThat(queue(A,"MINE",0,50).path("items").size()).isZero();
        assertThat(queue(B,"MINE",0,50).path("items").size()).isOne();
        assertThat(queue(A,"UNASSIGNED",0,50).path("items").size()).isEqualTo(2);
    }
    @Test void empty_legacy_conversation_has_no_customer_timestamp_or_confirmation_target() throws Exception {
        jdbc.update("DELETE FROM ai_suggestion_jobs WHERE input_message_id=?",inquiry);
        jdbc.update("DELETE FROM ticket_messages WHERE id=?",inquiry);
        claim();var item=queue(A,"MINE",0,50).path("items").get(0);
        for(String field:List.of("createdAt","latestCustomerMessageId","latestCustomerMessageAt","effectiveThroughMessageId"))
            assertThat(item.path(field).isNull()).isTrue();
        assertThat(item.path("needsConfirmation").asBoolean()).isFalse();assertThat(item.path("canConfirm").asBoolean()).isFalse();
    }
    @Test void role_membership_and_service_boundaries_are_checked_before_queue_projection() throws Exception {
        var before=snapshot();
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(get(path()).with(as(C))).andExpect(status().isForbidden());
        mvc.perform(get(path()).with(as(OUT))).andExpect(status().isNotFound());
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,id(A));
        mvc.perform(get(path()).with(as(A))).andExpect(status().isNotFound());
        mvc.perform(head(path()).with(as(A))).andExpect(status().isNotFound());
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void conversation_capability_keeps_staff_state_separate_from_customer_ownership() throws Exception {
        for(String name:List.of(A,B))mvc.perform(get("/api/tickets/{id}/messages",ticket).with(as(name)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.capabilities.canViewConfirmations").value(true));
        mvc.perform(get("/api/tickets/{id}/messages",ticket).with(as(C)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.capabilities.canViewConfirmations").value(false));
        jdbc.update("UPDATE tickets SET requester_user_id=? WHERE id=?",id(A),ticket);
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,id(A));
        mvc.perform(get("/api/tickets/{id}/messages",ticket).with(as(A)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.capabilities.canFollowUp").value(true))
                .andExpect(jsonPath("$.capabilities.canViewConfirmations").value(false));
    }
    private void claim(){assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(A));}
    private long follow(){return conversations.followUp(ticket,"followup",UUID.randomUUID(),IdentityFixtures.actor(C)).message().id();}
    private long id(String name){return new JdbcUserIdentityRepository(jdbc).findId(IdentityFixtures.key(name)).orElseThrow();}
    private String path(){return "/api/services/"+service+"/assignment-queue";}
    private org.springframework.test.web.servlet.request.RequestPostProcessor as(String name){return user(IdentityFixtures.user(User.withUsername(name).password("synthetic-unused")
            .roles(name.contains("agent")?new String[]{"USER","AGENT"}:new String[]{"USER"}).build()));}
    private JsonNode queue(String name,String view,long after,int limit) throws Exception {
        return mapper.readTree(mvc.perform(get(path()).with(as(name)).param("view",view).param("afterId",Long.toString(after)).param("limit",Integer.toString(limit)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
    }
    private Map<String,List<Map<String,Object>>> snapshot(){var rows=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:TABLES)rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return rows;}
}
