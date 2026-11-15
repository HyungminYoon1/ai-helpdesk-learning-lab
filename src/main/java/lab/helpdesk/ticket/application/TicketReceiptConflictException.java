package lab.helpdesk.ticket.application;

public class TicketReceiptConflictException extends RuntimeException {

    public TicketReceiptConflictException() {
        super("the receipt request ID has already been used with different content");
    }
}
