package lab.helpdesk.ai.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates the output contract, not the factual accuracy of an AI suggestion. */
public final class AiSuggestionOutputValidator {

    private static final Set<String> FIELDS = Set.of("decision", "summary", "categories", "priority");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final int maxSummaryCodePoints;

    public AiSuggestionOutputValidator(int maxSummaryCodePoints) {
        if (maxSummaryCodePoints < 1) {
            throw new IllegalArgumentException("maxSummaryCodePoints must be positive");
        }
        this.maxSummaryCodePoints = maxSummaryCodePoints;
    }

    public ContractValidatedOutput validate(String rawOutput) {
        JsonNode root = parse(rawOutput);
        if (root == null || !root.isObject()) {
            throw invalid(Violation.ROOT_SHAPE);
        }
        if (root.size() != FIELDS.size() || !root.propertyNames().containsAll(FIELDS)) {
            boolean missingOnly = FIELDS.containsAll(root.propertyNames())
                    && !root.propertyNames().containsAll(FIELDS);
            throw new InvalidOutputException(Violation.FIELD_SET, missingOnly);
        }

        Decision decision = readEnum(root.get("decision"), Decision.class, Violation.DECISION);
        if (decision == Decision.ABSTAIN) {
            if (!root.get("summary").isNull() || !root.get("categories").isNull()
                    || !root.get("priority").isNull()) {
                throw invalid(Violation.ABSTAIN_SHAPE);
            }
            return new ContractValidatedOutput(decision, null, null, null);
        }

        String summary = readSummary(root.get("summary"));
        List<Category> categories = readCategories(root.get("categories"));
        Priority priority = readEnum(root.get("priority"), Priority.class, Violation.PRIORITY);
        return new ContractValidatedOutput(decision, summary, categories, priority);
    }

    private JsonNode parse(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw invalid(Violation.JSON_SYNTAX);
        }
        try {
            return MAPPER.readTree(rawOutput);
        } catch (JacksonException exception) {
            // Parser messages may contain the original payload. Do not retain them as a cause.
            throw invalid(Violation.JSON_SYNTAX);
        }
    }

    private String readSummary(JsonNode value) {
        if (!value.isString()) {
            throw invalid(Violation.SUMMARY);
        }
        String summary = value.asString();
        int length = trimmedCodePointCount(summary);
        if (length < 1 || length > maxSummaryCodePoints) {
            throw invalid(Violation.SUMMARY);
        }
        return summary;
    }

    private List<Category> readCategories(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            throw invalid(Violation.CATEGORIES);
        }
        List<Category> categories = new ArrayList<>();
        for (JsonNode item : value) {
            Category category = readEnum(item, Category.class, Violation.CATEGORIES);
            if (categories.contains(category)) {
                throw invalid(Violation.CATEGORIES);
            }
            categories.add(category);
        }
        return List.copyOf(categories);
    }

    private <E extends Enum<E>> E readEnum(JsonNode value, Class<E> type, Violation violation) {
        if (!value.isString()) {
            throw invalid(violation);
        }
        try {
            return Enum.valueOf(type, value.asString());
        } catch (IllegalArgumentException exception) {
            throw invalid(violation);
        }
    }

    private int trimmedCodePointCount(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isTrimSpace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isTrimSpace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.codePointCount(start, end);
    }

    // Match ECMAScript String.trim whitespace; count without changing the returned summary.
    private boolean isTrimSpace(int codePoint) {
        return (codePoint >= 0x2000 && codePoint <= 0x200a)
                || switch (codePoint) {
                    case 0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020, 0x00a0,
                            0x1680, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff -> true;
                    default -> false;
                };
    }

    private InvalidOutputException invalid(Violation violation) {
        return new InvalidOutputException(violation);
    }

    public enum Decision {
        SUGGEST, ABSTAIN
    }

    public enum Category {
        ACCOUNT, BILLING, TECHNICAL, OTHER, UNDETERMINED
    }

    public enum Priority {
        NORMAL, HIGH, UNDETERMINED
    }

    public enum Violation {
        JSON_SYNTAX, ROOT_SHAPE, FIELD_SET, DECISION, SUMMARY, CATEGORIES, PRIORITY, ABSTAIN_SHAPE
    }

    public static final class InvalidOutputException extends IllegalArgumentException {

        private final Violation violation;
        private final boolean repairableRequiredFieldMissing;

        private InvalidOutputException(Violation violation) {
            this(violation, false);
        }

        private InvalidOutputException(Violation violation, boolean repairableRequiredFieldMissing) {
            super("AI_OUTPUT_" + violation.name());
            this.violation = violation;
            this.repairableRequiredFieldMissing = repairableRequiredFieldMissing;
        }

        public Violation violation() {
            return violation;
        }

        public boolean repairableRequiredFieldMissing() {
            return repairableRequiredFieldMissing;
        }
    }

    /** Contract validation alone does not authorize storage, tools, or ticket state changes. */
    public static final class ContractValidatedOutput {

        private final Decision decision;
        private final String summary;
        private final List<Category> categories;
        private final Priority priority;

        private ContractValidatedOutput(Decision decision, String summary,
                List<Category> categories, Priority priority) {
            this.decision = decision;
            this.summary = summary;
            this.categories = categories;
            this.priority = priority;
        }

        public Decision decision() {
            return decision;
        }

        public String summary() {
            return summary;
        }

        public List<Category> categories() {
            return categories;
        }

        public Priority priority() {
            return priority;
        }
    }
}
