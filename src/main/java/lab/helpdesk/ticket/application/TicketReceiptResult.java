package lab.helpdesk.ticket.application;

public record TicketReceiptResult(
        TicketResult ticket,
        long messageId,
        long jobId) {
}
