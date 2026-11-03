package lab.helpdesk.ticket.web;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.identity.*;
import lab.helpdesk.ticket.application.TicketConversationService;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"helpdesk.ai.provider.enabled=false","helpdesk.ai.worker.enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("postgres")
@WithIdentityFixtures
@Testcontainers
class MyTicketsHttpIntegrationTest {
    private static final String A="list-user-a", B="list-user-b", AGENT="list-agent";
    private static final List<String> TABLES=List.of("ticket_suggestion_categories","ticket_suggestions",
            "ai_suggestion_attempts","ai_suggestion_jobs","ticket_messages","ticket_assignment_events","tickets");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @MockitoBean AiSuggestionProvider provider;

    @BeforeEach void isolate() {
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) c -> c.getMetaData().getURL().split("\\?",2)[0]
                .equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();
        for(String table:TABLES)jdbc.update("DELETE FROM "+table);
        for(String name:List.of(A,B,AGENT))IdentityFixtures.bind(jdbc,name);
    }
    @AfterEach void noAi() {verifyNoInteractions(provider);}

    @Test void identity_scopes_get_and_head_and_does_not_expose_internal_data() throws Exception {
        long own=receive(A),other=receive(B);
        receive(AGENT);
        var before=snapshot();
        var body=list(A,"",200);
        assertThat(body.path("items").size()).isOne();
        assertThat(body.path("items").get(0).path("ticketId").asLong()).isEqualTo(own);
        assertThat(body.path("items").get(0).size()).isEqualTo(4);
        assertThat(list(AGENT,"",200).path("items").size()).isOne();
        assertThat(list(B,"",200).path("items").get(0).path("ticketId").asLong()).isEqualTo(other);
        // MockMvc retains the mapped body for HEAD; real HTTP body suppression is checked in Browser.
        mvc.perform(head("/api/my/tickets").with(as(A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ticketId").value(own));
        mvc.perform(get("/api/my/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/my/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/my/tickets").with(user(A).roles("USER")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IDENTITY_UNLINKED"));
        mvc.perform(get("/api/my/tickets").with(user("unrelated").roles("OTHER"))).andExpect(status().isForbidden());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void empty_and_legacy_unowned_rows_are_safe_and_staff_revocation_is_not_customer_revocation() throws Exception {
        long own=receive(AGENT),legacy=receive(A);
        jdbc.update("UPDATE tickets SET requester_user_id=NULL WHERE id=?",legacy);
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE user_id=?",identity(AGENT));
        assertThat(list(AGENT,"",200).path("items").get(0).path("ticketId").asLong()).isEqualTo(own);
        var empty=list(A,"",200);
        assertThat(empty.path("items").isEmpty()).isTrue();
        assertThat(empty.path("page").path("hasMore").asBoolean()).isFalse();
        assertThat(empty.path("page").path("nextCursor").isNull()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings={"limit=0","limit=101","limit=-1","limit=1.0","limit=",
            "limit=1&limit=2","cursor=","cursor=a&cursor=b","requesterId=1","serviceId=1","cursor=not-json"})
    void malformed_or_spoofed_query_is_rejected(String query) throws Exception {list(A,"?"+query,400);}

    @ParameterizedTest @ValueSource(strings={"{}","null","[]",
            "{\"v\":1,\"v\":1,\"lastActivityAt\":null,\"ticketId\":1}",
            "{\"v\":4294967297,\"lastActivityAt\":null,\"ticketId\":1}",
            "{\"v\":1,\"lastActivityAt\":\"bad date\",\"ticketId\":1}",
            "{\"v\":1,\"lastActivityAt\":null,\"ticketId\":0}",
            "{\"v\":1,\"lastActivityAt\":null,\"ticketId\":9223372036854775808}",
            "{\"v\":1,\"lastActivityAt\":null,\"ticketId\":1,\"requesterId\":1}",
            "{\"v\":1,\"lastActivityAt\":null,\"ticketId\":1} {}"})
    void cursor_requires_exact_bounded_versioned_shape(String raw) throws Exception {list(A,"?cursor="+cursor(raw),400);}

    @Test void oversized_cursor_is_rejected_before_json_parsing() throws Exception {list(A,"?cursor="+"a".repeat(1025),400);}

    @Test void tied_timestamps_and_null_activities_paginate_without_omission() throws Exception {
        long one=receive(A),two=receive(A),three=receive(A);
        jdbc.update("UPDATE ticket_messages SET created_at='2026-11-03T01:00:00.123456Z'");
        long nullOne=emptyTicket(),nullTwo=emptyTicket();
        var found=new java.util.ArrayList<Long>();
        String next=null;
        do {
            var page=list(A,"?limit=1"+(next==null?"":"&cursor="+next),200);
            found.add(page.path("items").get(0).path("ticketId").asLong());
            next=page.path("page").path("nextCursor").isNull()?null:page.path("page").path("nextCursor").asString();
        }while(next!=null);
        assertThat(found).containsExactly(three,two,one,nullTwo,nullOne);
        assertThat(list(A,"?limit=100",200).path("items").size()).isEqualTo(5);
    }

    @Test void last_public_message_id_defines_activity_not_max_timestamp_or_ticket_update() throws Exception {
        long own=receive(A);
        jdbc.update("UPDATE ticket_messages SET created_at='2026-11-03T02:00:00Z' WHERE ticket_id=?",own);
        long follow=conversations.followUp(own,"new detail",UUID.randomUUID(),IdentityFixtures.actor(A)).message().id();
        jdbc.update("UPDATE ticket_messages SET created_at='2026-11-03T01:00:00Z' WHERE id=?",follow);
        var before=list(A,"",200);
        assertThat(before.path("items").get(0).path("lastActivityAt").asString()).isEqualTo("2026-11-03T01:00:00Z");
        jdbc.update("UPDATE tickets SET status='RESOLVED' WHERE id=?",own);
        var after=list(A,"",200);
        assertThat(after.path("items").get(0).path("lastActivityAt")).isEqualTo(before.path("items").get(0).path("lastActivityAt"));
    }

    @Test void a_moved_ticket_requires_refresh_and_a_foreign_cursor_never_grants_ownership() throws Exception {
        long old=receive(A),recent=receive(A),foreign=receive(B);
        jdbc.update("UPDATE ticket_messages SET created_at='2026-11-03T01:00:00Z' WHERE ticket_id=?",old);
        jdbc.update("UPDATE ticket_messages SET created_at='2026-11-03T02:00:00Z' WHERE ticket_id=?",recent);
        String next=list(A,"?limit=1",200).path("page").path("nextCursor").asString();
        var follow=conversations.followUp(old,"later detail",UUID.randomUUID(),IdentityFixtures.actor(A));
        jdbc.update("UPDATE ticket_messages SET created_at='2026-11-03T03:00:00Z' WHERE id=?",follow.message().id());
        assertThat(list(A,"?limit=1&cursor="+next,200).path("items").isEmpty()).isTrue();
        assertThat(list(A,"",200).path("items").get(0).path("ticketId").asLong()).isEqualTo(old);
        var boundary=cursor("{\"v\":1,\"lastActivityAt\":null,\"ticketId\":"+foreign+"}");
        assertThat(list(A,"?cursor="+boundary,200).path("items").isEmpty()).isTrue();
        var forged=cursor("{\"v\":1,\"lastActivityAt\":\"2050-01-01T00:00:00Z\",\"ticketId\":"+foreign+"}");
        assertThat(list(A,"?cursor="+forged,200).path("items").size()).isEqualTo(2);
    }

    private long receive(String user) {return receipts.receive("list title","original body",IdentityFixtures.actor(user)).ticket().id();}
    private long identity(String name) {return new JdbcUserIdentityRepository(jdbc).findId(IdentityFixtures.key(name)).orElseThrow();}
    private long emptyTicket() {return jdbc.queryForObject("INSERT INTO tickets(title,status,requester_user_id,service_id) VALUES('legacy empty','OPEN',?,(SELECT id FROM support_services WHERE code='synthetic-support')) RETURNING id",Long.class,identity(A));}
    private JsonNode list(String user,String query,int expected) throws Exception {
        var response=mvc.perform(get("/api/my/tickets"+query).with(as(user))).andExpect(status().is(expected));
        if(expected==200)response.andExpect(header().string("Cache-Control","no-store"));
        return mapper.readTree(response.andReturn().getResponse().getContentAsByteArray());
    }
    private RequestPostProcessor as(String name) {return user(IdentityFixtures.user(User.withUsername(name).password("synthetic-unused")
            .roles(name.contains("agent")?new String[]{"USER","AGENT"}:new String[]{"USER"}).build()));}
    private static String cursor(String json) {return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));}
    private Map<String,List<Map<String,Object>>> snapshot() {
        var rows=new java.util.LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:TABLES)rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        return rows;
    }
}
