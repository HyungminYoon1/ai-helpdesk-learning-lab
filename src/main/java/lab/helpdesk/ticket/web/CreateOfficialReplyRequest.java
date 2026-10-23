package lab.helpdesk.ticket.web;

import java.util.UUID;
import tools.jackson.databind.JsonNode;
import lab.helpdesk.ticket.application.ConversationException;
import static lab.helpdesk.ticket.application.ConversationException.Code.INVALID_REQUEST;

public record CreateOfficialReplyRequest(String body, UUID clientRequestId, Long correctsMessageId) {
    /** Strict input conversion for this endpoint only; no global Jackson coercion change. */
    static CreateOfficialReplyRequest parse(JsonNode json) {
        if (json == null || !json.isObject() || !json.path("body").isString()
                || !json.path("clientRequestId").isString()) throw new ConversationException(INVALID_REQUEST);
        for (String field : json.propertyNames()) {
            if (!java.util.Set.of("body", "clientRequestId", "correctsMessageId").contains(field)) {
                throw new ConversationException(INVALID_REQUEST);
            }
        }
        UUID request;
        try {
            String raw = json.path("clientRequestId").asString();
            request = UUID.fromString(raw);
            if (!request.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new ConversationException(INVALID_REQUEST); }
        JsonNode correction = json.path("correctsMessageId");
        Long target = null;
        if (!correction.isMissingNode() && !correction.isNull()) {
            if (!correction.isIntegralNumber() || !correction.canConvertToLong() || correction.asLong() <= 0) {
                throw new ConversationException(INVALID_REQUEST);
            }
            target = correction.asLong();
        }
        return new CreateOfficialReplyRequest(json.path("body").asString(), request, target);
    }
}
