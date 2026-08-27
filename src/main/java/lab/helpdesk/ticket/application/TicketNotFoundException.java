package lab.helpdesk.ticket.application;

public class TicketNotFoundException extends RuntimeException {

    public TicketNotFoundException(long id) {
        super("ticket not found: " + id);
    }
}
