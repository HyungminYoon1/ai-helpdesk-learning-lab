package lab.helpdesk.ai.query;

// SQL·Provider 원문과 Cause를 HTTP 응답이나 Log에 전달하지 않는다.
public final class AiSuggestionQueryException extends RuntimeException {

    public enum Code {
        INVALID_TICKET_ID,
        AI_RESULT_INCONSISTENT,
        AI_RESULT_QUERY_FAILED
    }

    private final Code code;

    public AiSuggestionQueryException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
