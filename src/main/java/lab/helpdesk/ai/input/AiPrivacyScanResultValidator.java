package lab.helpdesk.ai.input;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiInputSpanMasker.DetectedSpan;
import lab.helpdesk.ai.input.AiInputSpanMasker.InputField;
import lab.helpdesk.ai.input.AiInputSpanMasker.MaskedInput;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates a local scanner's completed JSON result and applies it to the exact
 * original fields. This class does not run a scanner or prove detection accuracy.
 */
public final class AiPrivacyScanResultValidator {
    private static final Set<String> ROOT_FIELDS = Set.of("completed", "spans");
    private static final Set<String> SPAN_FIELDS = Set.of("field", "start", "end", "type");
    // The only containers in the contract are root object -> spans array -> span object.
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final int maxResponseUtf8Bytes;
    private final int maxSpans;
    private final AiInputSpanMasker masker = new AiInputSpanMasker();

    /** Explicit limits; a future transport must also bound reads before building a String. */
    public AiPrivacyScanResultValidator(int maxResponseUtf8Bytes, int maxSpans) {
        if (maxResponseUtf8Bytes < 1 || maxSpans < 1) {
            throw new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
        }
        this.maxResponseUtf8Bytes = maxResponseUtf8Bytes;
        this.maxSpans = maxSpans;
    }

    public MaskedInput validateAndMask(String title, String body, String rawResult) {
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException("AI_INPUT_REQUIRED");
        }
        JsonNode root = parse(rawResult);
        requireExactFields(root, ROOT_FIELDS);
        JsonNode completed = root.get("completed");
        if (!completed.isBoolean() || !completed.asBoolean()) {
            // Incomplete output is not a completed zero-detection scan. This is
            // not proof of a timeout either; the runtime must identify timeouts.
            throw invalidResult();
        }
        JsonNode spans = root.get("spans");
        if (!spans.isArray() || spans.size() > maxSpans) {
            throw invalidResult();
        }
        var detections = new ArrayList<DetectedSpan>(spans.size());
        for (JsonNode span : spans) {
            requireExactFields(span, SPAN_FIELDS);
            detections.add(new DetectedSpan(readField(span.get("field")),
                    readPosition(span.get("start")), readPosition(span.get("end")),
                    readType(span.get("type"))));
        }
        try {
            // Validate BOTH original fields, range bounds and overlaps before
            // returning a copy. Do not salvage only the valid entries.
            return masker.mask(title, body, detections);
        } catch (IllegalArgumentException exception) {
            throw invalidResult();
        }
    }

    private JsonNode parse(String rawResult) {
        if (rawResult == null || rawResult.length() > maxResponseUtf8Bytes || rawResult.isBlank()
                || rawResult.getBytes(StandardCharsets.UTF_8).length > maxResponseUtf8Bytes) {
            throw invalidResult();
        }
        try {
            return MAPPER.readTree(rawResult);
        } catch (JacksonException exception) {
            // Parser messages/causes may contain scanner output or source text.
            throw invalidResult();
        }
    }

    private void requireExactFields(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()
                || !value.propertyNames().containsAll(fields)) {
            throw invalidResult();
        }
    }

    private InputField readField(JsonNode value) {
        if (!value.isString()) {
            throw invalidResult();
        }
        return switch (value.asString()) {
            case "title" -> InputField.TITLE;
            case "body" -> InputField.BODY;
            default -> throw invalidResult();
        };
    }

    private int readPosition(JsonNode value) {
        // Do not coerce a string, boolean, float or out-of-range integer to int.
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidResult();
        }
        return value.intValue();
    }

    private SensitiveType readType(JsonNode value) {
        if (!value.isString()) {
            throw invalidResult();
        }
        try {
            return SensitiveType.valueOf(value.asString());
        } catch (IllegalArgumentException exception) {
            throw invalidResult();
        }
    }

    private InvalidScanResultException invalidResult() {
        return new InvalidScanResultException();
    }

    /** A typed, fixed error; contains no raw response, original input or cause. */
    public static final class InvalidScanResultException extends IllegalArgumentException {
        private InvalidScanResultException() {
            super("PRIVACY_SCAN_INVALID_RESULT");
        }
    }
}
