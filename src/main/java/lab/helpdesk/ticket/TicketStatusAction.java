package lab.helpdesk.ticket;

public enum TicketStatusAction {
    START, RESOLVE, REOPEN;

    public void apply(Ticket ticket) {
        switch (this) {
            case START -> ticket.startProgress();
            case RESOLVE -> ticket.resolve();
            case REOPEN -> ticket.reopen();
        }
    }
}
