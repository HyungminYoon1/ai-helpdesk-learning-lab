package lab.helpdesk.security;

import java.util.Map;
import java.util.UUID;

import lab.helpdesk.ai.processing.AiSuggestionJobWorker;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ticket.repository.JdbcTicketRepository;
import lab.helpdesk.ticket.repository.TicketRepository;
import lab.helpdesk.ticket.web.TicketController;
import lab.helpdesk.ticket.web.TicketReceiptController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "helpdesk.ai.provider.enabled=false", "helpdesk.ai.worker.enabled=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles({"postgres", "deployment"})
@Testcontainers
class DeploymentSecurityHttpIntegrationTest {

    private static final String USER_NAME = "deployment-fixture-user";
    private static final String AGENT_NAME = "deployment-fixture-agent";
    private static final String USER_SECRET = UUID.randomUUID().toString();
    private static final String AGENT_SECRET = UUID.randomUUID().toString();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine");

    @DynamicPropertySource
    static void syntheticDeploymentCredentials(DynamicPropertyRegistry registry) {
        registry.add("helpdesk.deployment.user.username", () -> USER_NAME);
        registry.add("helpdesk.deployment.user.password", () -> USER_SECRET);
        registry.add("helpdesk.deployment.agent.username", () -> AGENT_NAME);
        registry.add("helpdesk.deployment.agent.password", () -> AGENT_SECRET);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationContext context;
    @Autowired private TicketRepository tickets;

    @Test
    void main_deployment_users_support_session_role_csrf_and_postgres_receipt_without_ai() throws Exception {
        Boolean isolated = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getURL().split("\\?", 2)[0]
                        .equals(POSTGRES.getJdbcUrl().split("\\?", 2)[0]));
        assertThat(isolated).isTrue();
        assertThat(context.getBean(UserDetailsService.class)).isInstanceOf(InMemoryUserDetailsManager.class);
        assertThat(context.containsBean("deploymentUserDetailsService")).isTrue();
        assertThat(context.containsBean("localBrowserUserDetailsService")).isFalse();
        assertThat(tickets).isInstanceOf(JdbcTicketRepository.class);
        assertThat(context.getBeansOfType(AiSuggestionProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(AiSuggestionJobWorker.class)).isEmpty();

        MockHttpSession userSession = login(USER_NAME, USER_SECRET);
        String payload = mapper.writeValueAsString(Map.of("title", "배포 설정 접수", "body", "합성 문의 원문"));
        MvcResult denied = mockMvc.perform(post("/api/tickets").session(userSession)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(denied.getHandler()).isNull();

        MvcResult created = mockMvc.perform(post("/api/tickets").session(userSession).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(handler().handlerType(TicketReceiptController.class))
                .andExpect(jsonPath("$.status").value("OPEN")).andReturn();
        long id = mapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        assertThat(tickets.findById(id)).isPresent();

        MvcResult anonymous = mockMvc.perform(get("/api/tickets/{id}", id))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(anonymous.getHandler()).isNull();
        MvcResult userRead = mockMvc.perform(get("/api/tickets/{id}", id).session(userSession))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(userRead.getHandler()).isNull();

        MockHttpSession agentSession = login(AGENT_NAME, AGENT_SECRET);
        mockMvc.perform(get("/api/tickets/{id}", id).session(agentSession))
                .andExpect(status().isOk()).andExpect(handler().handlerType(TicketController.class))
                .andExpect(jsonPath("$.title").value("배포 설정 접수"));
        mockMvc.perform(get("/api/tickets/{id}/ai-suggestion", id).session(agentSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.job.status").value("PENDING"));
        mockMvc.perform(formLogin().user(USER_NAME).password(UUID.randomUUID().toString()))
                .andExpect(unauthenticated());
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(formLogin().user(username).password(password))
                .andExpect(authenticated().withUsername(username)).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }
}
