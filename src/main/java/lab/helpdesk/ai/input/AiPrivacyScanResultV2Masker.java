package lab.helpdesk.ai.input;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiInputSpanMasker.InputField;
import lab.helpdesk.ai.input.AiPrivacyScanResultV2Validator.Relation;
import lab.helpdesk.ai.input.AiPrivacyScanResultV2Validator.ScanSpan;
import lab.helpdesk.ai.input.AiPrivacyScanResultV2Validator.ValidatedScan;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator.InvalidScanResultException;

/**
 * Independent V2 validation and replacement, not a scanner, provider payload or
 * Spring Bean. Different placeholders mean separate SAME components, not proof
 * of different people: explicit UNDETERMINED relations must accompany the copy.
 */
public final class AiPrivacyScanResultV2Masker {
    private static final String RESERVED_PERSON_PREFIX = "[PERSON_";
    private final AiPrivacyScanResultV2Validator validator;
    private final int maxMaskedTextUtf8Bytes;

    /** The byte limit covers title and body together, not a serialized request. */
    public AiPrivacyScanResultV2Masker(AiPrivacyScanResultV2Validator validator, int maxMaskedTextUtf8Bytes) {
        if (validator == null || maxMaskedTextUtf8Bytes < 1) {
            throw new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
        }
        this.validator = validator;
        this.maxMaskedTextUtf8Bytes = maxMaskedTextUtf8Bytes;
    }

    public MaskedScan validateAndMask(String title, String body, String rawResult) {
        // The same immutable strings are used for range validation and replacement.
        // Do not accept a detached ValidatedScan that was checked on another input.
        ValidatedScan scan = validator.validate(title, body, rawResult);
        if (title.contains(RESERVED_PERSON_PREFIX) || body.contains(RESERVED_PERSON_PREFIX)) {
            // A literal supplied by the source must not impersonate a generated ref.
            // Refuse the copy rather than changing the original or guessing an escape.
            throw new InvalidScanResultException();
        }
        Map<String, Integer> groups = groupMentions(scan);
        List<MaskedPersonRelation> relations = maskedRelations(scan, groups);
        TextBudget budget = new TextBudget(maxMaskedTextUtf8Bytes);
        String maskedTitle = replace(title, InputField.TITLE, scan.spans(), groups, budget);
        String maskedBody = replace(body, InputField.BODY, scan.spans(), groups, budget);
        return new MaskedScan(maskedTitle, maskedBody, relations);
    }

    private Map<String, Integer> groupMentions(ValidatedScan scan) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (ScanSpan span : scan.spans()) {
            if (span.type() == SensitiveType.PERSON) indexes.put(span.mentionId(), indexes.size());
        }
        int[] parents = new int[indexes.size()];
        for (int index = 0; index < parents.length; index++) parents[index] = index;
        for (var relation : scan.personRelations()) {
            if (relation.relation() == Relation.SAME) {
                parents[find(parents, indexes.get(relation.left()))] = find(parents, indexes.get(relation.right()));
            }
        }
        Map<Integer, Integer> groupNumbers = new HashMap<>();
        Map<String, Integer> groups = new HashMap<>();
        for (var entry : indexes.entrySet()) {
            int root = find(parents, entry.getValue());
            int number = groupNumbers.computeIfAbsent(root, ignored -> groupNumbers.size() + 1);
            groups.put(entry.getKey(), number);
        }
        return groups;
    }

    private List<MaskedPersonRelation> maskedRelations(ValidatedScan scan, Map<String, Integer> groups) {
        Map<GroupPair, Relation> pairs = new HashMap<>();
        for (var relation : scan.personRelations()) {
            int left = groups.get(relation.left());
            int right = groups.get(relation.right());
            if (left != right) {
                // The validator already proved agreement across each SAME component.
                pairs.putIfAbsent(new GroupPair(Math.min(left, right), Math.max(left, right)), relation.relation());
            }
        }
        return pairs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(GroupPair::left)
                        .thenComparingInt(GroupPair::right)))
                .map(entry -> new MaskedPersonRelation(placeholder(entry.getKey().left()),
                        placeholder(entry.getKey().right()), entry.getValue()))
                .toList();
    }

    private String replace(String original, InputField field, List<ScanSpan> spans,
            Map<String, Integer> groups, TextBudget budget) {
        StringBuilder result = new StringBuilder();
        int fromChar = 0;
        int fromCodePoint = 0;
        for (ScanSpan span : spans) {
            if (span.field() != field) continue;
            int startChar = original.offsetByCodePoints(fromChar, span.startCodePoint() - fromCodePoint);
            int endChar = original.offsetByCodePoints(startChar, span.endCodePoint() - span.startCodePoint());
            budget.append(result, original, fromChar, startChar);
            String replacement = span.type() == SensitiveType.PERSON
                    ? placeholder(groups.get(span.mentionId())) : span.type().placeholder();
            budget.append(result, replacement, 0, replacement.length());
            fromChar = endChar;
            fromCodePoint = span.endCodePoint();
        }
        budget.append(result, original, fromChar, original.length());
        return result.toString();
    }

    private static String placeholder(int group) { return RESERVED_PERSON_PREFIX + group + "]"; }

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

    public record MaskedPersonRelation(String left, String right, Relation relation) {
        @Override
        public String toString() { return "MaskedPrivacyRelationV2[content omitted]"; }
    }

    /** No original positions, mention IDs or name-to-placeholder map is returned. */
    public static final class MaskedScan {
        private final String title;
        private final String body;
        private final List<MaskedPersonRelation> personRelations;

        private MaskedScan(String title, String body, List<MaskedPersonRelation> personRelations) {
            this.title = title;
            this.body = body;
            this.personRelations = List.copyOf(personRelations);
        }

        public String title() { return title; }
        public String body() { return body; }
        public List<MaskedPersonRelation> personRelations() { return personRelations; }

        @Override
        public String toString() { return "MaskedPrivacyScanV2[content omitted]"; }
    }

    private record GroupPair(int left, int right) { }

    /** Bound UTF-8 bytes before appending; never allocate an unbounded encoded copy. */
    private static final class TextBudget {
        private int remaining;

        private TextBudget(int limit) { remaining = limit; }

        private void append(StringBuilder target, String text, int start, int end) {
            for (int index = start; index < end; index++) {
                char value = text.charAt(index);
                int bytes;
                if (value < 0x80) bytes = 1;
                else if (value < 0x800) bytes = 2;
                else if (Character.isHighSurrogate(value) && index + 1 < end
                        && Character.isLowSurrogate(text.charAt(index + 1))) {
                    bytes = 4;
                    index++;
                } else {
                    // String.getBytes(UTF_8) uses one replacement byte for an unpaired surrogate.
                    bytes = Character.isSurrogate(value) ? 1 : 3;
                }
                if (bytes > remaining) throw new InvalidScanResultException();
                remaining -= bytes;
            }
            target.append(text, start, end);
        }
    }
}
