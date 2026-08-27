package lab.helpdesk.ticket.web;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.application.TicketApplicationService;
import lab.helpdesk.ticket.repository.InMemoryTicketRepository;
import lab.helpdesk.ticket.repository.TicketRepository;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketControllerTest {

    private MockMvc mockMvc;
    private TicketApplicationService service;
    private TicketRepository repository;

    @BeforeEach
    void setUp() {
        this.repository = new InMemoryTicketRepository();

        this.service = new TicketApplicationService(repository);

        TicketController controller = new TicketController(this.service);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new TicketApiExceptionHandler())
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

    @Test
    void blank_title_is_rejected_before_ticket_is_saved() throws Exception {

        // Given
        String requestBody = """
                {
                  "title": "   "
                }
                """;

        // When & Then
        mockMvc.perform(post("/api/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_PROBLEM_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail")
                        .value("title must not be blank"))
                .andExpect(header().doesNotExist(
                        HttpHeaders.LOCATION));

        assertTrue(repository.findById(1L).isEmpty());
    }

    @Test
    void unknown_ticket_id_returns_not_found_problem_detail() throws Exception {

        // When & Then
        mockMvc.perform(get("/api/tickets/{id}", 999L)
                .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail")
                        .value("ticket not found"))
                .andExpect(header().doesNotExist(
                        HttpHeaders.LOCATION));
    }

    // 티켓 id가 정수가 아닌 경우에 올바른 세부 메시지를 출력하는지 확인
    @Test
    void non_numeric_ticket_id_returns_bad_request_problem_detail() throws Exception {

        // When & Then
        mockMvc.perform(get("/api/tickets/abc")
                .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title")
                        .value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail")
                        .value("ticket id must be a number"))
                .andExpect(header().doesNotExist(
                        HttpHeaders.LOCATION));
    }

    // `잘못된 JSON → DTO 생성 실패 → Validation 미실행 → Controller 메서드 본문 미실행 → Repository
    // 미변경 → 400 ProblemDetail` 검증
    @Test
    void malformed_json_returns_bad_request_problem_detail() throws Exception {

        // Given: 닫는 중괄호가 없는 JSON
        String requestBody = "{\"title\":\"로그인 오류\"";

        // When & Then
        mockMvc.perform(post("/api/tickets")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_PROBLEM_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title")
                        .value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail")
                        .value("request body is malformed"))
                .andExpect(header().doesNotExist(
                        HttpHeaders.LOCATION));

        assertTrue(repository.findById(1L).isEmpty());
    }

    // 대표 500 테스트
    @Test
    void unexpected_repository_failure_returns_safe_problem_detail() throws Exception {

        // Given
        TicketRepository failingRepository = new TicketRepository() {

            @Override
            public long save(Ticket ticket) {
                throw new UnsupportedOperationException(
                        "save is not used in this test");
            }

            @Override
            public Optional<Ticket> findById(long id) {
                throw new RuntimeException(
                        "simulated repository failure");
            }
        };

        TicketApplicationService failingService = new TicketApplicationService(failingRepository);

        TicketController controller = new TicketController(failingService);

        MockMvc failingMockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(
                        new TicketApiExceptionHandler())
                .build();

        // When & Then
        failingMockMvc.perform(get("/api/tickets/{id}", 1L)
                .accept(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title")
                        .value("Internal Server Error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "an unexpected server error occurred"))
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(header().doesNotExist(
                        HttpHeaders.LOCATION));
    }
}
