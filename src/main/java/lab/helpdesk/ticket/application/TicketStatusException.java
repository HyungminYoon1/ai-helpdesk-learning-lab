package lab.helpdesk.ticket.application;

public final class TicketStatusException extends RuntimeException {
    public enum Code {
        INVALID_STATUS_REQUEST(400), INVALID_STATUS_TARGET(400), STATUS_FORBIDDEN(403), STATUS_NOT_FOUND(404),
        STATUS_REQUEST_CONFLICT(409), STATUS_VERSION_CONFLICT(409), INVALID_STATUS_TRANSITION(409),
        NEW_CUSTOMER_MESSAGE(409), CUSTOMER_CONFIRMATION_REQUIRED(409), REQUESTER_UNCONFIRMED(409), REQUEST_TOO_LARGE(413);
        private final int status;
        Code(int status) { this.status=status; }
        public int status() { return status; }
    }
    private final Code code;
    public TicketStatusException(Code code) { super(code.name());this.code=code; }
    public Code code() { return code; }
}
