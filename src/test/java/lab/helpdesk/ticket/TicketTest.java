package lab.helpdesk.ticket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TicketTest {

    @Test
    void new_ticket_starts_open() {
        // Given
        var title = "로그인 오류";

        // When
        var ticket = new Ticket(title);

        // Then
        assertEquals(TicketStatus.OPEN, ticket.status());
    }

    @Test
    void null_title_is_rejected() {
        // Given
        String title = null;

        // When
        var exception = assertThrows(IllegalArgumentException.class, () -> new Ticket(title));

        // Then
        assertEquals("title must not be blank", exception.getMessage());
    }

    @Test
    void empty_title_is_rejected() {
        // Given
        String title = "";

        // When & Then
        assertThrows(
                IllegalArgumentException.class,
                () -> new Ticket(title));
    }

    @Test
    void blank_title_is_rejected() {
        // Given
        String title = "  ";

        // When & Then
        assertThrows(
                IllegalArgumentException.class,
                () -> new Ticket(title));
    }

    @Test
    void open_ticket_can_start_progress() {
        // Given
        var title = "로그인 오류";
        var ticket = new Ticket(title);

        // When
        ticket.startProgress();

        // Then
        assertEquals(TicketStatus.IN_PROGRESS, ticket.status());
    }

    @Test
    void in_progress_ticket_can_be_resolved() {
        // Given
        var title = "로그인 오류";
        var ticket = new Ticket(title);
        ticket.startProgress();

        // When
        ticket.resolve();

        // Then
        assertEquals(TicketStatus.RESOLVED, ticket.status());
    }

    @Test
    void open_ticket_cannot_be_resolved() {
        // Given
        var ticket = new Ticket("로그인 오류");

        // When
        var exception = assertThrows(IllegalStateException.class, ticket::resolve);

        // Then
        assertEquals(
                "only IN_PROGRESS ticket can be resolved",
                exception.getMessage());
        assertEquals(TicketStatus.OPEN, ticket.status());
    }

    @Test
    void in_progress_ticket_cannot_start_progress_again() {
        // Given
        var ticket = new Ticket("로그인 오류");
        ticket.startProgress();

        // When
        var exception = assertThrows(IllegalStateException.class, ticket::startProgress);

        // Then
        assertEquals("only OPEN ticket can start progress", exception.getMessage());
        assertEquals(TicketStatus.IN_PROGRESS, ticket.status());
    }

    @Test
    void resolved_ticket_cannot_start_progress() {
        // Given
        var ticket = new Ticket("로그인 오류");
        ticket.startProgress();
        ticket.resolve();

        // When
        assertThrows(
                IllegalStateException.class,
                ticket::startProgress);

        // Then
        assertEquals(TicketStatus.RESOLVED, ticket.status());
    }

    @Test
    void resolved_ticket_cannot_be_resolved_again() {
        // Given
        var ticket = new Ticket("로그인 오류");
        ticket.startProgress();
        ticket.resolve();

        // When
        assertThrows(
                IllegalStateException.class,
                ticket::resolve);

        // Then
        assertEquals(TicketStatus.RESOLVED, ticket.status());
    }
}
