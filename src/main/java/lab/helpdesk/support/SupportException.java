package lab.helpdesk.support;

public class SupportException extends RuntimeException {
    public enum Code {
        INVALID_ASSIGNMENT_REQUEST(400), INVALID_ASSIGNEE(400), SUPPORT_FORBIDDEN(403),
        ASSIGNMENT_FORBIDDEN(403), NOT_ASSIGNEE(403), SUPPORT_NOT_FOUND(404),
        ASSIGNMENT_VERSION_CONFLICT(409), ASSIGNMENT_NOT_UNASSIGNED(409),
        ASSIGNMENT_REQUIRED(409), ASSIGNMENT_REQUEST_CONFLICT(409),
        SUPPORT_NOT_CONFIGURED(503);
        private final int status;
        Code(int status) { this.status = status; }
        public int status() { return status; }
    }
    private final Code code;
    public SupportException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
