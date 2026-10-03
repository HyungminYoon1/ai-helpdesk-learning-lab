package lab.helpdesk.ticket;

public record TicketMessage(String body, String authorUsername) {

    public TicketMessage {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("message body must not be blank");
        }
        if (authorUsername == null || authorUsername.isBlank()) {
            throw new IllegalArgumentException("message author must not be blank");
        }
        // 검증을 위해 원문을 trim하거나 다른 문자열로 바꾸지 않는다.
    }
}
