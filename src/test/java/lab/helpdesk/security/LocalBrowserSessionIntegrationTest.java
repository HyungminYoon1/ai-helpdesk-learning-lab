package lab.helpdesk.security;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"in-memory", "local-browser"})
class LocalBrowserSessionIntegrationTest {

    private static final String LOCAL_UI_ORIGIN =
            "http://127.0.0.1:4173";
    private static final String USER_USERNAME =
            UUID.randomUUID().toString();
    private static final String USER_PASSWORD =
            UUID.randomUUID().toString();
    private static final String AGENT_USERNAME =
            UUID.randomUUID().toString();
    private static final String AGENT_PASSWORD =
            UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void localBrowserProperties(
            DynamicPropertyRegistry registry) {

        registry.add(
                "helpdesk.local.user.username",
                () -> USER_USERNAME);
        registry.add(
                "helpdesk.local.user.password",
                () -> USER_PASSWORD);
        registry.add(
                "helpdesk.local.agent.username",
                () -> AGENT_USERNAME);
        registry.add(
                "helpdesk.local.agent.password",
                () -> AGENT_PASSWORD);
        registry.add(
                "helpdesk.local.cors.allowed-origin",
                () -> LOCAL_UI_ORIGIN);
    }

    @Test
    void local_user_can_log_in_with_runtime_configuration()
            throws Exception {

        mockMvc.perform(formLogin()
                        .user(USER_USERNAME)
                        .password(USER_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated()
                        .withUsername(USER_USERNAME)
                        .withRoles("USER"));
    }

    @Test
    void local_agent_can_log_in_with_runtime_configuration()
            throws Exception {

        mockMvc.perform(formLogin()
                        .user(AGENT_USERNAME)
                        .password(AGENT_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated()
                        .withUsername(AGENT_USERNAME)
                        .withRoles("USER", "AGENT"));
    }

    @Test
    void allowed_preflight_is_handled_before_authentication()
            throws Exception {

        mockMvc.perform(options("/api/tickets")
                        .header(
                                HttpHeaders.ORIGIN,
                                LOCAL_UI_ORIGIN)
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                HttpMethod.POST.name())
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Content-Type, X-CSRF-TOKEN"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        LOCAL_UI_ORIGIN))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                        "true"))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        containsString(HttpMethod.POST.name())))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsString("X-CSRF-TOKEN")));
    }

    @Test
    void disallowed_preflight_is_rejected_without_cors_permission()
            throws Exception {

        mockMvc.perform(options("/api/tickets")
                        .header(
                                HttpHeaders.ORIGIN,
                                "http://untrusted.example")
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                HttpMethod.POST.name())
                        .header(
                                HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Content-Type, X-CSRF-TOKEN"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(result -> assertThat(result.getHandler())
                        .isNull());
    }

    @Test
    void allowed_actual_get_exposes_authentication_failure()
            throws Exception {

        mockMvc.perform(get("/api/tickets/1")
                        .header(
                                HttpHeaders.ORIGIN,
                                LOCAL_UI_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        LOCAL_UI_ORIGIN))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                        "true"))
                .andExpect(result -> assertThat(result.getHandler())
                        .isNull());
    }
}
