package lab.helpdesk.ai.input;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces known sensitive fragments with configured type labels in a transmission
 * copy and checks the final serialized request. It does not detect or infer types.
 * It does not modify a stored Message or implement its retention policy.
 */
public final class AiInputPrivacyGuard {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final List<SensitiveFragment> knownSensitiveFragments;

    public AiInputPrivacyGuard(Collection<SensitiveFragment> knownSensitiveFragments) {
        if (knownSensitiveFragments == null || knownSensitiveFragments.isEmpty()) {
            throw new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
        }
        Map<String, SensitiveType> configuredTypes = new HashMap<>();
        for (SensitiveFragment fragment : knownSensitiveFragments) {
            if (fragment == null) {
                throw new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
            }
            for (SensitiveType type : SensitiveType.values()) {
                // A replacement label must not be redacted again or fail the final check.
                if (type.placeholder().contains(fragment.value())) {
                    throw new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
                }
            }
            SensitiveType previousType = configuredTypes.putIfAbsent(fragment.value(), fragment.type());
            if (previousType != null && previousType != fragment.type()) {
                throw new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
            }
        }
        this.knownSensitiveFragments = knownSensitiveFragments.stream()
                .distinct()
                .sorted(Comparator.comparingInt((SensitiveFragment fragment) -> fragment.value().length())
                        .reversed())
                .toList();
    }

    public PreparedInput prepare(String title, String body) {
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException("AI_INPUT_REQUIRED");
        }
        return new PreparedInput(redact(title), redact(body));
    }

    /** Return the same immutable String that was checked; send this exact body. */
    public String checkSerializedRequest(String serializedBody) {
        JsonNode request;
        try {
            request = MAPPER.readTree(serializedBody);
        } catch (JacksonException | IllegalArgumentException exception) {
            // Parser errors can contain the payload. Keep neither its message nor cause.
            throw new IllegalArgumentException("AI_REQUEST_BODY_INVALID");
        }
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("AI_REQUEST_BODY_INVALID");
        }
        if (containsSensitiveValue(request)) {
            throw new IllegalArgumentException("AI_REQUEST_SENSITIVE_VALUE_PRESENT");
        }
        return serializedBody;
    }

    private String redact(String value) {
        String copy = value;
        for (SensitiveFragment fragment : knownSensitiveFragments) {
            copy = copy.replace(fragment.value(), fragment.type().placeholder());
        }
        return copy;
    }

    private boolean containsSensitiveValue(JsonNode node) {
        if (node.isString()) {
            return containsSensitiveValue(node.asString());
        }
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                if (containsSensitiveValue(name) || containsSensitiveValue(node.get(name))) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (containsSensitiveValue(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsSensitiveValue(String value) {
        return knownSensitiveFragments.stream().anyMatch(fragment -> value.contains(fragment.value()));
    }

    public enum SensitiveType {
        EMAIL("[EMAIL_REDACTED]"),
        PHONE("[PHONE_REDACTED]"),
        INSTAGRAM_HANDLE("[INSTAGRAM_HANDLE_REDACTED]"),
        ADDRESS("[ADDRESS_REDACTED]"),
        PASSWORD("[PASSWORD_REDACTED]"),
        CONTACT("[CONTACT_REDACTED]"),
        UNKNOWN("[REDACTED]");

        private final String placeholder;

        SensitiveType(String placeholder) {
            this.placeholder = placeholder;
        }

        public String placeholder() {
            return placeholder;
        }
    }

    public record SensitiveFragment(String value, SensitiveType type) {
        public SensitiveFragment {
            if (value == null || value.isBlank() || type == null) {
                throw new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
            }
        }

        @Override
        public String toString() {
            return "SensitiveFragment[type=" + type + ", value omitted]";
        }
    }

    public record PreparedInput(String title, String body) {
        @Override
        public String toString() {
            // Other sensitive content may be unknown to this limited guard.
            return "PreparedInput[content omitted]";
        }
    }
}
