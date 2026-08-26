package lab.helpdesk.ticket.application;

import lab.helpdesk.ticket.TicketStatus;

public record TicketResult(
        long id,
        String title,
        TicketStatus status) {
}
