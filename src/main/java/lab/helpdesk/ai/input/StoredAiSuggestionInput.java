package lab.helpdesk.ai.input;

import lab.helpdesk.ticket.TicketMessage;

/** Database input, not a Provider prompt. The transmission copy is prepared later. */
public record StoredAiSuggestionInput(long ticketId, long messageId, String title, String body) {

    public StoredAiSuggestionInput {
        if (ticketId <= 0 || messageId <= 0 || title == null || title.isBlank()
                || body == null || body.isBlank()
                || TicketMessage.bodyCodePointCount(body) > TicketMessage.MAX_BODY_CODE_POINTS) {
            throw new IllegalArgumentException("AI_STORED_INPUT_INVALID");
        }
    }

    @Override
    public String toString() {
        return "StoredAiSuggestionInput[ticketId=" + ticketId + ", messageId=" + messageId
                + ", content omitted]";
    }
}
