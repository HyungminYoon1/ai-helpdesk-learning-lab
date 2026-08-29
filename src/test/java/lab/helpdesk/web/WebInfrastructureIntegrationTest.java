package lab.helpdesk.web;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class WebInfrastructureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void request_id_filter_is_registered()
            throws Exception {

        mockMvc.perform(
                get("/api/tickets/{id}", 999L)
                        .accept(
                                MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(header().exists(
                        RequestIdFilter.HEADER_NAME));
    }

    @Test
    void handler_timing_interceptor_is_registered(
            CapturedOutput output)
            throws Exception {

        mockMvc.perform(
                get("/api/tickets/{id}", 999L)
                        .accept(
                                MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound());

        assertThat(output.getAll())
                .contains(
                        "handler=TicketController#findById")
                .contains(
                        "status=404")
                .contains(
                        "elapsedNanos=");
    }
}
