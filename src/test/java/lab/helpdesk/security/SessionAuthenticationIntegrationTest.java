package lab.helpdesk.security;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.ActiveProfiles;

import lab.helpdesk.ticket.application.TicketApplicationService;
import lab.helpdesk.ticket.application.TicketResult;
import lab.helpdesk.ticket.web.TicketController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SessionAuthenticationIntegrationTest.LoginTestConfiguration.class)
@ActiveProfiles("in-memory")
class SessionAuthenticationIntegrationTest {

    private static final String TEST_AGENT_USERNAME =
            "session-test-agent";

    private static final String TEST_AGENT_PASSWORD =
            UUID.randomUUID().toString();

    private static final String TEST_USER_USERNAME =
            "session-test-user";

    private static final String TEST_USER_PASSWORD =
            UUID.randomUUID().toString();

    private static final String CREATE_TICKET_JSON = """
            {"title":"CSRF comparison ticket"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TicketApplicationService ticketApplicationService;

    @Test
    void registered_agent_can_log_in() throws Exception {
        mockMvc.perform(
                formLogin()
                        .user(TEST_AGENT_USERNAME)
                        .password(TEST_AGENT_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated()
                        .withUsername(TEST_AGENT_USERNAME)
                        .withRoles("AGENT"));
    }

    @Test
    void wrong_password_does_not_authenticate_agent()
            throws Exception {

        mockMvc.perform(
                formLogin()
                        .user(TEST_AGENT_USERNAME)
                        .password(TEST_AGENT_PASSWORD + "-different"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void login_session_authenticates_follow_up_request()
            throws Exception {

        MvcResult loginResult = mockMvc.perform(
                formLogin()
                        .user(TEST_AGENT_USERNAME)
                        .password(TEST_AGENT_PASSWORD))
                .andExpect(authenticated()
                        .withUsername(TEST_AGENT_USERNAME)
                        .withRoles("AGENT"))
                .andReturn();

        MockHttpSession authenticatedSession =
                (MockHttpSession) loginResult
                        .getRequest()
                        .getSession(false);

        assertThat(authenticatedSession).isNotNull();

        mockMvc.perform(
                get("/api/tickets/{id}", 999L)
                        .session(authenticatedSession)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(authenticated()
                        .withUsername(TEST_AGENT_USERNAME)
                        .withRoles("AGENT"))
                .andExpect(status().isNotFound())
                .andExpect(handler()
                        .handlerType(TicketController.class))
                .andExpect(handler()
                        .methodName("findById"));
    }

    @Test
    void logged_in_user_cannot_read_ticket() throws Exception {
        MockHttpSession authenticatedSession = authenticatedSession(
                TEST_USER_USERNAME,
                TEST_USER_PASSWORD,
                "USER");

        MvcResult result = mockMvc.perform(
                get("/api/tickets/{id}", 999L)
                        .session(authenticatedSession)
                        .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(authenticated()
                        .withUsername(TEST_USER_USERNAME)
                        .withRoles("USER"))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(result.getHandler()).isNull();
    }

    @Test
    void logged_in_user_cannot_create_ticket_without_csrf()
            throws Exception {

        MockHttpSession authenticatedSession = authenticatedSession(
                TEST_USER_USERNAME,
                TEST_USER_PASSWORD,
                "USER");

        MvcResult result = mockMvc.perform(
                post("/api/tickets")
                        .session(authenticatedSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_TICKET_JSON))
                .andExpect(authenticated()
                        .withUsername(TEST_USER_USERNAME)
                        .withRoles("USER"))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(result.getHandler()).isNull();
    }

    @Test
    void logged_in_user_can_create_ticket_with_valid_csrf()
            throws Exception {

        MockHttpSession authenticatedSession = authenticatedSession(
                TEST_USER_USERNAME,
                TEST_USER_PASSWORD,
                "USER");

        mockMvc.perform(
                post("/api/tickets")
                        .session(authenticatedSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_TICKET_JSON))
                .andExpect(authenticated()
                        .withUsername(TEST_USER_USERNAME)
                        .withRoles("USER"))
                .andExpect(status().isCreated())
                .andExpect(handler()
                        .handlerType(TicketController.class))
                .andExpect(handler()
                        .methodName("create"));
    }

    @Test
    void logged_in_user_can_request_csrf_token_for_follow_up_request()
            throws Exception {

        MockHttpSession authenticatedSession = authenticatedSession(
                TEST_USER_USERNAME,
                TEST_USER_PASSWORD,
                "USER");

        mockMvc.perform(
                get("/api/csrf")
                        .session(authenticatedSession))
                .andExpect(authenticated()
                        .withUsername(TEST_USER_USERNAME)
                        .withRoles("USER"))
                .andExpect(status().isOk())
                .andExpect(handler()
                        .handlerType(CsrfController.class))
                .andExpect(handler()
                        .methodName("csrf"))
                .andExpect(jsonPath("$.headerName")
                        .value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void csrf_token_from_endpoint_authorizes_follow_up_ticket_creation()
            throws Exception {

        MockHttpSession authenticatedSession = authenticatedSession(
                TEST_USER_USERNAME,
                TEST_USER_PASSWORD,
                "USER");

        MvcResult csrfResult = mockMvc.perform(
                get("/api/csrf")
                        .session(authenticatedSession))
                .andExpect(status().isOk())
                .andReturn();

        CsrfToken csrfToken = (CsrfToken) csrfResult
                .getRequest()
                .getAttribute(CsrfToken.class.getName());

        assertThat(csrfToken).isNotNull();

        mockMvc.perform(
                post("/api/tickets")
                        .session(authenticatedSession)
                        .header(
                                csrfToken.getHeaderName(),
                                csrfToken.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_TICKET_JSON))
                .andExpect(authenticated()
                        .withUsername(TEST_USER_USERNAME)
                        .withRoles("USER"))
                .andExpect(status().isCreated())
                .andExpect(handler()
                        .handlerType(TicketController.class))
                .andExpect(handler()
                        .methodName("create"));
    }

    @Test
    void logged_in_agent_can_read_existing_ticket()
            throws Exception {

        TicketResult existingTicket = ticketApplicationService.create(
                "Role matrix agent ticket");
        MockHttpSession authenticatedSession = authenticatedSession(
                TEST_AGENT_USERNAME,
                TEST_AGENT_PASSWORD,
                "AGENT");

        mockMvc.perform(
                get("/api/tickets/{id}", existingTicket.id())
                        .session(authenticatedSession))
                .andExpect(authenticated()
                        .withUsername(TEST_AGENT_USERNAME)
                        .withRoles("AGENT"))
                .andExpect(status().isOk())
                .andExpect(handler()
                        .handlerType(TicketController.class))
                .andExpect(handler()
                        .methodName("findById"));
    }

    private MockHttpSession authenticatedSession(
            String username,
            String password,
            String role) throws Exception {

        MvcResult loginResult = mockMvc.perform(
                formLogin()
                        .user(username)
                        .password(password))
                .andExpect(authenticated()
                        .withUsername(username)
                        .withRoles(role))
                .andReturn();

        MockHttpSession authenticatedSession =
                (MockHttpSession) loginResult
                        .getRequest()
                        .getSession(false);

        assertThat(authenticatedSession).isNotNull();
        return authenticatedSession;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LoginTestConfiguration {

        @Bean
        UserDetailsService testUserDetailsService(
                PasswordEncoder passwordEncoder) {

            UserDetails user = User.builder()
                    .username(TEST_USER_USERNAME)
                    .password(passwordEncoder.encode(
                            TEST_USER_PASSWORD))
                    .roles("USER")
                    .build();

            UserDetails agent = User.builder()
                    .username(TEST_AGENT_USERNAME)
                    .password(passwordEncoder.encode(
                            TEST_AGENT_PASSWORD))
                    .roles("AGENT")
                    .build();

            return new InMemoryUserDetailsManager(user, agent);
        }
    }
}
