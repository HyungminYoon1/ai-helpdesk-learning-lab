package lab.helpdesk.ai.input;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Experiment-only bridge; it receives no API key and makes no network requests. */
public final class AiInputPrivacyRequestBridge {

    private static final int MAX_BYTES = 16_384;
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final AiInputPrivacyGuard GUARD = new AiInputPrivacyGuard(List.of(
            new SensitiveFragment("<합성_비밀번호>", SensitiveType.PASSWORD),
            new SensitiveFragment("<합성_주소>", SensitiveType.ADDRESS),
            new SensitiveFragment("<합성_연락처>", SensitiveType.CONTACT),
            new SensitiveFragment("<합성_이메일>", SensitiveType.EMAIL),
            new SensitiveFragment("<합성_휴대폰>", SensitiveType.PHONE),
            new SensitiveFragment("<합성_인스타그램>", SensitiveType.INSTAGRAM_HANDLE)));

    private AiInputPrivacyRequestBridge() {
    }

    public static String prepareForTransmission(String serializedRequest) {
        if (serializedRequest == null || serializedRequest.isBlank()
                || serializedRequest.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalidRequest();
        }
        JsonNode root = MAPPER.readTree(serializedRequest);
        if (!(root instanceof ObjectNode request)) {
            throw invalidRequest();
        }
        JsonNode input = request.get("input");
        if (input == null || !input.isArray() || input.size() != 1
                || !(input.get(0) instanceof ObjectNode message)
                || !"user".equals(readString(message.get("role")))) {
            throw invalidRequest();
        }
        JsonNode original = MAPPER.readTree(readString(message.get("content")));
        if (original == null || !original.isObject() || original.size() != 2
                || !original.propertyNames().containsAll(Set.of("title", "body"))) {
            throw invalidRequest();
        }
        var prepared = GUARD.prepare(readString(original.get("title")), readString(original.get("body")));
        ObjectNode preparedContent = MAPPER.createObjectNode();
        // Keep model-visible JSON ordering identical across modes and JVM executions.
        preparedContent.put("title", prepared.title());
        preparedContent.put("body", prepared.body());
        message.put("content", MAPPER.writeValueAsString(preparedContent));

        String checkedBody = GUARD.checkSerializedRequest(MAPPER.writeValueAsString(request));
        if (checkedBody.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalidRequest();
        }
        return checkedBody;
    }

    public static void main(String[] args) {
        try {
            if (args.length != 0) {
                throw invalidRequest();
            }
            byte[] bytes = System.in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw invalidRequest();
            }
            String input = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            String checkedBody = prepareForTransmission(input);
            var writer = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            writer.write(checkedBody);
            writer.flush();
        } catch (IOException | RuntimeException exception) {
            // Neither parser diagnostics nor input content goes to stderr.
            System.err.println("AI_PRIVACY_REQUEST_REJECTED");
            System.exit(1);
        }
    }

    private static String readString(JsonNode node) {
        if (node == null || !node.isString()) {
            throw invalidRequest();
        }
        return node.asString();
    }

    private static IllegalArgumentException invalidRequest() {
        return new IllegalArgumentException("AI_REQUEST_BODY_INVALID");
    }
}
