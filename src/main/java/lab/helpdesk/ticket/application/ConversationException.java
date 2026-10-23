package lab.helpdesk.ticket.application;

public class ConversationException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST(400), INVALID_CORRECTION_TARGET(400), FORBIDDEN(403), NOT_FOUND(404),
        REQUESTER_UNCONFIRMED(409), REPLY_REQUEST_CONFLICT(409);
        private final int status;
        Code(int status) { this.status = status; }
        public int status() { return status; }
    }
    private final Code code;
    public ConversationException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
