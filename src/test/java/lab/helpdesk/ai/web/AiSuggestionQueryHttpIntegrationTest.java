package lab.helpdesk.ai.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobFailureCode;
import lab.helpdesk.ai.job.AiJobStatus;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.job.JdbcAiSuggestionJobResultRepository;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ai.query.AiSuggestionQueryService;
import lab.helpdesk.ai.query.JdbcAiSuggestionQueryRepository;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.suggestion.JdbcTicketSuggestionRepository;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;
import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import lab.helpdesk.ticket.application.TicketReceiptResult;
import lab.helpdesk.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("postgres")
@Testcontainers
@Import(AiSuggestionQueryHttpIntegrationTest.TestUsers.class)
@ExtendWith(OutputCaptureExtension.class)
class AiSuggestionQueryHttpIntegrationTest {

    private static final String USER_NAME = "query-user";
    private static final String AGENT_NAME = "query-agent";
    private static final String LOGIN_SECRET = UUID.randomUUID().toString();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    private final AiSuggestionOutputValidator validator = new AiSuggestionOutputValidator(200);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TicketRepository tickets;
    @Autowired private TicketReceiptApplicationService receipts;
    @Autowired private JdbcTicketSuggestionRepository suggestions;
    @Autowired private JdbcAiSuggestionJobResultRepository jobResults;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AiSuggestionQueryService queries;
    @MockitoSpyBean private JdbcAiSuggestionQueryRepository queryRepository;
    @MockitoSpyBean private AiSuggestionJobClaimService claims;
    @MockitoSpyBean private AiSuggestionResultService results;
    @MockitoBean private AiSuggestionProvider provider;

