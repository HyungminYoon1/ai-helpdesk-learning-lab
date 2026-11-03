package lab.helpdesk.ticket.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import lab.helpdesk.ticket.application.ConversationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static lab.helpdesk.ticket.application.ConversationException.Code.*;

/** Bounded, endpoint-local conversion before creating a complete JSON tree. */
public record CreateCustomerFollowupRequest(String body, UUID clientRequestId) {
    static final int MAX_BODY_BYTES = 32 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    static CreateCustomerFollowupRequest read(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw new ConversationException(REQUEST_TOO_LARGE);
            return parse(MAPPER.readTree(bytes));
        } catch (IOException | JacksonException invalid) {
            throw new ConversationException(INVALID_REQUEST);
        }
    }

    private static CreateCustomerFollowupRequest parse(JsonNode json) {
        if (json == null || !json.isObject() || json.size() != 2
                || !json.path("body").isString() || !json.path("clientRequestId").isString()) {
            throw new ConversationException(INVALID_REQUEST);
        }
        for (String field : json.propertyNames()) {
            if (!java.util.Set.of("body", "clientRequestId").contains(field))
                throw new ConversationException(INVALID_REQUEST);
        }
        UUID request;
        try {
            String raw = json.path("clientRequestId").asString();
            request = UUID.fromString(raw);
            if (!request.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new ConversationException(INVALID_REQUEST);
        }
        return new CreateCustomerFollowupRequest(json.path("body").asString(), request);
    }
}
