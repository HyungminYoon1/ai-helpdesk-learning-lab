package lab.helpdesk.ticket.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lab.helpdesk.ticket.web.validation.TicketMessageBodyLength;

public record CreateTicketReceiptRequest(
        @NotBlank(message = "title must not be blank") String title,
        @NotBlank(message = "body must not be blank")
        @TicketMessageBodyLength String body,
        @NotBlank(message = "clientRequestId is required")
        @Pattern(
                regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                message = "clientRequestId must be a UUID")
        String clientRequestId) {
}
