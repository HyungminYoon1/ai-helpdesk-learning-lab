package lab.helpdesk.ai.input;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiInputSpanMasker.InputField;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator.InvalidScanResultException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Standalone V2 structure/range/relation-coherence validator, not a detector,
 * masker, Spring Bean or provider connection. It does not prove that a reported
 * relation is true in the source. V1 remains a separate, unchanged contract.
 */
public final class AiPrivacyScanResultV2Validator {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "completed", "spans", "personRelations");
    private static final Set<String> SPAN_FIELDS = Set.of("field", "start", "end", "type");
    private static final Set<String> PERSON_FIELDS = Set.of("field", "start", "end", "type", "mentionId");
    private static final Set<String> RELATION_FIELDS = Set.of("left", "right", "relation");
    private static final Comparator<ScanSpan> POSITION_ORDER = Comparator
            .comparing(ScanSpan::field)
            .thenComparingInt(ScanSpan::startCodePoint)
            .thenComparingInt(ScanSpan::endCodePoint);
    private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(3).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final int maxResponseUtf8Bytes;
    private final int maxSpans;
    private final int maxPersonRelations;

    /** Zero relation capacity explicitly permits only zero or one PERSON. */
    public AiPrivacyScanResultV2Validator(int maxResponseUtf8Bytes, int maxSpans, int maxPersonRelations) {
        if (maxResponseUtf8Bytes < 1 || maxSpans < 1 || maxPersonRelations < 0) {
            throw new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
        }
        this.maxResponseUtf8Bytes = maxResponseUtf8Bytes;
        this.maxSpans = maxSpans;
        this.maxPersonRelations = maxPersonRelations;
    }

    public ValidatedScan validate(String title, String body, String rawResult) {
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException("AI_INPUT_REQUIRED");
        }
        JsonNode root = parse(rawResult);
        requireExactFields(root, ROOT_FIELDS);
        if (readInteger(root.get("schemaVersion")) != 2
                || !root.get("completed").isBoolean() || !root.get("completed").asBoolean()) {
            throw invalid();
        }
        JsonNode spanNodes = root.get("spans");
        JsonNode relationNodes = root.get("personRelations");
        if (!spanNodes.isArray() || spanNodes.size() > maxSpans
                || !relationNodes.isArray() || relationNodes.size() > maxPersonRelations) {
            throw invalid();
        }

        List<ScanSpan> spans = readSpans(title, body, spanNodes);
        Map<String, Integer> mentionIndexes = indexMentions(spans);
        int count = mentionIndexes.size();
        long expectedRelations = (long) count * (count - 1) / 2;
        // Check before allocating any pair map; no missing-pair filling or matrix.
        if (expectedRelations > maxPersonRelations || expectedRelations != relationNodes.size()) {
            throw invalid();
        }
        List<PersonRelation> relations = readRelations(mentionIndexes, relationNodes);
        return new ValidatedScan(spans, relations);
    }

    private List<ScanSpan> readSpans(String title, String body, JsonNode nodes) {
        int titleLength = title.codePointCount(0, title.length());
        int bodyLength = body.codePointCount(0, body.length());
        Set<ScanSpan> unique = new HashSet<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()) throw invalid();
            SensitiveType type = readType(node.get("type"));
            requireExactFields(node, type == SensitiveType.PERSON ? PERSON_FIELDS : SPAN_FIELDS);
            InputField field = readField(node.get("field"));
            int start = readInteger(node.get("start"));
            int end = readInteger(node.get("end"));
            if (start < 0 || start >= end || end > (field == InputField.TITLE ? titleLength : bodyLength)) {
                throw invalid();
            }
            String mentionId = type == SensitiveType.PERSON ? readString(node.get("mentionId")) : null;
            unique.add(new ScanSpan(field, start, end, type, mentionId));
        }
        List<ScanSpan> result = new ArrayList<>(unique);
        result.sort(POSITION_ORDER);
        ScanSpan previous = null;
        for (ScanSpan span : result) {
            if (previous != null && previous.field() == span.field()
                    && span.startCodePoint() < previous.endCodePoint()) {
                throw invalid();
            }
            previous = span;
        }
        return result;
    }

    private Map<String, Integer> indexMentions(List<ScanSpan> spans) {
        Map<String, Integer> indexes = new HashMap<>();
        for (ScanSpan span : spans) {
            if (span.type() != SensitiveType.PERSON) continue;
            int index = indexes.size();
            // Canonical position-based IDs, never names, user IDs or global identity.
            if (!("m" + (index + 1)).equals(span.mentionId())) throw invalid();
            indexes.put(span.mentionId(), index);
        }
        return indexes;
    }

    private List<PersonRelation> readRelations(Map<String, Integer> mentions, JsonNode nodes) {
        Map<Pair, Relation> pairs = new HashMap<>();
        int[] parents = new int[mentions.size()];
        for (int index = 0; index < parents.length; index++) parents[index] = index;

        for (JsonNode node : nodes) {
            requireExactFields(node, RELATION_FIELDS);
            Integer left = mentions.get(readString(node.get("left")));
            Integer right = mentions.get(readString(node.get("right")));
            Relation relation = readRelation(node.get("relation"));
            if (left == null || right == null || left.equals(right)) throw invalid();
            Pair pair = pair(left, right);
            if (pairs.putIfAbsent(pair, relation) != null) throw invalid();
            if (relation == Relation.SAME) {
                parents[find(parents, left)] = find(parents, right);
            }
        }

        Map<Pair, Relation> componentRelations = new HashMap<>();
        for (var entry : pairs.entrySet()) {
            int left = find(parents, entry.getKey().left());
            int right = find(parents, entry.getKey().right());
            Relation relation = entry.getValue();
            if (left == right) {
                // The final relation table must already include SAME closure.
                if (relation != Relation.SAME) throw invalid();
            } else {
                Relation existing = componentRelations.putIfAbsent(pair(left, right), relation);
                // Equal people must have equal reported relations to a third person.
                if (existing != null && existing != relation) throw invalid();
            }
        }

        return pairs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(Pair::left)
                        .thenComparingInt(Pair::right)))
                .map(entry -> new PersonRelation("m" + (entry.getKey().left() + 1),
                        "m" + (entry.getKey().right() + 1), entry.getValue()))
                .toList();
    }

    private static Pair pair(int left, int right) {
        return new Pair(Math.min(left, right), Math.max(left, right));
    }

    private static int find(int[] parents, int value) {
        int root = value;
        while (parents[root] != root) root = parents[root];
        while (parents[value] != value) {
            int next = parents[value];
            parents[value] = root;
            value = next;
        }
        return root;
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.length() > maxResponseUtf8Bytes || raw.isBlank()
                || raw.getBytes(StandardCharsets.UTF_8).length > maxResponseUtf8Bytes) {
            throw invalid();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JacksonException exception) {
            throw invalid();
        }
    }

    private void requireExactFields(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()
                || !value.propertyNames().containsAll(fields)) throw invalid();
    }

    private String readString(JsonNode value) {
        if (value == null || !value.isString()) throw invalid();
        return value.asString();
    }

    private int readInteger(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        return value.intValue();
    }

    private InputField readField(JsonNode value) {
        return switch (readString(value)) {
            case "title" -> InputField.TITLE;
            case "body" -> InputField.BODY;
            default -> throw invalid();
        };
    }

    private SensitiveType readType(JsonNode value) {
        try {
            return SensitiveType.valueOf(readString(value));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private Relation readRelation(JsonNode value) {
        try {
            return Relation.valueOf(readString(value));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static InvalidScanResultException invalid() {
        return new InvalidScanResultException();
    }

    public enum Relation { SAME, DIFFERENT, UNDETERMINED }

    public record ScanSpan(InputField field, int startCodePoint, int endCodePoint,
            SensitiveType type, String mentionId) {
        @Override
        public String toString() { return "PrivacyScanV2Span[content omitted]"; }
    }

    public record PersonRelation(String left, String right, Relation relation) {
        @Override
        public String toString() { return "PrivacyScanV2Relation[references omitted]"; }
    }

    /** No original text or raw JSON is retained; lists and members are immutable. */
    public static final class ValidatedScan {
        private final List<ScanSpan> spans;
        private final List<PersonRelation> personRelations;

        private ValidatedScan(List<ScanSpan> spans, List<PersonRelation> personRelations) {
            this.spans = List.copyOf(spans);
            this.personRelations = List.copyOf(personRelations);
        }

        public List<ScanSpan> spans() { return spans; }
        public List<PersonRelation> personRelations() { return personRelations; }

        @Override
        public String toString() { return "ValidatedPrivacyScanV2[content omitted]"; }
    }

    private record Pair(int left, int right) { }
}