    @BeforeEach
    void prepare_only_the_verified_test_database() {
        Boolean isolated = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) {
            throw new IllegalStateException("query test cleanup requires the isolated test database");
        }
        jdbcTemplate.update("DELETE FROM ticket_suggestion_categories");
        jdbcTemplate.update("DELETE FROM ticket_suggestions");
        jdbcTemplate.update("DELETE FROM ai_suggestion_attempts");
        jdbcTemplate.update("DELETE FROM ai_suggestion_jobs");
        jdbcTemplate.update("DELETE FROM ticket_messages");
        jdbcTemplate.update("DELETE FROM tickets");
        reset(queryRepository);
        clearInvocations(queries, claims, results, provider);
    }

    @Test
    void anonymous_get_is_401_before_controller_or_query_service() throws Exception {
        long id = receive().ticket().id();
        MvcResult result = mockMvc.perform(get("/api/tickets/{id}/ai-suggestion", id))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION)).andReturn();
        assertThat(result.getHandler()).isNull();
        verify(queries, never()).findByTicketId(anyLong());
        verifyNoInteractions(queryRepository, claims, results, provider);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, Long.MAX_VALUE})
    void user_is_403_even_for_a_missing_ticket_before_query(long id) throws Exception {
        MvcResult result = read(id, sessionFor(USER_NAME)).andExpect(status().isForbidden()).andReturn();
        assertThat(result.getHandler()).isNull();
        verify(queries, never()).findByTicketId(anyLong());
        verifyNoInteractions(queryRepository, claims, results, provider);
    }

    @Test
    void anonymous_head_is_401_before_the_implicit_get_handler() throws Exception {
        MvcResult result = mockMvc.perform(head("/api/tickets/{id}/ai-suggestion", 7))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getHandler()).isNull();
        verifyNoInteractions(queries, queryRepository, claims, results, provider);
    }

    @Test
    void user_head_cannot_bypass_the_agent_only_get_rule() throws Exception {
        MvcResult result = mockMvc.perform(head("/api/tickets/{id}/ai-suggestion", 7)
                        .session(sessionFor(USER_NAME)))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(result.getHandler()).isNull();
        verifyNoInteractions(queries, queryRepository, claims, results, provider);
    }

    @Test
    void agent_head_is_an_authorized_read_without_ai_side_effects() throws Exception {
        TicketReceiptResult receipt = receive();
        var before = databaseSnapshot();
        mockMvc.perform(head("/api/tickets/{id}/ai-suggestion", receipt.ticket().id())
                        .session(sessionFor(AGENT_NAME)))
                .andExpect(status().isOk()).andExpect(handler().handlerType(AiSuggestionQueryController.class));
        verify(queries).findByTicketId(receipt.ticket().id());
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void unsupported_post_does_not_relax_the_role_rule_or_start_a_job() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tickets/{id}/ai-suggestion", 7)
                        .session(sessionFor(USER_NAME)).with(csrf()))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(result.getHandler()).isNull();
        verifyNoInteractions(queries, queryRepository, claims, results, provider);
    }

    @Test
    void agent_get_without_csrf_reads_pending_and_repeated_reads_change_no_rows() throws Exception {
        TicketReceiptResult receipt = receive();
        var before = databaseSnapshot();
        var session = sessionFor(AGENT_NAME);
        for (int repeat = 0; repeat < 3; repeat++) {
            read(receipt.ticket().id(), session).andExpect(status().isOk())
                    .andExpect(handler().handlerType(AiSuggestionQueryController.class))
                    .andExpect(jsonPath("$.ticketId").value(receipt.ticket().id()))
                    .andExpect(jsonPath("$.job.id").value(receipt.jobId()))
                    .andExpect(jsonPath("$.job.status").value("PENDING"))
                    .andExpect(jsonPath("$.job.failureCode").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.suggestion").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void old_ticket_without_message_or_job_returns_explicit_null_without_creation() throws Exception {
        long id = tickets.save(new Ticket("기존 제목 전용 Ticket"));
        var before = databaseSnapshot();
        MvcResult result = read(id, sessionFor(AGENT_NAME)).andExpect(status().isOk()).andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.has("job")).isTrue();
        assertThat(body.path("job").isNull()).isTrue();
        assertThat(body.has("suggestion")).isTrue();
        assertThat(body.path("suggestion").isNull()).isTrue();
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void agent_missing_ticket_is_404_not_normal_null() throws Exception {
        read(Long.MAX_VALUE, sessionFor(AGENT_NAME)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("ticket not found"));
        verifyNoInteractions(claims, results, provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number"})
    void invalid_id_is_400_not_a_database_failure(String id) throws Exception {
        mockMvc.perform(get("/api/tickets/{id}/ai-suggestion", id).session(sessionFor(AGENT_NAME)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(queryRepository, claims, results, provider);
    }

    @Test
    void running_with_expired_deadline_is_read_without_marking_failed_or_retrying() throws Exception {
        TicketReceiptResult receipt = receive();
        claims.claimNextPending().orElseThrow();
        jdbcTemplate.update("""
                UPDATE ai_suggestion_jobs
                SET first_started_at = clock_timestamp() - INTERVAL '10 minutes',
                    processing_deadline_at = clock_timestamp() - INTERVAL '1 minute',
                    lease_expires_at = clock_timestamp() - INTERVAL '2 minutes'
                WHERE id = ?
                """, receipt.jobId());
        var before = databaseSnapshot();
        clearInvocations(claims);
        read(receipt.ticket().id(), sessionFor(AGENT_NAME)).andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("RUNNING"))
                .andExpect(jsonPath("$.suggestion").value(org.hamcrest.Matchers.nullValue()));
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void pending_retry_does_not_publish_the_previous_failure_as_final_failure() throws Exception {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThat(claims.scheduleOutputRepair(claim)).isTrue();
        clearInvocations(claims);
        var before = databaseSnapshot();
        read(receipt.ticket().id(), sessionFor(AGENT_NAME)).andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("PENDING"))
                .andExpect(jsonPath("$.job.failureCode").value(org.hamcrest.Matchers.nullValue()));
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @ParameterizedTest
    @EnumSource(AiJobFailureCode.class)
    void failed_job_is_a_200_read_with_only_a_known_failure_code(AiJobFailureCode code) throws Exception {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        assertThat(claims.failIfCurrent(claim, code)).isTrue();
        clearInvocations(claims);
        var before = databaseSnapshot();
        MvcResult result = read(receipt.ticket().id(), sessionFor(AGENT_NAME))
                .andExpect(status().isOk()).andExpect(jsonPath("$.job.status").value("FAILED"))
                .andExpect(jsonPath("$.job.failureCode").value(code.name()))
                .andExpect(jsonPath("$.suggestion").value(org.hamcrest.Matchers.nullValue())).andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("job").size()).isEqualTo(3);
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void abstained_keeps_its_job_and_is_not_failed_or_absent() throws Exception {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        results.complete(claim, abstain());
        clearInvocations(claims, results);
        var before = databaseSnapshot();
        read(receipt.ticket().id(), sessionFor(AGENT_NAME)).andExpect(status().isOk())
                .andExpect(jsonPath("$.job.id").value(receipt.jobId()))
                .andExpect(jsonPath("$.job.status").value("ABSTAINED"))
                .andExpect(jsonPath("$.suggestion").value(org.hamcrest.Matchers.nullValue()));
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void succeeded_restores_summary_categories_priority_and_review_status() throws Exception {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        results.complete(claim, suggestion());
        clearInvocations(claims, results);
        var before = databaseSnapshot();
        MvcResult result = read(receipt.ticket().id(), sessionFor(AGENT_NAME)).andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.suggestion.summary").value("<strong>합성 로그인·결제 문의</strong>"))
                .andExpect(jsonPath("$.suggestion.categories[0]").value("ACCOUNT"))
                .andExpect(jsonPath("$.suggestion.categories[1]").value("BILLING"))
                .andExpect(jsonPath("$.suggestion.priority").value("UNDETERMINED"))
                .andExpect(jsonPath("$.suggestion.reviewStatus").value("PENDING_REVIEW")).andReturn();
        var body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("suggestion").size()).isEqualTo(5);
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void later_message_does_not_replace_the_initial_message_job() throws Exception {
        TicketReceiptResult receipt = receive();
        Long messageId = jdbcTemplate.queryForObject("""
                INSERT INTO ticket_messages (ticket_id, body, author_username)
                VALUES (?, '뒤에 추가한 메시지', 'synthetic-author') RETURNING id
                """, Long.class, receipt.ticket().id());
        jdbcTemplate.update("INSERT INTO ai_suggestion_jobs (input_message_id, status) VALUES (?, 'PENDING')", messageId);
        read(receipt.ticket().id(), sessionFor(AGENT_NAME)).andExpect(status().isOk())
                .andExpect(jsonPath("$.job.id").value(receipt.jobId()));
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void an_initial_message_without_a_job_is_not_replaced_by_a_later_job() throws Exception {
        long ticketId = tickets.save(new Ticket("Job 없는 최초 메시지"));
        jdbcTemplate.update("""
                INSERT INTO ticket_messages (ticket_id, body, author_username)
                VALUES (?, '최초 메시지', 'synthetic-author')
                """, ticketId);
        Long laterMessageId = jdbcTemplate.queryForObject("""
                INSERT INTO ticket_messages (ticket_id, body, author_username)
                VALUES (?, '후속 메시지', 'synthetic-author') RETURNING id
                """, Long.class, ticketId);
        jdbcTemplate.update("INSERT INTO ai_suggestion_jobs (input_message_id, status) VALUES (?, 'PENDING')", laterMessageId);
        var before = databaseSnapshot();
        read(ticketId, sessionFor(AGENT_NAME)).andExpect(status().isOk())
                .andExpect(jsonPath("$.job").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.suggestion").value(org.hamcrest.Matchers.nullValue()));
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void http_query_runs_inside_a_read_only_postgres_transaction() throws Exception {
        TicketReceiptResult receipt = receive();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(jdbcTemplate.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
            return invocation.callRealMethod();
        }).when(queryRepository).findByTicketId(receipt.ticket().id());
        read(receipt.ticket().id(), sessionFor(AGENT_NAME)).andExpect(status().isOk());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void succeeded_without_suggestion_is_safe_500_and_does_not_repair_the_job(CapturedOutput output)
            throws Exception {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        results.complete(claim, abstain());
        jdbcTemplate.update("UPDATE ai_suggestion_jobs SET status = 'SUCCEEDED' WHERE id = ?", receipt.jobId());
        clearInvocations(claims, results);
        var before = databaseSnapshot();
        read(receipt.ticket().id(), sessionFor(AGENT_NAME)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AI_RESULT_INCONSISTENT"));
        assertThat(output.getAll()).contains("code=AI_RESULT_INCONSISTENT");
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void missing_category_is_safe_500_not_an_empty_valid_suggestion() throws Exception {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        results.complete(claim, suggestion());
        jdbcTemplate.update("DELETE FROM ticket_suggestion_categories");
        clearInvocations(claims, results);
        var before = databaseSnapshot();
        read(receipt.ticket().id(), sessionFor(AGENT_NAME)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AI_RESULT_INCONSISTENT"));
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void database_read_failure_has_fixed_code_and_no_raw_cause_in_response_or_log(CapturedOutput output)
            throws Exception {
        TicketReceiptResult receipt = receive();
        String sentinel = "SYNTHETIC_QUERY_CAUSE_NOT_FOR_PUBLIC_OUTPUT";
        doThrow(new DataAccessResourceFailureException(sentinel))
                .when(queryRepository).findByTicketId(receipt.ticket().id());
        var before = databaseSnapshot();
        MvcResult result = read(receipt.ticket().id(), sessionFor(AGENT_NAME))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AI_RESULT_QUERY_FAILED")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(sentinel, "SQLException");
        assertThat(output.getAll()).doesNotContain(sentinel);
        assertThat(databaseSnapshot()).isEqualTo(before);
        verifyNoInteractions(claims, results, provider);
    }

    @Test
    void uncommitted_completion_is_not_partially_visible_or_blocking_a_plain_read() throws Exception {
        TicketReceiptResult receipt = receive();
        AiJobClaim claim = claims.claimNextPending().orElseThrow();
        var session = sessionFor(AGENT_NAME);
        CountDownLatch sqlDone = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                assertThat(jobResults.lockCurrentExecution(claim)).isTrue();
                suggestions.save(claim.jobId(), suggestion());
                assertThat(jobResults.finishIfCurrent(claim, AiJobStatus.SUCCEEDED)).isTrue();
                sqlDone.countDown();
                await(allowCommit);
            }));
            try {
                await(sqlDone);
                var reader = executor.submit(() -> read(receipt.ticket().id(), session)
                        .andExpect(status().isOk()).andExpect(jsonPath("$.job.status").value("RUNNING"))
                        .andExpect(jsonPath("$.suggestion").value(org.hamcrest.Matchers.nullValue())));
                reader.get(5, TimeUnit.SECONDS);
            } finally {
                allowCommit.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
        }
        read(receipt.ticket().id(), session).andExpect(status().isOk())
                .andExpect(jsonPath("$.job.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.suggestion.categories.length()").value(2));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_suggestion_attempts", Integer.class)).isOne();
        verifyNoInteractions(provider);
    }

    private ResultActions read(long id, MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/api/tickets/{id}/ai-suggestion", id).session(session));
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult login = mockMvc.perform(formLogin().user(username).password(LOGIN_SECRET))
                .andExpect(authenticated().withUsername(username)).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private TicketReceiptResult receive() {
        return receipts.receive("합성 문의", "고객 원문은 보존합니다.", "synthetic-author");
    }

    private ContractValidatedOutput suggestion() {
        return validator.validate("""
                {"decision":"SUGGEST","summary":"<strong>합성 로그인·결제 문의</strong>",
                 "categories":["BILLING","ACCOUNT"],"priority":"UNDETERMINED"}
                """);
    }

    private ContractValidatedOutput abstain() {
        return validator.validate("""
                {"decision":"ABSTAIN","summary":null,"categories":null,"priority":null}
                """);
    }

    private List<List<Map<String, Object>>> databaseSnapshot() {
        return List.of(
                jdbcTemplate.queryForList("SELECT * FROM tickets ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM ticket_messages ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM ai_suggestion_jobs ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM ai_suggestion_attempts ORDER BY job_id, attempt_number"),
                jdbcTemplate.queryForList("SELECT * FROM ticket_suggestions ORDER BY id"),
                jdbcTemplate.queryForList("SELECT * FROM ticket_suggestion_categories ORDER BY suggestion_id, category"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("query test synchronization timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("query test synchronization interrupted");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestUsers {
        @Bean
        UserDetailsService queryTestUsers(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(
                    User.withUsername(USER_NAME).password(encoder.encode(LOGIN_SECRET)).roles("USER").build(),
                    User.withUsername(AGENT_NAME).password(encoder.encode(LOGIN_SECRET)).roles("AGENT").build());
        }
    }
}
