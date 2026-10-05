package lab.helpdesk.ticket.web;

import jakarta.validation.constraints.NotBlank;
import lab.helpdesk.ticket.web.validation.TicketMessageBodyLength;

public record CreateTicketReceiptRequest(
        @NotBlank(message = "title must not be blank") String title,
        @NotBlank(message = "body must not be blank")
        @TicketMessageBodyLength String body) {
}
