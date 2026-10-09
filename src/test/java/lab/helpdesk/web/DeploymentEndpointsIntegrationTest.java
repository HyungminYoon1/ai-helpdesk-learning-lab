package lab.helpdesk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("in-memory")
class DeploymentEndpointsIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private Environment environment;

    @Test
    void anonymousHealthReturnsOnlyStatusWithoutCreatingASession() throws Exception {
        var result = mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andReturn();

        assertThat(JSON.readTree(result.getResponse().getContentAsString()).size()).isOne();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void aQueryParameterCannotTurnOnHealthDetails() throws Exception {
        var result = mvc.perform(get("/actuator/health").param("show-details", "always")
                        .with(user("deployment-agent").roles("AGENT")))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(JSON.readTree(result.getResponse().getContentAsString()).size()).isOne();
    }

    @Test
    void headHealthIsAllowed() throws Exception {
        // Body suppression is checked with a real HTTP server, not the MockMvc transport.
        mvc.perform(head("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void anonymousRequestMetricQueryReturns401WithoutLoginRedirect() throws Exception {
        var result = mvc.perform(get("/actuator/metrics/http.server.requests"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andReturn();

        assertThat(result.getHandler()).isNull();
    }

    @Test
    void userCannotReadRequestMetrics() throws Exception {
        var result = mvc.perform(get("/actuator/metrics/http.server.requests")
                        .with(user("deployment-user").roles("USER")))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(result.getHandler()).isNull();
    }

    @Test
    void agentCanReadTheRequestMetric() throws Exception {
        // A fixture tests endpoint access and response; real HTTP recording has its own test.
        Timer.builder("http.server.requests")
                .tag("method", "GET").tag("uri", "/api/tickets/{id}").tag("status", "200")
                .register(meters).record(Duration.ofMillis(2));

        mvc.perform(get("/actuator/metrics/http.server.requests")
                        .with(user("deployment-agent").roles("AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("http.server.requests"))
                .andExpect(jsonPath("$.measurements").isArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/configprops", "/actuator/beans",
            "/actuator/mappings", "/actuator/heapdump", "/actuator/threaddump", "/actuator/shutdown",
            "/actuator/loggers", "/actuator/metrics", "/actuator/metrics/jvm.memory.used",
            "/actuator/health/db"})
    void evenAgentCannotReadUnapprovedManagementEndpoints(String path) throws Exception {
        var result = mvc.perform(get(path).with(user("deployment-agent").roles("AGENT")))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(result.getHandler()).isNull();
    }

    @Test
    void defaultsKeepDetailsPrivateAndConfigureHttpShutdownGrace() {
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(environment.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.endpoints.web.discovery.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.endpoints.jmx.exposure.exclude")).isEqualTo("*");
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("20s");
    }
}
