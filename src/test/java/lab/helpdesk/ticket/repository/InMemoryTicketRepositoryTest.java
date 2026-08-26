package lab.helpdesk.ticket.repository;

import lab.helpdesk.ticket.Ticket;
import lab.helpdesk.ticket.TicketStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InMemoryTicketRepositoryTest {

    @Test
    void saved_ticket_can_be_found_by_id() {
        // Given: 새로운 Repository와 유효한 Ticket
        TicketRepository repository = new InMemoryTicketRepository();
        Ticket ticket = new Ticket("로그인 오류");

        // When: Ticket을 저장하고 반환된 ID로 조회
        long savedId = repository.save(ticket);
        Optional<Ticket> result = repository.findById(savedId);

        // Then: ID는 1이고 저장했던 Ticket 정보가 조회됨
        assertTrue(result.isPresent());
        assertEquals(1L, savedId);

        Ticket foundTicket = result.orElseThrow();

        assertEquals("로그인 오류", foundTicket.title());
        assertEquals(TicketStatus.OPEN, foundTicket.status());
    }

    // 존재하지 않는 id로 티켓 조회
    @Test
    void unknown_ticket_id_returns_empty() {
        // Given: 비어 있는 새로운 Repository
        TicketRepository repository = new InMemoryTicketRepository();

        // When: 존재하지 않는 ID 999로 조회
        Optional<Ticket> result = repository.findById(999);

        // Then: Optional이 비어 있음
        assertTrue(result.isEmpty());
    }

    // 저장할 때마다 다음 ID 발급
    @Test
    void each_saved_ticket_receives_next_id() {
        // Given
        var firstTicket = new Ticket("로그인 오류");
        var secondTicket = new Ticket("결제 오류");

        TicketRepository repository = new InMemoryTicketRepository();

        // When
        long firstId = repository.save(firstTicket);
        long secondId = repository.save(secondTicket);

        // Then
        assertEquals(1L, firstId);
        assertEquals(2L, secondId);

        Ticket foundFirstTicket = repository.findById(firstId).orElseThrow();
        Ticket foundSecondTicket = repository.findById(secondId).orElseThrow();

        assertEquals("로그인 오류", foundFirstTicket.title());
        assertEquals("결제 오류", foundSecondTicket.title());
    }
}
