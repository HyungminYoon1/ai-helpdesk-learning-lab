package lab.helpdesk.ticket.web;

import lab.helpdesk.ticket.TicketStatus;
import lab.helpdesk.ticket.application.TicketResult;

public record TicketResponse(
        long id,
        String title,
        TicketStatus status) {

    public static TicketResponse from(TicketResult result) {
        return new TicketResponse(
                result.id(),
                result.title(),
                result.status());
    }
}
