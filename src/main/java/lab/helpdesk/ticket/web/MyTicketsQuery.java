package lab.helpdesk.ticket.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import lab.helpdesk.ticket.application.ConversationException;
import lab.helpdesk.ticket.repository.MyTicketsRepository.Cursor;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Bounded opaque pagination data. Ownership always comes from Authentication. */
record MyTicketsQuery(int limit, Cursor cursor) {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    static MyTicketsQuery parse(MultiValueMap<String,String> params) {
        try {
            if (!Set.of("limit","cursor").containsAll(params.keySet())
                    || params.values().stream().anyMatch(values -> values.size() != 1)) throw invalid();
            String rawLimit = params.getFirst("limit");
            if (rawLimit != null && !rawLimit.matches("[0-9]{1,3}")) throw invalid();
            int limit = rawLimit == null ? 50 : Integer.parseInt(rawLimit);
            if (limit < 1 || limit > 100) throw invalid();
            String rawCursor = params.getFirst("cursor");
            if (rawCursor == null) return new MyTicketsQuery(limit, null);
            if (!rawCursor.matches("[A-Za-z0-9_-]{1,1024}")) throw invalid();
            byte[] bytes = Base64.getUrlDecoder().decode(rawCursor);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(rawCursor)) throw invalid();
            var node = JSON.readTree(bytes);
            if (!node.isObject() || node.size() != 3
                    || !List.of("v","lastActivityAt","ticketId").stream().allMatch(node::has)
                    || !node.get("v").isIntegralNumber() || !node.get("v").canConvertToInt() || node.get("v").asInt() != 1
                    || !node.get("ticketId").isIntegralNumber() || !node.get("ticketId").canConvertToLong()
                    || node.get("ticketId").asLong() <= 0) throw invalid();
            var time = node.get("lastActivityAt");
            if (!time.isNull() && !time.isString()) throw invalid();
            Instant instant = time.isNull() ? null : Instant.parse(time.asString());
            if (instant != null && (instant.atOffset(ZoneOffset.UTC).getYear() < 1
                    || instant.atOffset(ZoneOffset.UTC).getYear() > 9999)) throw invalid();
            return new MyTicketsQuery(limit, new Cursor(instant, node.get("ticketId").asLong()));
        } catch (RuntimeException malformed) { throw invalid(); }
    }

    static String encode(Cursor cursor) {
        if (cursor == null) return null;
        var json = JSON.createObjectNode().put("v",1).put("ticketId",cursor.ticketId());
        if (cursor.lastActivityAt() == null) json.putNull("lastActivityAt");
        else json.put("lastActivityAt",cursor.lastActivityAt().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsString(json).getBytes(StandardCharsets.UTF_8));
    }
    private static ConversationException invalid() { return new ConversationException(ConversationException.Code.INVALID_REQUEST); }
}
