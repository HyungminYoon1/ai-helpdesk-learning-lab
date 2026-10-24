package lab.helpdesk.support.web;

import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import lab.helpdesk.support.SupportException;
import static lab.helpdesk.support.SupportException.Code.INVALID_ASSIGNMENT_REQUEST;

record AssignmentRequest(UUID clientRequestId,long expectedVersion,Long toAgentId,String reason) {
    static AssignmentRequest parse(JsonNode json,boolean transfer) {
        Set<String> fields=transfer ? Set.of("clientRequestId","expectedAssignmentVersion","toAgentId","reason")
                : Set.of("clientRequestId","expectedAssignmentVersion");
        if (json==null || !json.isObject() || json.size()!=fields.size()
                || !fields.containsAll(json.propertyNames()) || !json.path("clientRequestId").isString()) invalid();
        UUID id;
        try {
            String raw=json.path("clientRequestId").asString();id=UUID.fromString(raw);
            if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw new SupportException(INVALID_ASSIGNMENT_REQUEST); }
        JsonNode version=json.path("expectedAssignmentVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong()<0 || version.asLong()==Long.MAX_VALUE) invalid();
        Long target=null;String reason=null;
        if (transfer) {
            JsonNode to=json.path("toAgentId");
            if (!to.isIntegralNumber() || !to.canConvertToLong() || to.asLong()<=0 || !json.path("reason").isString()) invalid();
            target=to.asLong();reason=json.path("reason").asString();
            if (reason.isBlank() || reason.codePointCount(0,reason.length())>500) invalid();
        }
        return new AssignmentRequest(id,version.asLong(),target,reason);
    }
    private static void invalid() { throw new SupportException(INVALID_ASSIGNMENT_REQUEST); }
}
