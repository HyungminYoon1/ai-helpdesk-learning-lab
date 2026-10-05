package lab.helpdesk.ticket;

public record TicketMessage(String body, String authorUsername) {

    public static final int MAX_BODY_CODE_POINTS = 2000;

    public TicketMessage {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("message body must not be blank");
        }
        if (bodyCodePointCount(body) > MAX_BODY_CODE_POINTS) {
            throw new IllegalArgumentException("message body exceeds allowed code point count");
        }
        if (authorUsername == null || authorUsername.isBlank()) {
            throw new IllegalArgumentException("message author must not be blank");
        }
        // 길이 계산용 복사본과 별개로, 저장하는 body는 원문 그대로 유지한다.
    }

    public static int bodyCodePointCount(String body) {
        String measuredBody = body.strip();
        return measuredBody.codePointCount(0, measuredBody.length());
    }
}
