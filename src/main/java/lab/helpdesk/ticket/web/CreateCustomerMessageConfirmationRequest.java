package lab.helpdesk.ticket.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import lab.helpdesk.ticket.application.ConversationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static lab.helpdesk.ticket.application.ConversationException.Code.*;

public record CreateCustomerMessageConfirmationRequest(UUID clientRequestId, long expectedAssignmentVersion, long throughMessageId) {
    static final int MAX_BODY_BYTES = 32 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    static CreateCustomerMessageConfirmationRequest read(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) throw new ConversationException(REQUEST_TOO_LARGE);
            var json = JSON.readTree(bytes);
            if (json == null || !json.isObject() || json.size() != 3
                    || !Set.of("clientRequestId","expectedAssignmentVersion","throughMessageId").containsAll(json.propertyNames())
                    || !json.path("clientRequestId").isString()) throw new ConversationException(INVALID_REQUEST);
            String raw = json.get("clientRequestId").asString();
            UUID request = UUID.fromString(raw);
            if (!request.toString().equalsIgnoreCase(raw)) throw new ConversationException(INVALID_REQUEST);
            var version = json.get("expectedAssignmentVersion");
            var through = json.get("throughMessageId");
            if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() <= 0
                    || !through.isIntegralNumber() || !through.canConvertToLong() || through.asLong() <= 0)
                throw new ConversationException(INVALID_REQUEST);
            return new CreateCustomerMessageConfirmationRequest(request,version.asLong(),through.asLong());
        } catch (IOException | JacksonException | IllegalArgumentException invalid) {
            throw new ConversationException(INVALID_REQUEST);
        }
    }
}
