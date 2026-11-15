package lab.helpdesk.ticket.web;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.identity.*;
import lab.helpdesk.support.*;
import lab.helpdesk.ticket.*;
import lab.helpdesk.ticket.application.*;
import lab.helpdesk.ticket.application.TicketStatusService.Command;
import lab.helpdesk.ticket.repository.JdbcTicketStatusRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"helpdesk.ai.worker.enabled=false","helpdesk.ai.provider.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@ActiveProfiles("postgres")
@WithIdentityFixtures
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TicketStatusHttpIntegrationTest {
    static final String A="status-agent-a",B="status-agent-b",S="status-agent-supervisor",C="status-customer",D="status-other-customer",OUT="status-agent-out";
    static final List<String> TABLES=List.of("ticket_status_events","ticket_customer_message_confirmations","ticket_suggestion_categories",
            "ticket_suggestions","ai_suggestion_attempts","ai_suggestion_jobs","ticket_messages","ticket_assignment_events","tickets");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @Autowired CustomerMessageConfirmationService confirmations;
    @Autowired TicketAssignmentService assignments;
    @Autowired TicketStatusService statuses;
    @Autowired PlatformTransactionManager manager;
    @Autowired lab.helpdesk.ai.job.AiSuggestionJobClaimService claims;
    @Autowired lab.helpdesk.ai.suggestion.AiSuggestionResultService results;
    @MockitoSpyBean JdbcTicketStatusRepository repository;
    @MockitoBean AiSuggestionProvider provider;
    long ticket,inquiry,service,reply;

    @BeforeEach void isolatedFixture() {
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) c->c.getMetaData().getURL().split("\\?",2)[0].equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();
        for(String table:TABLES)jdbc.update("DELETE FROM "+table);
        for(String who:List.of(A,B,S,C,D,OUT))IdentityFixtures.bind(jdbc,who);
        service=jdbc.queryForObject("SELECT id FROM support_services WHERE code='synthetic-support'",Long.class);
        for(String who:List.of(A,B,S))jdbc.update("UPDATE support_service_memberships SET active=true,support_role=? WHERE service_id=? AND user_id=?",who.equals(S)?"SUPERVISOR":"AGENT",service,id(who));
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,id(OUT));
        var created=receipts.receive("Synthetic status case"," original inquiry unchanged ",IdentityFixtures.actor(C));
        ticket=created.ticket().id();inquiry=created.messageId();reply=0;
        clearInvocations(repository,provider);
    }
    @AfterEach void noProvider() {verifyNoInteractions(provider);}

    @Test void get_head_are_read_only_and_customer_projection_has_no_internal_metadata()throws Exception {
        var before=snapshot();
        var staff=read(A,"/status",200);
        assertThat(staff.path("status").asString()).isEqualTo("OPEN");
        assertThat(staff.path("statusVersion").asLong()).isZero();
        assertThat(staff.path("assignmentVersion").asLong()).isZero();
        assertThat(staff.path("canStart").asBoolean()).isFalse();
        assertThat(read(C,"/customer-status",200).propertyNames()).containsExactlyInAnyOrder("status","statusVersion","canReopen");
        read(C,"/status",403);read(D,"/customer-status",404);read(B,"/customer-status",404);read(OUT,"/status",404);
        mvc.perform(get(base()+"/status")).andExpect(status().isUnauthorized());
        mvc.perform(head(base()+"/status").with(as(A))).andExpect(status().isOk());
        mvc.perform(head(base()+"/customer-status").with(as(C))).andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void complete_lifecycle_preserves_assignment_original_messages_and_ai_job()throws Exception {
        claim();var a=start();assertThat(a.path("current").path("statusVersion").asLong()).isEqualTo(1);
        reply=conversations.reply(ticket,"Synthetic resolution",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A)).message().id();
        confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(A));
        var fixed=preserved();
        var resolved=send(A,resolve(UUID.randomUUID(),1,1,inquiry),201);
        assertThat(resolved.path("change").path("statusVersion").asLong()).isEqualTo(2);
        assertThat(resolved.path("current").path("status").asString()).isEqualTo("RESOLVED");
        UUID customerKey=UUID.randomUUID();var reopened=reopen(C,customerKey,2,"Still unresolved",201);
        assertThat(reopened.path("current").path("status").asString()).isEqualTo("OPEN");
        assertThat(reopened.path("change").propertyNames()).containsExactlyInAnyOrder("id","fromStatus","toStatus","statusVersion","reason","recordedAt");
        reopen(C,customerKey,2,"Still unresolved",200);
        send(A,staff(UUID.randomUUID(),"START",3,1,"New work"),201);
        var current=read(A,"/status",200);
        assertThat(current.path("assignmentVersion").asLong()).isEqualTo(1);
        assertThat(current.path("statusVersion").asLong()).isEqualTo(4);
        assertThat(preserved()).isEqualTo(fixed);
        assertThat(count()).isEqualTo(4);
    }
    @Test void history_is_bounded_and_not_a_customer_audit_endpoint()throws Exception {
        prepare();send(A,resolve(UUID.randomUUID(),1,1,inquiry),201);reopen(C,UUID.randomUUID(),2,"Again",201);
        var before=snapshot();var page=read(B,"/status-events?limit=2",200);
        assertThat(page.path("items").size()).isEqualTo(2);assertThat(page.path("hasMore").asBoolean()).isTrue();
        var last=read(A,"/status-events?afterId="+page.path("nextAfterId").asLong()+"&limit=2",200);
        assertThat(last.path("items").size()).isEqualTo(1);assertThat(last.path("hasMore").asBoolean()).isFalse();
        read(C,"/status-events",403);read(OUT,"/status-events",404);
        read(A,"/status-events?limit=101",400);read(A,"/status-events?afterId=-1",400);
        mvc.perform(head(base()+"/status-events").with(as(A))).andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void original_result_replays_after_reopening_and_handoff_without_reapplying_resolution()throws Exception {
        prepare();UUID key=UUID.randomUUID();var payload=resolve(key,1,1,inquiry);
        var original=send(A,payload,201);reopen(C,UUID.randomUUID(),2,"Again",201);
        assignments.transfer(ticket,UUID.randomUUID(),1,id(B),"Handoff",IdentityFixtures.actor(A));
        var before=snapshot();var replay=send(A,payload,200);
        assertThat(replay.path("change")).isEqualTo(original.path("change"));
        assertThat(replay.path("current").path("status").asString()).isEqualTo("OPEN");
        assertThat(replay.path("current").path("statusVersion").asLong()).isEqualTo(3);
        assertThat(replay.path("current").path("assignmentVersion").asLong()).isEqualTo(2);
        assertThat(snapshot()).isEqualTo(before);
        assertCode(send(A,staff(UUID.randomUUID(),"START",3,2,"Not owner"),403),"NOT_ASSIGNEE");
    }
    @Test void changed_payload_under_same_uuid_conflicts_without_a_new_version()throws Exception {
        claim();UUID key=UUID.randomUUID();var input=staff(key,"START",0,1,"Start");send(A,input,201);var before=snapshot();
        for(String field:List.of("reason","expectedStatusVersion","expectedAssignmentVersion","action")) {
            var changed=new LinkedHashMap<String,Object>(input);
            changed.put(field,switch(field){case "reason"->"changed";case "action"->"REOPEN";default->2;});
            assertCode(send(A,changed,409),"STATUS_REQUEST_CONFLICT");
        }
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void status_version_fences_a_same_status_cycle_and_assignment_version_fences_a_to_b_to_a()throws Exception {
        prepare();send(A,resolve(UUID.randomUUID(),1,1,inquiry),201);reopen(C,UUID.randomUUID(),2,"Again",201);
        send(A,staff(UUID.randomUUID(),"START",3,1,"Restart"),201);
        assertCode(send(A,resolve(UUID.randomUUID(),1,1,inquiry),409),"STATUS_VERSION_CONFLICT");
        assignments.transfer(ticket,UUID.randomUUID(),1,id(B),"To B",IdentityFixtures.actor(A));
        assignments.transfer(ticket,UUID.randomUUID(),2,id(A),"Back",IdentityFixtures.actor(B));
        assertCode(send(A,resolve(UUID.randomUUID(),4,1,inquiry),409),"ASSIGNMENT_VERSION_CONFLICT");
        assertThat(read(A,"/status",200).path("statusVersion").asLong()).isEqualTo(4);
    }
    @Test void supervisor_and_other_active_staff_do_not_bypass_current_assignee()throws Exception {
        claim();var before=snapshot();
        for(String who:List.of(B,S))assertCode(send(who,staff(UUID.randomUUID(),"START",0,1,"Other"),403),"NOT_ASSIGNEE");
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void newer_customer_message_requires_a_new_selected_range_even_after_another_confirmation()throws Exception {
        prepare();long later=follow();var old=resolve(UUID.randomUUID(),1,1,inquiry);
        assertCode(send(A,old,409),"NEW_CUSTOMER_MESSAGE");
        confirmations.confirm(ticket,UUID.randomUUID(),1,later,IdentityFixtures.actor(A));
        assertCode(send(A,old,409),"NEW_CUSTOMER_MESSAGE");
        send(A,resolve(UUID.randomUUID(),1,1,later),201);
        assertThat(jdbc.queryForObject("SELECT through_customer_message_id FROM ticket_status_events WHERE operation='RESOLVE'",Long.class)).isEqualTo(later);
    }
    @Test void post_resolution_followup_survives_without_implicit_reopening_or_new_ai_work()throws Exception {
        prepare();send(A,resolve(UUID.randomUUID(),1,1,inquiry),201);
        var job=jdbc.queryForList("SELECT * FROM ai_suggestion_jobs");long later=follow();
        assertThat(later).isGreaterThan(inquiry);
        assertThat(read(A,"/status",200).path("status").asString()).isEqualTo("RESOLVED");
        assertThat(confirmations.read(ticket,IdentityFixtures.actor(A)).needsConfirmation()).isTrue();
        assertThat(jdbc.queryForList("SELECT * FROM ai_suggestion_jobs")).isEqualTo(job);
    }
    @Test void resolution_requires_explicit_confirmation_and_same_ticket_message_kinds()throws Exception {
        claim();start();reply=conversations.reply(ticket,"Reply",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A)).message().id();
        assertCode(send(A,resolve(UUID.randomUUID(),1,1,inquiry),409),"CUSTOMER_CONFIRMATION_REQUIRED");
        confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(A));
        var wrong=resolve(UUID.randomUUID(),1,1,inquiry);wrong.put("relatedReplyMessageId",inquiry);
        assertCode(send(A,wrong,400),"INVALID_STATUS_TARGET");
        wrong=resolve(UUID.randomUUID(),1,1,reply);assertCode(send(A,wrong,400),"INVALID_STATUS_TARGET");
        long other=receipts.receive("Other","Other original",IdentityFixtures.actor(D)).messageId();
        assertCode(send(A,resolve(UUID.randomUUID(),1,1,other),400),"INVALID_STATUS_TARGET");
        assertThat(count()).isEqualTo(1);
    }
    @Test void unknown_requester_is_not_inferred_by_new_resolution()throws Exception {
        prepare();jdbc.update("UPDATE tickets SET requester_user_id=NULL, receipt_request_id=NULL WHERE id=?",ticket);var before=snapshot();
        assertCode(send(A,resolve(UUID.randomUUID(),1,1,inquiry),409),"REQUESTER_UNCONFIRMED");
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void revoked_staff_replay_is_denied_but_the_committed_event_survives()throws Exception {
        claim();var body=staff(UUID.randomUUID(),"START",0,1,"Start");send(A,body,201);revoke(A);
        var before=snapshot();send(A,body,404);read(A,"/status",404);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void requester_ownership_survives_staff_revocation_but_route_scope_cannot_change()throws Exception {
        var created=receipts.receive("Agent's own inquiry","Own original",IdentityFixtures.actor(A));ticket=created.ticket().id();inquiry=created.messageId();
        prepare();UUID staffKey=UUID.randomUUID();var command=staff(staffKey,"REOPEN",2,1,"Staff reopen");
        send(A,resolve(UUID.randomUUID(),1,1,inquiry),201);send(A,command,201);
        assertCode(reopen(A,staffKey,2,"Staff reopen",409),"STATUS_REQUEST_CONFLICT");
        send(A,staff(UUID.randomUUID(),"START",3,1,"Start again"),201);
        send(A,resolve(UUID.randomUUID(),4,1,inquiry),201);revoke(A);
        var current=read(A,"/customer-status",200);assertThat(current.path("canReopen").asBoolean()).isTrue();
        UUID key=UUID.randomUUID();reopen(A,key,5,"Customer reopen",201);reopen(A,key,5,"Customer reopen",200);
        read(A,"/status",404);
    }
    @Test void deferred_commit_failure_never_returns_success_and_preserves_previous_rows()throws Exception {
        prepare();var before=snapshot();
        jdbc.execute("CREATE TABLE status_commit_guard (id BIGINT REFERENCES tickets(id) DEFERRABLE INITIALLY DEFERRED)");
        jdbc.execute("CREATE FUNCTION status_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO status_commit_guard VALUES (9223372036854775807); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER status_commit_failure AFTER INSERT ON ticket_status_events FOR EACH ROW EXECUTE FUNCTION status_commit_failure()");
        try {send(A,resolve(UUID.randomUUID(),1,1,inquiry),500);assertThat(snapshot()).isEqualTo(before);}
        finally {jdbc.execute("DROP TRIGGER status_commit_failure ON ticket_status_events");jdbc.execute("DROP FUNCTION status_commit_failure()");jdbc.execute("DROP TABLE status_commit_guard");}
    }
    @Test void event_insert_then_failure_rolls_back_status_and_preserves_populated_ai_data()throws Exception {
        prepare();var claim=claims.claimNextPending().orElseThrow();
        var valid=new lab.helpdesk.ai.validation.AiSuggestionOutputValidator(200).validate("""
                {"decision":"SUGGEST","summary":"Synthetic original inquiry","categories":["ACCOUNT"],"priority":"NORMAL"}
                """);
        results.complete(claim,valid);var before=snapshot();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("controlled status event failure");})
                .when(repository).append(any(),anyLong(),any(),any(),any(),any(),any(),anyString(),any(),any());
        send(A,resolve(UUID.randomUUID(),1,1,inquiry),500);assertThat(snapshot()).isEqualTo(before);
    }
    @Test void authentication_csrf_and_role_filters_precede_service_invocation()throws Exception {
        claim();var before=snapshot();clearInvocations(repository);
        String body=mapper.writeValueAsString(staff(UUID.randomUUID(),"START",0,1,"Start"));
        mvc.perform(post(base()+"/status-changes").with(as(A)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(base()+"/status-changes").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(base()+"/status-changes").with(as(C)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(repository);assertThat(snapshot()).isEqualTo(before);
    }
    @ParameterizedTest @MethodSource("invalidStaffBodies")
    void malformed_or_spoofed_requests_do_not_mutate_any_rows(String body)throws Exception {
        claim();var before=snapshot();
        mvc.perform(post(base()+"/status-changes").with(as(A)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }
    static Stream<String> invalidStaffBodies() {
        String base="{\"action\":\"START\",\"clientRequestId\":\"00000000-0000-4000-8000-000000000001\",\"expectedStatusVersion\":0,\"expectedAssignmentVersion\":1,\"reason\":\"Start\"}";
        return Stream.of("", "[]", base+" {}",base.replace("START","CANCEL"),base.replace("\"reason\":\"Start\"","\"reason\":null"),
                base.replace("\"Start\"","\" \""),base.replace("\"Start\"","\""+"x".repeat(501)+"\""),
                base.replace("\"expectedStatusVersion\":0","\"expectedStatusVersion\":\"0\""),base.replace("\"expectedStatusVersion\":0","\"expectedStatusVersion\":0.0"),
                base.replace("\"expectedStatusVersion\":0","\"expectedStatusVersion\":9223372036854775808"),base.replace("\"expectedStatusVersion\":0","\"expectedStatusVersion\":-1"),
                base.replace("\"expectedAssignmentVersion\":1","\"expectedAssignmentVersion\":0"),base.replace("00000000-0000-4000-8000-000000000001","1-1-1-1-1"),
                base.replace("\"action\":\"START\",", ""),base.replace("{","{\"actorUserId\":1,"),base.replace("{","{\"reason\":\"other\","),
                base.replace("{","{\"relatedReplyMessageId\":1,"));
    }
    @Test void body_limit_and_original_unicode_reason_are_enforced()throws Exception {
        claim();String reason="\uD83D\uDE42".repeat(500);var request=staff(UUID.randomUUID(),"START",0,1,reason);
        assertThat(send(A,request,201).path("change").path("reason").asString()).isEqualTo(reason);
        var huge=staff(UUID.randomUUID(),"REOPEN",1,1,"x".repeat(40000));send(A,huge,413);
    }

    @Test void invalid_transitions_and_requester_extra_fields_cannot_change_status()throws Exception {
        claim();var before=snapshot();
        assertCode(send(A,staff(UUID.randomUUID(),"REOPEN",0,1,"Invalid"),409),"INVALID_STATUS_TRANSITION");
        assertCode(reopen(C,UUID.randomUUID(),0,"Not resolved",409),"INVALID_STATUS_TRANSITION");
        for(String field:List.of("action","expectedAssignmentVersion","actorUserId","status")) {
            var body=new LinkedHashMap<String,Object>();body.put("clientRequestId",UUID.randomUUID());body.put("expectedStatusVersion",0);body.put("reason","Again");body.put(field,1);
            postJson(C,"/customer-status/reopen",body,400);
        }
        assertThat(snapshot()).isEqualTo(before);
        start();assertCode(send(A,staff(UUID.randomUUID(),"START",1,1,"Again"),409),"INVALID_STATUS_TRANSITION");
    }
    @Test void confirmed_requester_can_reopen_baseline_unassigned_data_without_an_assignment_counter()throws Exception {
        ticket=jdbc.queryForObject("INSERT INTO tickets(title,status,requester_user_id) VALUES('Legacy resolved','RESOLVED',?) RETURNING id",Long.class,id(C));
        var body=reopen(C,UUID.randomUUID(),0,"Explicit legacy reopen",201);
        assertThat(body.path("current").path("statusVersion").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT assignment_version FROM tickets WHERE id=?",Long.class,ticket)).isZero();
        assertThat(jdbc.queryForObject("SELECT assignment_version FROM ticket_status_events WHERE ticket_id=?",Long.class,ticket)).isZero();
    }
    @Test void exact_concurrent_replay_creates_one_event_and_advances_version_once()throws Exception {
        claim();var cmd=startCommand(UUID.randomUUID());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);holdAppend(entered,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                var first=pool.submit(()->statuses.changeStaff(ticket,cmd,IdentityFixtures.actor(A)));await(entered);
                var second=pool.submit(()->statuses.changeStaff(ticket,cmd,IdentityFixtures.actor(A)));awaitDatabaseWaiter();release.countDown();
                var a=first.get(10,TimeUnit.SECONDS);var b=second.get(10,TimeUnit.SECONDS);
                assertThat(a.created()).isTrue();assertThat(b.created()).isFalse();assertThat(b.change()).isEqualTo(a.change());
                assertThat(count()).isEqualTo(1);assertThat(read(A,"/status",200).path("statusVersion").asLong()).isEqualTo(1);
            } finally {release.countDown();}
        }
    }
    @Test void competing_new_submissions_recheck_the_status_version_after_obtaining_the_lock()throws Exception {
        claim();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);holdAppend(entered,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                var first=pool.submit(()->statuses.changeStaff(ticket,startCommand(UUID.randomUUID()),IdentityFixtures.actor(A)));await(entered);
                var second=pool.submit(()->outcome(()->statuses.changeStaff(ticket,startCommand(UUID.randomUUID()),IdentityFixtures.actor(A))));
                awaitDatabaseWaiter();release.countDown();first.get(10,TimeUnit.SECONDS);
                assertThat(second.get(10,TimeUnit.SECONDS)).isEqualTo("STATUS_VERSION_CONFLICT");assertThat(count()).isEqualTo(1);
            } finally {release.countDown();}
        }
    }
    @Test void customer_commit_before_waiting_resolution_is_seen_by_a_fresh_post_lock_statement()throws Exception {
        prepare();var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                var message=pool.submit(()->new TransactionTemplate(manager).execute(s->{long m=follow();inserted.countDown();await(release);return m;}));
                await(inserted);
                var resolution=pool.submit(()->outcome(()->statuses.changeStaff(ticket,resolveCommand(),IdentityFixtures.actor(A))));
                awaitDatabaseWaiter();release.countDown();long later=message.get(10,TimeUnit.SECONDS);
                assertThat(resolution.get(10,TimeUnit.SECONDS)).isEqualTo("NEW_CUSTOMER_MESSAGE");
                var current=read(A,"/status",200);assertThat(current.path("latestCustomerMessageId").asLong()).isEqualTo(later);
                assertThat(current.path("status").asString()).isEqualTo("IN_PROGRESS");assertThat(count()).isEqualTo(1);
            } finally {release.countDown();}
        }
    }
    @Test void resolution_commit_before_waiting_message_preserves_the_original_response_snapshot()throws Exception {
        prepare();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);holdAppend(entered,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                var resolution=pool.submit(()->statuses.changeStaff(ticket,resolveCommand(),IdentityFixtures.actor(A)));await(entered);
                var message=pool.submit(this::follow);awaitDatabaseWaiter();release.countDown();
                var saved=resolution.get(10,TimeUnit.SECONDS);long later=message.get(10,TimeUnit.SECONDS);
                assertThat(saved.current().latestCustomerMessageId()).isEqualTo(inquiry);
                var current=read(A,"/status",200);assertThat(current.path("latestCustomerMessageId").asLong()).isEqualTo(later);
                assertThat(current.path("status").asString()).isEqualTo("RESOLVED");
                assertThat(confirmations.read(ticket,IdentityFixtures.actor(A)).needsConfirmation()).isTrue();
            } finally {release.countDown();}
        }
    }
    @Test void handoff_commit_before_waiting_resolution_revokes_the_old_assignees_new_write()throws Exception {
        prepare();var transferred=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                var handoff=pool.submit(()->new TransactionTemplate(manager).execute(s->{var r=assignments.transfer(ticket,UUID.randomUUID(),1,id(B),"Handoff",IdentityFixtures.actor(A));transferred.countDown();await(release);return r;}));
                await(transferred);var resolution=pool.submit(()->outcome(()->statuses.changeStaff(ticket,resolveCommand(),IdentityFixtures.actor(A))));
                awaitDatabaseWaiter();release.countDown();handoff.get(10,TimeUnit.SECONDS);
                assertThat(resolution.get(10,TimeUnit.SECONDS)).isEqualTo("NOT_ASSIGNEE");assertThat(count()).isEqualTo(1);
            } finally {release.countDown();}
        }
    }
    @Test void resolution_commit_before_handoff_keeps_its_saved_assignment_version()throws Exception {
        prepare();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);holdAppend(entered,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                var resolution=pool.submit(()->statuses.changeStaff(ticket,resolveCommand(),IdentityFixtures.actor(A)));await(entered);
                var handoff=pool.submit(()->assignments.transfer(ticket,UUID.randomUUID(),1,id(B),"Handoff",IdentityFixtures.actor(A)));
                awaitDatabaseWaiter();release.countDown();var saved=resolution.get(10,TimeUnit.SECONDS);handoff.get(10,TimeUnit.SECONDS);
                assertThat(saved.change().assignmentVersion()).isEqualTo(1);
                var current=read(B,"/status",200);assertThat(current.path("assignmentVersion").asLong()).isEqualTo(2);
                assertThat(current.path("statusVersion").asLong()).isEqualTo(2);
            } finally {release.countDown();}
        }
    }
    @Test void committed_revocation_blocks_a_waiting_request_before_any_status_write()throws Exception {
        claim();var changed=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                var revoke=pool.submit(()->new TransactionTemplate(manager).execute(s->{revoke(A);changed.countDown();await(release);return null;}));
                await(changed);var status=pool.submit(()->outcome(()->statuses.changeStaff(ticket,startCommand(UUID.randomUUID()),IdentityFixtures.actor(A))));
                awaitDatabaseWaiter();release.countDown();revoke.get(10,TimeUnit.SECONDS);
                assertThat(status.get(10,TimeUnit.SECONDS)).isEqualTo("SUPPORT_NOT_FOUND");assertThat(count()).isZero();
                assertThat(jdbc.queryForObject("SELECT status_version FROM tickets WHERE id=?",Long.class,ticket)).isZero();
            } finally {release.countDown();}
        }
    }
    @Test void already_authorized_change_finishes_before_waiting_revocation_then_future_replay_is_denied()throws Exception {
        claim();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);holdAppend(entered,release);
        var cmd=startCommand(UUID.randomUUID());
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                var status=pool.submit(()->statuses.changeStaff(ticket,cmd,IdentityFixtures.actor(A)));await(entered);
                var revoke=pool.submit(()->{revoke(A);return true;});awaitDatabaseWaiter();release.countDown();
                assertThat(status.get(10,TimeUnit.SECONDS).created()).isTrue();revoke.get(10,TimeUnit.SECONDS);
                assertThat(outcome(()->statuses.changeStaff(ticket,cmd,IdentityFixtures.actor(A)))).isEqualTo("SUPPORT_NOT_FOUND");
                assertThat(count()).isEqualTo(1);
            } finally {release.countDown();}
        }
    }
    @Test void lock_deadline_returns_safe_busy_error_without_a_status_event()throws Exception {
        claim();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            try {
                var holder=pool.submit(()->new TransactionTemplate(manager).execute(s->{repository.lock(ticket);locked.countDown();await(release);return null;}));
                await(locked);assertCode(send(A,staff(UUID.randomUUID(),"START",0,1,"Waiting"),503),"SUPPORT_BUSY");
                release.countDown();holder.get(10,TimeUnit.SECONDS);assertThat(count()).isZero();
            } finally {release.countDown();}
        }
    }

    private Command startCommand(UUID key){return new Command(key,TicketStatusAction.START,0,1L,"Start",null,null);}
    private Command resolveCommand(){return new Command(UUID.randomUUID(),TicketStatusAction.RESOLVE,1,1L,"Resolved",reply,inquiry);}
    private static String outcome(Runnable action){try{action.run();return "CREATED";}catch(TicketStatusException e){return e.code().name();}catch(SupportException e){return e.code().name();}}
    private static void await(CountDownLatch latch){try{assertThat(latch.await(8,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError("fixture interrupted");}}
    private void holdAppend(CountDownLatch entered,CountDownLatch release){doAnswer(call->{Object saved=call.callRealMethod();entered.countDown();await(release);return saved;}).when(repository).append(any(),anyLong(),any(),any(),any(),any(),any(),anyString(),any(),any());}
    private void awaitDatabaseWaiter()throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock'",Long.class)>0)return;Thread.sleep(10);}
        throw new AssertionError("expected database lock waiter");
    }

    private String base(){return "/api/tickets/"+ticket;}
    private void claim(){assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(A));}
    private JsonNode start()throws Exception{return send(A,staff(UUID.randomUUID(),"START",0,1,"Start"),201);}
    private void prepare()throws Exception{claim();start();reply=conversations.reply(ticket,"Synthetic resolution",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A)).message().id();confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(A));}
    private long follow(){return conversations.followUp(ticket,"New customer details",UUID.randomUUID(),IdentityFixtures.actor(C)).message().id();}
    private long id(String who){return new JdbcUserIdentityRepository(jdbc).findId(IdentityFixtures.key(who)).orElseThrow();}
    private void revoke(String who){jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,id(who));}
    private long count(){return jdbc.queryForObject("SELECT count(*) FROM ticket_status_events",Long.class);}
    private Map<String,Object> staff(UUID key,String action,long state,long assignment,String reason){var map=new LinkedHashMap<String,Object>();map.put("action",action);map.put("clientRequestId",key);map.put("expectedStatusVersion",state);map.put("expectedAssignmentVersion",assignment);map.put("reason",reason);return map;}
    private Map<String,Object> resolve(UUID key,long state,long assignment,long through){var map=staff(key,"RESOLVE",state,assignment,"Resolved");map.put("relatedReplyMessageId",reply);map.put("throughCustomerMessageId",through);return map;}
    private JsonNode send(String who,Map<String,Object> body,int expected)throws Exception{return postJson(who,"/status-changes",body,expected);}
    private JsonNode reopen(String who,UUID key,long state,String reason,int expected)throws Exception{return postJson(who,"/customer-status/reopen",Map.of("clientRequestId",key,"expectedStatusVersion",state,"reason",reason),expected);}
    private JsonNode postJson(String who,String suffix,Map<String,Object> body,int expected)throws Exception {
        var result=mvc.perform(post(base()+suffix).with(as(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expected));
        if(expected==200||expected==201)result.andExpect(header().string("Cache-Control","no-store"));
        return mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }
    private JsonNode read(String who,String suffix,int expected)throws Exception {
        var result=mvc.perform(get(base()+suffix).with(as(who))).andExpect(status().is(expected));
        if(expected==200)result.andExpect(header().string("Cache-Control","no-store"));
        byte[] body=result.andReturn().getResponse().getContentAsByteArray();return body.length==0?null:mapper.readTree(body);
    }
    private RequestPostProcessor as(String who){return user(IdentityFixtures.user(User.withUsername(who).password("synthetic-unused").roles(who.contains("agent")?new String[]{"USER","AGENT"}:new String[]{"USER"}).build()));}
    private static void assertCode(JsonNode response,String expected){assertThat(response.path("code").asString()).isEqualTo(expected);}
    private Map<String,List<Map<String,Object>>> snapshot(){var map=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:TABLES)map.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return map;}
    private Map<String,List<Map<String,Object>>> preserved(){var map=snapshot();map.remove("tickets");map.remove("ticket_status_events");return map;}
}
