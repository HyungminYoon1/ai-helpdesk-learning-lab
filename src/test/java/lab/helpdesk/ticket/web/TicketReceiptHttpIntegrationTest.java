package lab.helpdesk.ticket.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import lab.helpdesk.ticket.application.TicketReceiptApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("postgres")
@Testcontainers
@Import(TicketReceiptHttpIntegrationTest.TestUsers.class)
@ExtendWith(OutputCaptureExtension.class)
class TicketReceiptHttpIntegrationTest {

    private static final String USER_NAME = "receipt-user-a";
    private static final String AGENT_NAME = "receipt-agent-b";
    private static final String LOGIN_SECRET = UUID.randomUUID().toString();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @MockitoSpyBean private TicketReceiptApplicationService receipts;

    @BeforeEach
    void prepare_isolated_test_database() {
        Boolean isolated = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        if (!Boolean.TRUE.equals(isolated)) {
            throw new IllegalStateException("refusing to modify a non-test database");
        }
        jdbcTemplate.update("DELETE FROM ai_suggestion_jobs");
        jdbcTemplate.update("DELETE FROM ticket_messages");
        jdbcTemplate.update("DELETE FROM tickets");
        clearInvocations(receipts);
    }

    @ParameterizedTest
    @ValueSource(strings = {USER_NAME, AGENT_NAME})
    void authenticated_author_and_original_body_are_committed_through_http(String username)
            throws Exception {
        String original = "  로그인 링크가 만료됩니다.\n새 링크를 요청합니다.  ";
        MvcResult result = create(sessionFor(username), payload(original))
                .andExpect(status().isCreated())
                .andExpect(handler().handlerType(TicketReceiptController.class))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        long ticketId = objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/api/tickets/" + ticketId);
        assertRowCounts(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT body FROM ticket_messages WHERE ticket_id = ?", String.class, ticketId))
                .isEqualTo(original);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT author_username FROM ticket_messages WHERE ticket_id = ?", String.class, ticketId))
                .isEqualTo(username);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT j.status FROM ai_suggestion_jobs j
                JOIN ticket_messages m ON m.id = j.input_message_id WHERE m.ticket_id = ?
                """, String.class, ticketId)).isEqualTo("PENDING");
        verify(receipts).receive("접수 문의", original, username);
    }

    @Test
    void client_author_claim_does_not_replace_authenticated_author() throws Exception {
        Map<String, Object> request = payload("작성자 신뢰 경계 실험");
        request.put("authorUsername", "someone-else");
        request.put("role", "AGENT");
        create(sessionFor(USER_NAME), request).andExpect(status().isCreated());
        assertRowCounts(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT author_username FROM ticket_messages", String.class)).isEqualTo(USER_NAME);
        verify(receipts).receive("접수 문의", "작성자 신뢰 경계 실험", USER_NAME);
    }

    @Test
    void valid_2000_code_points_are_not_measured_as_utf16_units() throws Exception {
        String original = "\u2003" + "😀".repeat(2000) + "\n";
        create(sessionFor(USER_NAME), payload(original)).andExpect(status().isCreated());
        assertRowCounts(1);
        assertThat(jdbcTemplate.queryForObject("SELECT body FROM ticket_messages", String.class))
                .isEqualTo(original);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n", "\u2003"})
    void blank_body_returns_400_without_calling_receipt_service(String body) throws Exception {
        create(sessionFor(USER_NAME), payload(body))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        assertNoReceipt();
    }

    @Test
    void title_only_request_is_not_a_postgres_receipt() throws Exception {
        create(sessionFor(USER_NAME), Map.of("title", "접수 문의"))
                .andExpect(status().isBadRequest());
        assertNoReceipt();
    }

    @Test
    void overlong_body_returns_400_without_truncation_or_service_call() throws Exception {
        create(sessionFor(USER_NAME), payload("😀".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "body must not exceed 2000 Unicode code points excluding edge whitespace"));
        assertNoReceipt();
    }

    @Test
    void missing_csrf_returns_403_before_controller_and_service() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tickets")
                        .session(sessionFor(USER_NAME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("정상 합성 문의"))))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(result.getHandler()).isNull();
        assertNoReceipt();
    }

    @Test
    void invalid_csrf_returns_403_before_controller_and_service() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tickets")
                        .session(sessionFor(USER_NAME)).with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("정상 합성 문의"))))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(result.getHandler()).isNull();
        assertNoReceipt();
    }

    @Test
    void anonymous_request_with_valid_csrf_returns_401() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tickets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("정상 합성 문의"))))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getHandler()).isNull();
        assertNoReceipt();
    }

    @Test
    void user_cannot_read_but_agent_can_read_the_committed_ticket() throws Exception {
        MvcResult created = create(sessionFor(USER_NAME), payload("정상 합성 문의"))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(get("/api/tickets/{id}", id).session(sessionFor(USER_NAME)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tickets/{id}", id).session(sessionFor(AGENT_NAME)))
                .andExpect(status().isOk())
                .andExpect(handler().handlerType(TicketController.class));
        assertRowCounts(1);
    }

    @Test
    void message_insert_failure_does_not_log_failed_row_body(CapturedOutput output)
            throws Exception {
        jdbcTemplate.execute("""
                ALTER TABLE ticket_messages ADD CONSTRAINT http_receipt_reject_message
                CHECK (false) NOT VALID
                """);
        try {
            String original = "SYNTHETIC_MESSAGE_ROW_NOT_FOR_LOG";
            create(sessionFor(USER_NAME), payload(original))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
            verify(receipts).receive("접수 문의", original, USER_NAME);
            assertRowCounts(0);
            assertThat(output.getAll()).doesNotContain(original, "Failing row contains");
        } finally {
            jdbcTemplate.execute("ALTER TABLE ticket_messages DROP CONSTRAINT http_receipt_reject_message");
        }
    }

    @Test
    void job_insert_failure_returns_safe_500_and_rolls_back_receipt(CapturedOutput output)
            throws Exception {
        jdbcTemplate.execute("""
                ALTER TABLE ai_suggestion_jobs ADD CONSTRAINT http_receipt_reject_job
                CHECK (false) NOT VALID
                """);
        try {
            String original = "SYNTHETIC_PRIVATE_BODY_NOT_FOR_LOG";
            create(sessionFor(USER_NAME), payload(original))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                    .andExpect(jsonPath("$.detail").value("an unexpected server error occurred"));
            verify(receipts).receive("접수 문의", original, USER_NAME);
            assertRowCounts(0);
            assertThat(output.getAll()).doesNotContain(original, "Failing row contains");
        } finally {
            jdbcTemplate.execute("ALTER TABLE ai_suggestion_jobs DROP CONSTRAINT http_receipt_reject_job");
        }
    }

    private org.springframework.test.web.servlet.ResultActions create(
            MockHttpSession session, Map<String, Object> request) throws Exception {
        return mockMvc.perform(post("/api/tickets").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private Map<String, Object> payload(String body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("title", "접수 문의");
        request.put("body", body);
        return request;
    }

    private MockHttpSession sessionFor(String username) throws Exception {
        MvcResult login = mockMvc.perform(formLogin().user(username).password(LOGIN_SECRET))
                .andExpect(authenticated().withUsername(username)).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private void assertNoReceipt() {
        verify(receipts, never()).receive(anyString(), anyString(), anyString());
        assertRowCounts(0);
    }

    private void assertRowCounts(int expected) {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tickets", Integer.class)).isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticket_messages", Integer.class)).isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_suggestion_jobs", Integer.class)).isEqualTo(expected);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestUsers {
        @Bean
        UserDetailsService receiptTestUsers(PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(
                    User.withUsername(USER_NAME).password(encoder.encode(LOGIN_SECRET)).roles("USER").build(),
                    User.withUsername(AGENT_NAME).password(encoder.encode(LOGIN_SECRET)).roles("USER", "AGENT").build());
        }
    }
}
