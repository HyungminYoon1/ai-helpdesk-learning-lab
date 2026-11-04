package lab.helpdesk.ticket.web;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import lab.helpdesk.testing.ClassOwnedContextCleanupVerifier;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.identity.*;
import lab.helpdesk.support.*;
import lab.helpdesk.ticket.application.*;
import lab.helpdesk.ticket.repository.JdbcCustomerMessageConfirmationRepository;
import org.junit.jupiter.api.*;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = ClassOwnedContextCleanupVerifier.class, mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class CustomerMessageConfirmationHttpIntegrationTest {
    private static final String A="confirm-agent-a", B="confirm-agent-b", S="confirm-agent-supervisor",
            C="confirm-customer", OUT="confirm-agent-other";
    private static final List<String> TABLES=List.of("ticket_customer_message_confirmations","ticket_suggestion_categories",
            "ticket_suggestions","ai_suggestion_attempts","ai_suggestion_jobs","ticket_messages","ticket_assignment_events","tickets");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.6-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TicketReceiptApplicationService receipts;
    @Autowired TicketConversationService conversations;
    @Autowired TicketAssignmentService assignments;
    @Autowired CustomerMessageConfirmationService confirmations;
    @Autowired PlatformTransactionManager manager;
    @Autowired lab.helpdesk.ai.job.AiSuggestionJobClaimService claims;
    @Autowired lab.helpdesk.ai.suggestion.AiSuggestionResultService results;
    @MockitoSpyBean JdbcCustomerMessageConfirmationRepository repository;
    @MockitoBean AiSuggestionProvider provider;
    long ticket,inquiry,service;

    @BeforeEach void isolatedFixture() {
        assertThat(jdbc.execute((ConnectionCallback<Boolean>) c -> c.getMetaData().getURL().split("\\?",2)[0]
                .equals(POSTGRES.getJdbcUrl().split("\\?",2)[0]))).isTrue();
        for(String table:TABLES)jdbc.update("DELETE FROM "+table);
        for(String who:List.of(A,B,S,C,OUT))IdentityFixtures.bind(jdbc,who);
        service=jdbc.queryForObject("SELECT id FROM support_services WHERE code='synthetic-support'",Long.class);
        // Explicit test-owned reset, not a production membership regrant.
        for(String who:List.of(A,B,S))jdbc.update("UPDATE support_service_memberships SET active=true,support_role=? WHERE service_id=? AND user_id=?",
                who.equals(S)?"SUPERVISOR":"AGENT",service,id(who));
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,id(OUT));
        var received=receipts.receive("confirmation fixture","  original inquiry unchanged  ",IdentityFixtures.actor(C));
        ticket=received.ticket().id();inquiry=received.messageId();
        clearInvocations(repository,provider);
    }
    @AfterEach void noProvider() {verifyNoInteractions(provider);}

    @Test void unassigned_and_assigned_get_head_are_read_only_and_customer_cannot_read_staff_state() throws Exception {
        var before=snapshot();
        var state=current(A,200);
        assertThat(state.size()).isEqualTo(5);
        assertThat(state.path("assignmentVersion").asLong()).isZero();
        assertThat(state.path("effectiveThroughMessageId").isNull()).isTrue();
        assertThat(state.path("latestCustomerMessageId").asLong()).isEqualTo(inquiry);
        assertThat(state.path("needsConfirmation").asBoolean()).isTrue();
        assertThat(state.path("canConfirm").asBoolean()).isFalse();
        mvc.perform(head(currentPath()).with(as(A))).andExpect(status().isOk());
        current(C,403);current(OUT,404);
        assertThat(snapshot()).isEqualTo(before);
        claim();before=snapshot();current(A,200);current(B,200);current(S,200);
        mvc.perform(head(currentPath()).with(as(A))).andExpect(status().isOk());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void new_confirmation_commits_201_exact_replay_is_200_and_no_other_business_data_changes() throws Exception {
        claim();var before=snapshotWithoutConfirmations();UUID key=UUID.randomUUID();
        var first=confirm(A,key,1,inquiry,201);
        assertThat(first.path("confirmation").size()).isEqualTo(4);
        assertThat(first.path("confirmation").path("throughMessageId").asLong()).isEqualTo(inquiry);
        assertThat(first.path("currentConfirmation").path("effectiveThroughMessageId").asLong()).isEqualTo(inquiry);
        assertThat(first.path("currentConfirmation").path("needsConfirmation").asBoolean()).isFalse();
        assertThat(first.path("currentConfirmation").path("canConfirm").asBoolean()).isTrue();
        assertThat(confirm(A,key,1,inquiry,200).path("confirmation")).isEqualTo(first.path("confirmation"));
        assertThat(count()).isOne();
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM ticket_customer_message_confirmations",Long.class)).isEqualTo(id(A));
        assertThat(jdbc.queryForObject("SELECT client_request_id FROM ticket_customer_message_confirmations",UUID.class)).isEqualTo(key);
        assertThat(snapshotWithoutConfirmations()).isEqualTo(before);
    }

    @Test void later_lower_confirmation_is_retained_but_progress_uses_customer_message_max_not_audit_id() throws Exception {
        claim();long lower=follow(),higher=follow();
        var high=confirm(A,UUID.randomUUID(),1,higher,201);
        var low=confirm(A,UUID.randomUUID(),1,lower,201);
        assertThat(low.path("confirmation").path("id").asLong()).isGreaterThan(high.path("confirmation").path("id").asLong());
        assertThat(low.path("confirmation").path("throughMessageId").asLong()).isEqualTo(lower);
        assertThat(low.path("currentConfirmation").path("effectiveThroughMessageId").asLong()).isEqualTo(higher);
        assertThat(low.path("currentConfirmation").path("needsConfirmation").asBoolean()).isFalse();
        assertThat(jdbc.queryForList("SELECT through_message_id FROM ticket_customer_message_confirmations ORDER BY id",Long.class))
                .containsExactly(higher,lower);
        assertThat(count()).isEqualTo(2);
    }

    @Test void new_customer_message_is_not_silently_confirmed_by_an_old_replay_or_staff_reply() throws Exception {
        claim();UUID key=UUID.randomUUID();confirm(A,key,1,inquiry,201);
        long later=follow();
        conversations.reply(ticket,"staff answer",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A));
        var replay=confirm(A,key,1,inquiry,200).path("currentConfirmation");
        assertThat(replay.path("effectiveThroughMessageId").asLong()).isEqualTo(inquiry);
        assertThat(replay.path("latestCustomerMessageId").asLong()).isEqualTo(later);
        assertThat(replay.path("needsConfirmation").asBoolean()).isTrue();assertThat(count()).isOne();
    }

    @Test void changed_payload_conflicts_and_new_uuid_is_a_new_explicit_record() throws Exception {
        claim();long later=follow();UUID key=UUID.randomUUID();confirm(A,key,1,inquiry,201);
        assertThat(confirm(A,key,1,later,409).path("code").asString()).isEqualTo("CONFIRMATION_REQUEST_CONFLICT");
        assertThat(confirm(A,key,2,inquiry,409).path("code").asString()).isEqualTo("CONFIRMATION_REQUEST_CONFLICT");
        confirm(A,UUID.randomUUID(),1,inquiry,201);assertThat(count()).isEqualTo(2);
    }

    @Test void confirmation_submission_namespace_is_separate_from_message_submissions() throws Exception {
        claim();UUID key=UUID.randomUUID();
        conversations.reply(ticket,"official answer",key,null,1L,IdentityFixtures.actor(A));
        var before=snapshotWithoutConfirmations();confirm(A,key,1,inquiry,201);
        assertThat(count()).isOne();assertThat(snapshotWithoutConfirmations()).isEqualTo(before);
    }

    @Test void only_current_assignee_can_create_a_confirmation_and_version_is_not_automatically_updated() throws Exception {
        confirm(A,UUID.randomUUID(),1,inquiry,403);claim();
        confirm(B,UUID.randomUUID(),1,inquiry,403);confirm(S,UUID.randomUUID(),1,inquiry,403);
        assertThat(confirm(A,UUID.randomUUID(),2,inquiry,409).path("code").asString()).isEqualTo("ASSIGNMENT_VERSION_CONFLICT");
        assertThat(current(B,200).path("canConfirm").asBoolean()).isFalse();assertThat(count()).isZero();
    }

    @Test void handoff_and_a_to_b_to_a_never_copy_old_progress_but_old_submission_replay_is_separate() throws Exception {
        claim();UUID key=UUID.randomUUID();var original=confirm(A,key,1,inquiry,201).path("confirmation");
        assignments.transfer(ticket,UUID.randomUUID(),1,id(B),"handoff",IdentityFixtures.actor(A));
        var replay=confirm(A,key,1,inquiry,200);
        assertThat(replay.path("confirmation")).isEqualTo(original);
        assertThat(replay.path("currentConfirmation").path("assignmentVersion").asLong()).isEqualTo(2);
        assertThat(replay.path("currentConfirmation").path("effectiveThroughMessageId").isNull()).isTrue();
        assertThat(replay.path("currentConfirmation").path("canConfirm").asBoolean()).isFalse();
        confirm(A,UUID.randomUUID(),1,inquiry,403);
        confirm(B,UUID.randomUUID(),1,inquiry,409);confirm(B,UUID.randomUUID(),2,inquiry,201);
        assignments.transfer(ticket,UUID.randomUUID(),2,id(A),"return",IdentityFixtures.actor(B));
        var returned=confirm(A,key,1,inquiry,200).path("currentConfirmation");
        assertThat(returned.path("assignmentVersion").asLong()).isEqualTo(3);
        assertThat(returned.path("effectiveThroughMessageId").isNull()).isTrue();
        assertThat(returned.path("needsConfirmation").asBoolean()).isTrue();
        confirm(A,key,3,inquiry,409);assertThat(count()).isEqualTo(2);
    }

    @Test void revocation_blocks_even_saved_replays_and_does_not_erase_history() throws Exception {
        claim();UUID key=UUID.randomUUID();confirm(A,key,1,inquiry,201);
        jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,id(A));
        current(A,404);confirm(A,key,1,inquiry,404);
        mvc.perform(head(currentPath()).with(as(A))).andExpect(status().isNotFound());
        assertThat(current(B,200).path("effectiveThroughMessageId").asLong()).isEqualTo(inquiry);
        assertThat(count()).isOne();
    }

    @Test void csrf_role_linked_identity_and_service_access_are_required() throws Exception {
        claim();String json=payload(UUID.randomUUID(),1,inquiry);
        mvc.perform(get(currentPath())).andExpect(status().isUnauthorized());
        mvc.perform(head(currentPath())).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
        mvc.perform(post(path()).with(as(A)).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        confirm(C,UUID.randomUUID(),1,inquiry,403);confirm(OUT,UUID.randomUUID(),1,inquiry,404);
        mvc.perform(get(currentPath()).with(user(A).roles("AGENT"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_UNLINKED"));
        assertThat(count()).isZero();
    }

    @Test void target_must_be_a_customer_message_of_this_ticket() throws Exception {
        claim();long reply=conversations.reply(ticket,"staff answer",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A)).message().id();
        long other=receipts.receive("other","other inquiry",IdentityFixtures.actor(C)).messageId();
        for(long wrong:new long[]{reply,other,Long.MAX_VALUE}) {
            assertThat(confirm(A,UUID.randomUUID(),1,wrong,400).path("code").asString()).isEqualTo("INVALID_CONFIRMATION_TARGET");
        }
        assertThat(count()).isZero();
    }

    @Test void resolved_ticket_confirmation_does_not_reopen_or_resolve_any_ticket() throws Exception {
        claim();jdbc.update("UPDATE tickets SET status='RESOLVED' WHERE id=?",ticket);
        var before=snapshotWithoutConfirmations();confirm(A,UUID.randomUUID(),1,inquiry,201);
        assertThat(snapshotWithoutConfirmations()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{",
            "{\"clientRequestId\":\"bad\",\"expectedAssignmentVersion\":1,\"throughMessageId\":1}",
            "{\"clientRequestId\":\"1-1-1-1-1\",\"expectedAssignmentVersion\":1,\"throughMessageId\":1}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":0,\"throughMessageId\":1}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":1,\"throughMessageId\":0}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":1.0,\"throughMessageId\":1}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":1,\"throughMessageId\":\"1\"}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":1,\"throughMessageId\":9223372036854775808}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":1,\"throughMessageId\":null}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":1,\"throughMessageId\":1,\"actorUserId\":1}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":1,\"throughMessageId\":1,\"throughMessageId\":2}",
            "{\"clientRequestId\":\"11111111-1111-4111-8111-111111111111\",\"expectedAssignmentVersion\":1,\"throughMessageId\":1} {}"})
    void malformed_request_is_rejected_before_confirmation_lookup(String json) throws Exception {
        mvc.perform(post(path()).with(as(A)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);assertThat(count()).isZero();
    }

    @Test void actual_stream_limit_is_bounded_independently_of_content_length() throws Exception {
        claim();String json=payload(UUID.randomUUID(),1,inquiry);
        int padding=CreateCustomerMessageConfirmationRequest.MAX_BODY_BYTES-json.getBytes(StandardCharsets.UTF_8).length;
        mvc.perform(post(path()).with(as(A)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json+" ".repeat(padding)))
                .andExpect(status().isCreated());
        mvc.perform(post(path()).with(as(A)).with(csrf()).header("Content-Length","1")
                .contentType(MediaType.APPLICATION_JSON).content(json+" ".repeat(padding+1)))
                .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
        assertThat(count()).isOne();
    }

    @ParameterizedTest @ValueSource(longs={0,-1})
    void nonpositive_ticket_is_rejected(long value) throws Exception {
        mvc.perform(get("/api/tickets/{id}/customer-message-confirmations/current",value).with(as(A))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tickets/{id}/customer-message-confirmations",value).with(as(A)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(payload(UUID.randomUUID(),1,inquiry))).andExpect(status().isBadRequest());
        assertThat(count()).isZero();
    }

    @Test void no_customer_messages_is_distinct_from_not_yet_confirmed() throws Exception {
        long empty=jdbc.queryForObject("INSERT INTO tickets(title,status,service_id) VALUES('legacy empty','OPEN',?) RETURNING id",Long.class,service);
        assignments.claim(empty,UUID.randomUUID(),0,IdentityFixtures.actor(A));
        mvc.perform(get("/api/tickets/{id}/customer-message-confirmations/current",empty).with(as(A))).andExpect(status().isOk())
                .andExpect(jsonPath("$.latestCustomerMessageId").isEmpty())
                .andExpect(jsonPath("$.effectiveThroughMessageId").isEmpty())
                .andExpect(jsonPath("$.needsConfirmation").value(false)).andExpect(jsonPath("$.canConfirm").value(false));
    }

    @Test void concurrent_same_submission_commits_one_record() throws Exception {
        claim();UUID key=UUID.randomUUID();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{await(start);return confirmations.confirm(ticket,key,1,inquiry,IdentityFixtures.actor(A));});
            var second=pool.submit(()->{await(start);return confirmations.confirm(ticket,key,1,inquiry,IdentityFixtures.actor(A));});
            start.countDown();var one=first.get(10,TimeUnit.SECONDS);var two=second.get(10,TimeUnit.SECONDS);
            assertThat(one.confirmation().id()).isEqualTo(two.confirmation().id());assertThat(one.created()).isNotEqualTo(two.created());
        }
        assertThat(count()).isOne();
    }

    @Test void confirmation_commits_before_waiting_handoff_and_history_stays_in_old_assignment() throws Exception {
        claim();var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);holdInsert(inserted,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(A)));
            await(inserted);
            var handoff=pool.submit(()->assignments.transfer(ticket,UUID.randomUUID(),1,id(B),"handoff",IdentityFixtures.actor(A)));
            try {awaitDatabaseWaiter();} finally {release.countDown();}
            assertThat(first.get(10,TimeUnit.SECONDS).created()).isTrue();handoff.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();}
        var state=current(B,200);assertThat(state.path("assignmentVersion").asLong()).isEqualTo(2);
        assertThat(state.path("effectiveThroughMessageId").isNull()).isTrue();assertThat(count()).isOne();
    }

    @Test void waiting_old_assignee_is_rejected_after_handoff_commits() throws Exception {
        claim();var changed=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var handoff=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                assignments.transfer(ticket,UUID.randomUUID(),1,id(B),"handoff",IdentityFixtures.actor(A));changed.countDown();await(release);
            }));await(changed);
            var old=pool.submit(()->outcome(A));
            try {awaitDatabaseWaiter();} finally {release.countDown();}
            handoff.get(10,TimeUnit.SECONDS);assertThat(old.get(10,TimeUnit.SECONDS)).isEqualTo("NOT_ASSIGNEE");
        } finally {release.countDown();}
        assertThat(count()).isZero();
    }

    @Test void membership_revocation_commits_before_waiting_confirmation_and_blocks_it() throws Exception {
        claim();var changed=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoke=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,id(A));
                changed.countDown();await(release);
            }));await(changed);
            var confirmation=pool.submit(()->outcome(A));
            try {awaitDatabaseWaiter();} finally {release.countDown();}
            revoke.get(10,TimeUnit.SECONDS);assertThat(confirmation.get(10,TimeUnit.SECONDS)).isEqualTo("SUPPORT_NOT_FOUND");
        } finally {release.countDown();}
        assertThat(count()).isZero();
    }

    @Test void a_new_customer_message_waits_for_confirmation_and_remains_unconfirmed_after_its_own_commit() throws Exception {
        claim();var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);holdInsert(inserted,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var confirmation=pool.submit(()->confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(A)));
            await(inserted);
            var followup=pool.submit(()->follow());
            try {awaitDatabaseWaiter();} finally {release.countDown();}
            var saved=confirmation.get(10,TimeUnit.SECONDS);long latest=followup.get(10,TimeUnit.SECONDS);
            assertThat(saved.currentConfirmation().latestCustomerMessageId()).isEqualTo(inquiry);
            assertThat(saved.currentConfirmation().needsConfirmation()).isFalse();
            var state=current(A,200);
            assertThat(state.path("latestCustomerMessageId").asLong()).isEqualTo(latest);
            assertThat(state.path("effectiveThroughMessageId").asLong()).isEqualTo(inquiry);
            assertThat(state.path("needsConfirmation").asBoolean()).isTrue();
        } finally {release.countDown();}
    }

    @Test void a_customer_message_commits_first_but_waiting_confirmation_does_not_expand_its_explicit_target() throws Exception {
        claim();var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var followup=pool.submit(()->new TransactionTemplate(manager).execute(status->{
                long latest=follow();inserted.countDown();await(release);return latest;
            }));await(inserted);
            var confirmation=pool.submit(()->confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(A)));
            try {awaitDatabaseWaiter();} finally {release.countDown();}
            long latest=followup.get(10,TimeUnit.SECONDS);var saved=confirmation.get(10,TimeUnit.SECONDS);
            assertThat(saved.confirmation().throughMessageId()).isEqualTo(inquiry);
            assertThat(saved.currentConfirmation().latestCustomerMessageId()).isEqualTo(latest);
            assertThat(saved.currentConfirmation().needsConfirmation()).isTrue();
        } finally {release.countDown();}
    }

    @Test void current_projection_reads_the_new_assignment_together_with_its_own_progress_after_a_handoff() throws Exception {
        claim();confirm(A,UUID.randomUUID(),1,inquiry,201);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();await(release);return call.callRealMethod();}).when(repository).current(ticket);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var reader=pool.submit(()->confirmations.read(ticket,IdentityFixtures.actor(A)));await(entered);
            try {assignments.transfer(ticket,UUID.randomUUID(),1,id(B),"handoff",IdentityFixtures.actor(A));}
            finally {release.countDown();}
            var state=reader.get(10,TimeUnit.SECONDS);
            assertThat(state.assignmentVersion()).isEqualTo(2);
            assertThat(state.effectiveThroughMessageId()).isNull();assertThat(state.canConfirm()).isFalse();
        } finally {release.countDown();}
    }

    @Test void committed_confirmation_survives_waiting_membership_revocation() throws Exception {
        claim();var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);holdInsert(inserted,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var confirmation=pool.submit(()->confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(A)));
            await(inserted);
            var revoke=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->
                    jdbc.update("UPDATE support_service_memberships SET active=false WHERE service_id=? AND user_id=?",service,id(A))));
            try {awaitDatabaseWaiter();} finally {release.countDown();}
            assertThat(confirmation.get(10,TimeUnit.SECONDS).created()).isTrue();revoke.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();}
        current(A,404);assertThat(count()).isOne();
    }

    @Test void ticket_lock_timeout_is_safe_503_without_new_record() throws Exception {
        claim();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var holder=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                jdbc.queryForList("SELECT id FROM tickets WHERE id=? FOR UPDATE",ticket);locked.countDown();await(release);
            }));await(locked);
            try {assertThat(confirm(A,UUID.randomUUID(),1,inquiry,503).path("code").asString()).isEqualTo("SUPPORT_BUSY");}
            finally {release.countDown();holder.get(10,TimeUnit.SECONDS);}
        } finally {release.countDown();}
        assertThat(count()).isZero();
    }

    @Test void failure_after_insert_preserves_nonempty_ai_results_and_all_prior_conversation_assignment_and_confirmation_rows() throws Exception {
        claim();confirm(A,UUID.randomUUID(),1,inquiry,201);long later=follow();
        conversations.reply(ticket,"stored reply",UUID.randomUUID(),null,1L,IdentityFixtures.actor(A));
        var ai=claims.claimNextPending().orElseThrow();
        var output=new lab.helpdesk.ai.validation.AiSuggestionOutputValidator(200).validate("""
                {"decision":"SUGGEST","summary":"Synthetic original inquiry","categories":["ACCOUNT"],"priority":"NORMAL"}
                """);
        assertThat(results.complete(ai,output)).isEqualTo(lab.helpdesk.ai.suggestion.AiSuggestionCompletion.STORED);
        var before=snapshot();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("controlled confirmation failure");})
                .when(repository).insert(anyLong(),anyLong(),any(UUID.class),anyLong(),anyLong());
        confirm(A,UUID.randomUUID(),1,later,500);assertThat(snapshot()).isEqualTo(before);
    }

    @Test void actual_deferred_commit_failure_never_returns_201_and_preserves_prior_rows() throws Exception {
        claim();var before=snapshot();
        jdbc.execute("CREATE TABLE confirmation_commit_guard (id BIGINT REFERENCES tickets(id) DEFERRABLE INITIALLY DEFERRED)");
        jdbc.execute("CREATE FUNCTION confirmation_commit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN INSERT INTO confirmation_commit_guard VALUES (9223372036854775807); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER confirmation_commit_failure AFTER INSERT ON ticket_customer_message_confirmations FOR EACH ROW EXECUTE FUNCTION confirmation_commit_failure()");
        try {confirm(A,UUID.randomUUID(),1,inquiry,500);assertThat(snapshot()).isEqualTo(before);}
        finally {jdbc.execute("DROP TRIGGER confirmation_commit_failure ON ticket_customer_message_confirmations");
            jdbc.execute("DROP FUNCTION confirmation_commit_failure()");jdbc.execute("DROP TABLE confirmation_commit_guard");}
    }

    private String path(){return "/api/tickets/"+ticket+"/customer-message-confirmations";}
    private String currentPath(){return path()+"/current";}
    private long id(String who){return new JdbcUserIdentityRepository(jdbc).findId(IdentityFixtures.key(who)).orElseThrow();}
    private void claim(){assignments.claim(ticket,UUID.randomUUID(),0,IdentityFixtures.actor(A));}
    private long follow(){return conversations.followUp(ticket,"customer detail",UUID.randomUUID(),IdentityFixtures.actor(C)).message().id();}
    private long count(){return jdbc.queryForObject("SELECT count(*) FROM ticket_customer_message_confirmations",Long.class);}
    private String payload(UUID key,long version,long through){return mapper.writeValueAsString(Map.of("clientRequestId",key,"expectedAssignmentVersion",version,"throughMessageId",through));}
    private JsonNode confirm(String who,UUID key,long version,long through,int expected)throws Exception {
        var result=mvc.perform(post(path()).with(as(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload(key,version,through)))
                .andExpect(status().is(expected));
        if(expected==200||expected==201)result.andExpect(header().string("Cache-Control","no-store"));
        return mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
    }
    private JsonNode current(String who,int expected)throws Exception {
        var result=mvc.perform(get(currentPath()).with(as(who))).andExpect(status().is(expected));
        if(expected==200)result.andExpect(header().string("Cache-Control","no-store"));
        String body=result.andReturn().getResponse().getContentAsString();return body.isEmpty()?null:mapper.readTree(body);
    }
    private RequestPostProcessor as(String name){return user(IdentityFixtures.user(User.withUsername(name).password("synthetic-unused")
            .roles(name.contains("agent")?new String[]{"USER","AGENT"}:new String[]{"USER"}).build()));}
    private Map<String,List<Map<String,Object>>> snapshot(){
        var all=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:TABLES)all.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));return all;
    }
    private Map<String,List<Map<String,Object>>> snapshotWithoutConfirmations(){var all=snapshot();all.remove("ticket_customer_message_confirmations");return all;}
    private static void await(CountDownLatch latch){try{assertThat(latch.await(8,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError("fixture interrupted");}}
    private void holdInsert(CountDownLatch inserted,CountDownLatch release){
        doAnswer(call->{Object result=call.callRealMethod();inserted.countDown();await(release);return result;})
                .when(repository).insert(anyLong(),anyLong(),any(UUID.class),anyLong(),anyLong());
    }
    private String outcome(String who){try{confirmations.confirm(ticket,UUID.randomUUID(),1,inquiry,IdentityFixtures.actor(who));return "CREATED";}
        catch(SupportException e){return e.code().name();}}
    private void awaitDatabaseWaiter() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){
            if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock'",Long.class)>0)return;
            Thread.sleep(10);
        }
        throw new AssertionError("expected database lock waiter");
    }
}
