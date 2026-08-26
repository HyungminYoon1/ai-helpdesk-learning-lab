package lab.helpdesk.ticket.web;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import lab.helpdesk.ticket.application.TicketApplicationService;
import lab.helpdesk.ticket.repository.InMemoryTicketRepository;
import lab.helpdesk.ticket.repository.TicketRepository;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketControllerTest {

    private MockMvc mockMvc;
    private TicketApplicationService service;

    @BeforeEach
    void setUp() {
        TicketRepository repository = new InMemoryTicketRepository();

        this.service = new TicketApplicationService(repository);

        TicketController controller = new TicketController(this.service);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .build();
    }

    @Test
    void valid_request_creates_ticket() throws Exception {
        // Given
        String requestBody = """
                {
                  "title": "로그인 오류"
                }
                """;

        // When & Then
        mockMvc.perform(post("/api/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        "/api/tickets/1"))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("로그인 오류"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void existing_ticket_can_be_found_by_id() throws Exception {
        // Given
        var created = service.create("로그인 오류");

        // When & Then
        mockMvc.perform(
                get("/api/tickets/{id}", created.id())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(created.id()))
                .andExpect(jsonPath("$.title").value("로그인 오류"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

}
