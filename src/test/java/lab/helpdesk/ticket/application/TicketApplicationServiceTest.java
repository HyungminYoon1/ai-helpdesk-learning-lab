package lab.helpdesk.ticket.application;

import org.junit.jupiter.api.Test;
import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketStatus;
import lab.helpdesk.ticket.repository.InMemoryTicketRepository;
import lab.helpdesk.ticket.repository.TicketRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TicketApplicationServiceTest {

    // 티켓 저장 가능
    @Test
    void create_saves_ticket_and_returns_result() {
        // Given
        TicketRepository repository = new InMemoryTicketRepository();
        TicketApplicationService service = new TicketApplicationService(repository);

        // When
        TicketResult result = service.create("로그인 오류");

        // Then
        assertEquals(1L, result.id()); // 반환 ID가 1
        assertEquals("로그인 오류", result.title()); // 반환 제목이 "로그인 오류"
        assertEquals(TicketStatus.OPEN, result.status()); // 반환 상태가 OPEN

        Ticket savedTicket = repository.findById(result.id()).orElseThrow(); // 반환 ID로 Repository를 조회하면 Ticket이 실제로 존재

        assertEquals("로그인 오류", savedTicket.title());
        assertEquals(TicketStatus.OPEN, savedTicket.status());
    }

    // 저장된 티켓은 조회 가능
    @Test
    void saved_ticket_can_be_found_by_id() {
        // Given
        TicketRepository repository = new InMemoryTicketRepository();
        TicketApplicationService service = new TicketApplicationService(repository);
        TicketResult created = service.create("로그인 오류");

        // When
        TicketResult found = service.findById(created.id());

        // Then
        assertEquals(created.id(), found.id());
        assertEquals("로그인 오류", found.title());
        assertEquals(TicketStatus.OPEN, found.status());
    }

    // Ticket이 없으면 TicketNotFoundException 발생
    @Test
    void unknown_ticket_id_throws_exception() {
        // Given
        TicketRepository repository = new InMemoryTicketRepository();

        TicketApplicationService service = new TicketApplicationService(repository);

        // When
        TicketNotFoundException exception = assertThrows(
                TicketNotFoundException.class,
                () -> service.findById(999L));

        // Then
        assertEquals(
                "ticket not found: 999",
                exception.getMessage());
    }
}
