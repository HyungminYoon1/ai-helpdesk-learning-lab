package lab.helpdesk.ai.input;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;

/**
 * Builds a transmission copy from already detected spans; it does not detect
 * sensitive data, run a model, or decide whether a scan completed successfully.
 * Positions are zero-based Unicode code points in the unchanged original field,
 * with an inclusive start and an exclusive end.
 */
public final class AiInputSpanMasker {
    private static final Comparator<ValidatedSpan> POSITION_ORDER = Comparator
            .comparingInt(ValidatedSpan::start)
            .thenComparingInt(ValidatedSpan::end);

    /**
     * Applies the result of a completed scan. An empty collection means no spans
     * were detected, not that a timeout or scanner error should be ignored.
     * The caller is responsible for confirming scan completion and accuracy.
     */
    public MaskedInput mask(String title, String body, Collection<DetectedSpan> detections) {
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException("AI_INPUT_REQUIRED");
        }
        if (detections == null) {
            throw invalidResult();
        }

        int titleCodePoints = title.codePointCount(0, title.length());
        int bodyCodePoints = body.codePointCount(0, body.length());
        List<ValidatedSpan> titleSpans = new ArrayList<>();
        List<ValidatedSpan> bodySpans = new ArrayList<>();
        Set<DetectedSpan> seen = new HashSet<>();

        // Snapshot the caller's collection, but do not edit it or either original field.
        for (DetectedSpan detection : new ArrayList<>(detections)) {
            if (detection == null || detection.field() == null || detection.type() == null) {
                throw invalidResult();
            }
            String original = detection.field() == InputField.TITLE ? title : body;
            int count = detection.field() == InputField.TITLE ? titleCodePoints : bodyCodePoints;
            if (detection.startCodePoint() < 0
                    || detection.startCodePoint() >= detection.endCodePoint()
                    || detection.endCodePoint() > count) {
                throw invalidResult();
            }
            if (!seen.add(detection)) {
                continue;
            }

            ValidatedSpan span = new ValidatedSpan(
                    original.offsetByCodePoints(0, detection.startCodePoint()),
                    original.offsetByCodePoints(0, detection.endCodePoint()),
                    detection.type());
            (detection.field() == InputField.TITLE ? titleSpans : bodySpans).add(span);
        }

        // Validate BOTH fields before assembling either result. No partial result is returned.
        validateNonOverlapping(titleSpans);
        validateNonOverlapping(bodySpans);
        return new MaskedInput(replace(title, titleSpans), replace(body, bodySpans));
    }

    private void validateNonOverlapping(List<ValidatedSpan> spans) {
        spans.sort(POSITION_ORDER);
        int previousEnd = 0;
        for (ValidatedSpan span : spans) {
            // Exact duplicates are already removed. Conflicting types on the same
            // range and all other overlaps are rejected; adjacent ranges are allowed.
            if (span.start() < previousEnd) {
                throw invalidResult();
            }
            previousEnd = span.end();
        }
    }

    private String replace(String original, List<ValidatedSpan> spans) {
        if (spans.isEmpty()) {
            return original;
        }
        StringBuilder masked = new StringBuilder();
        int from = 0;
        for (ValidatedSpan span : spans) {
            masked.append(original, from, span.start()).append(span.type().placeholder());
            from = span.end();
        }
        return masked.append(original, from, original.length()).toString();
    }

    private IllegalArgumentException invalidResult() {
        // No input, detected text, or parser cause is included in the error.
        return new IllegalArgumentException("PRIVACY_SCAN_INVALID_RESULT");
    }

    public enum InputField {
        TITLE,
        BODY
    }

    public record DetectedSpan(InputField field, int startCodePoint, int endCodePoint, SensitiveType type) {
    }

    public record MaskedInput(String title, String body) {
        @Override
        public String toString() {
            // Detection may miss data; even the masked copy must not be logged by default.
            return "MaskedInput[content omitted]";
        }
    }

    private record ValidatedSpan(int start, int end, SensitiveType type) {
    }
}
