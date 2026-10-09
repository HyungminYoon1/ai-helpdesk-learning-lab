package lab.helpdesk.ai.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiInputSpanMasker.DetectedSpan;
import lab.helpdesk.ai.input.AiInputSpanMasker.InputField;

class AiInputSpanMaskerTest {
    private final AiInputSpanMasker masker = new AiInputSpanMasker();

    @Test
    void returnsAnUnchangedCopyForAnEmptyCompletedDetectionList() {
        var result = masker.mask("문의", "  로그인 복구 완료\n<strong>문자열</strong>\t", List.of());

        assertThat(result.title()).isEqualTo("문의");
        assertThat(result.body()).isEqualTo("  로그인 복구 완료\n<strong>문자열</strong>\t");
    }

    @Test
    void preservesOriginalFieldsBusinessFactsAndTheCallersList() {
        String title = "문의 MAIL";
        String body = "  연락 MAIL; 로그인 복구 완료\n";
        var titleDetection = new DetectedSpan(InputField.TITLE, 3, 7, SensitiveType.EMAIL);
        var bodyDetection = new DetectedSpan(InputField.BODY, 5, 9, SensitiveType.EMAIL);
        var detections = new ArrayList<>(List.of(bodyDetection, titleDetection));

        var result = masker.mask(title, body, detections);

        assertThat(result.title()).isEqualTo("문의 [EMAIL_REDACTED]");
        assertThat(result.body()).isEqualTo("  연락 [EMAIL_REDACTED]; 로그인 복구 완료\n");
        assertThat(title).isEqualTo("문의 MAIL");
        assertThat(body).isEqualTo("  연락 MAIL; 로그인 복구 완료\n");
        assertThat(detections).containsExactly(bodyDetection, titleDetection);
    }

    @Test
    void replacesMultipleRangesUsingOriginalPositionsEvenWhenInputOrderIsReversed() {
        var result = masker.mask("문의", "MAIL PHONE 끝", List.of(
                new DetectedSpan(InputField.BODY, 5, 10, SensitiveType.PHONE),
                new DetectedSpan(InputField.BODY, 0, 4, SensitiveType.EMAIL)));

        assertThat(result.body()).isEqualTo("[EMAIL_REDACTED] [PHONE_REDACTED] 끝");
    }

    @Test
    void replacesOnlyTheDetectedOccurrenceOfARepeatedValue() {
        var result = masker.mask("문의", "MAIL MAIL", List.of(
                new DetectedSpan(InputField.BODY, 5, 9, SensitiveType.EMAIL)));

        assertThat(result.body()).isEqualTo("MAIL [EMAIL_REDACTED]");
    }

    @Test
    void replacesBothOccurrencesWhenBothRangesAreDetected() {
        var result = masker.mask("문의", "MAIL MAIL", List.of(
                new DetectedSpan(InputField.BODY, 0, 4, SensitiveType.EMAIL),
                new DetectedSpan(InputField.BODY, 5, 9, SensitiveType.EMAIL)));

        assertThat(result.body()).isEqualTo("[EMAIL_REDACTED] [EMAIL_REDACTED]");
    }

    @ParameterizedTest
    @EnumSource(SensitiveType.class)
    void usesTheConfirmedTypeLabelWithoutGuessing(SensitiveType type) {
        var result = masker.mask("문의", "VALUE", List.of(
                new DetectedSpan(InputField.BODY, 0, 5, type)));

        assertThat(result.body()).isEqualTo(type.placeholder());
    }

    @Test
    void appliesAnExactDuplicateOnlyOnce() {
        var detection = new DetectedSpan(InputField.BODY, 0, 4, SensitiveType.EMAIL);

        var result = masker.mask("문의", "MAIL 끝", List.of(detection,
                new DetectedSpan(InputField.BODY, 0, 4, SensitiveType.EMAIL), detection));

        assertThat(result.body()).isEqualTo("[EMAIL_REDACTED] 끝");
    }

    @ParameterizedTest
    @MethodSource("independentFieldTypes")
    void treatsIdenticalPositionsInDifferentFieldsIndependently(SensitiveType bodyType) {
        var result = masker.mask("VALUE", "VALUE", List.of(
                new DetectedSpan(InputField.TITLE, 0, 5, SensitiveType.EMAIL),
                new DetectedSpan(InputField.BODY, 0, 5, bodyType)));

        assertThat(result.title()).isEqualTo("[EMAIL_REDACTED]");
        assertThat(result.body()).isEqualTo(bodyType.placeholder());
    }

    @Test
    void acceptsAdjacentRangesWithoutCombiningTheirTypes() {
        var result = masker.mask("문의", "AB", List.of(
                new DetectedSpan(InputField.BODY, 1, 2, SensitiveType.PHONE),
                new DetectedSpan(InputField.BODY, 0, 1, SensitiveType.EMAIL)));

        assertThat(result.body()).isEqualTo("[EMAIL_REDACTED][PHONE_REDACTED]");
    }

    @ParameterizedTest
    @MethodSource("overlappingRanges")
    void rejectsConflictsAndOverlapsInEitherInputOrder(List<DetectedSpan> detections) {
        assertInvalid(detections);
        assertInvalid(detections.reversed());
    }

    @Test
    void convertsBothCodePointBoundariesAfterAnEmoji() {
        // In A😀B, B is code point [2, 3), but UTF-16 [3, 4).
        var result = masker.mask("문의", "A😀B", List.of(
                new DetectedSpan(InputField.BODY, 2, 3, SensitiveType.CONTACT)));

        assertThat(result.body()).isEqualTo("A😀[CONTACT_REDACTED]");
    }

    @Test
    void replacesAnEntireSupplementaryCodePointWithoutSplittingASurrogatePair() {
        var result = masker.mask("문의", "A😀B", List.of(
                new DetectedSpan(InputField.BODY, 1, 2, SensitiveType.UNKNOWN)));

        assertThat(result.body()).isEqualTo("A[REDACTED]B");
    }

    @Test
    void convertsPositionsInBothFieldsWithoutShiftingLaterRanges() {
        var result = masker.mask("😀메일 끝", "😀A😀B😀", List.of(
                new DetectedSpan(InputField.BODY, 3, 4, SensitiveType.PHONE),
                new DetectedSpan(InputField.TITLE, 1, 3, SensitiveType.EMAIL),
                new DetectedSpan(InputField.BODY, 1, 2, SensitiveType.CONTACT)));

        assertThat(result.title()).isEqualTo("😀[EMAIL_REDACTED] 끝");
        assertThat(result.body()).isEqualTo("😀[CONTACT_REDACTED]😀[PHONE_REDACTED]😀");
    }

    @Test
    void acceptsAWholeFieldRangeAtTheCodePointBoundaries() {
        var result = masker.mask("😀한글", "😀끝", List.of(
                new DetectedSpan(InputField.TITLE, 0, 3, SensitiveType.UNKNOWN),
                new DetectedSpan(InputField.BODY, 0, 2, SensitiveType.UNKNOWN)));

        assertThat(result.title()).isEqualTo("[REDACTED]");
        assertThat(result.body()).isEqualTo("[REDACTED]");
    }

    @Test
    void rejectsAnEndBeyondCodePointCountEvenWhenItFitsUtf16Length() {
        assertThatThrownBy(() -> masker.mask("문의", "A😀B", List.of(
                new DetectedSpan(InputField.BODY, 2, 4, SensitiveType.CONTACT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT")
                .hasNoCause();
    }

    @Test
    void validatesEachRangeAgainstItsOwnFieldLength() {
        // [0, 3) fits the body but not the two-code-point title.
        assertInvalid(List.of(new DetectedSpan(InputField.TITLE, 0, 3, SensitiveType.EMAIL)));
    }

    @ParameterizedTest
    @MethodSource("invalidRanges")
    void rejectsInvalidRangeBoundsWithoutClamping(int start, int end) {
        assertInvalid(List.of(new DetectedSpan(InputField.BODY, start, end, SensitiveType.EMAIL)));
    }

    @Test
    void rejectsAMissingDetectionCollectionRatherThanTreatingItAsAnEmptyScan() {
        assertInvalid(null);
    }

    @Test
    void rejectsAMissingDetection() {
        assertInvalid(Arrays.asList((DetectedSpan) null));
    }

    @Test
    void rejectsAMissingField() {
        assertInvalid(List.of(new DetectedSpan(null, 0, 1, SensitiveType.EMAIL)));
    }

    @Test
    void rejectsAMissingType() {
        assertInvalid(List.of(new DetectedSpan(InputField.BODY, 0, 1, null)));
    }

    @Test
    void rejectsTheWholeOperationWhenTheOtherFieldHasAnInvalidRange() {
        var detections = List.of(
                new DetectedSpan(InputField.TITLE, 0, 1, SensitiveType.EMAIL),
                new DetectedSpan(InputField.BODY, 0, 7, SensitiveType.PHONE));

        assertInvalid(detections);
        assertInvalid(detections.reversed());
    }

    @Test
    void rejectsTheWholeOperationWhenTheOtherFieldHasConflictingTypes() {
        assertInvalid(List.of(
                new DetectedSpan(InputField.TITLE, 0, 1, SensitiveType.EMAIL),
                new DetectedSpan(InputField.BODY, 0, 2, SensitiveType.EMAIL),
                new DetectedSpan(InputField.BODY, 0, 2, SensitiveType.PHONE)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsMissingInputWithTheExistingInputError(String missing) {
        assertThatThrownBy(() -> masker.mask(missing, "본문", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI_INPUT_REQUIRED")
                .hasNoCause();
        assertThatThrownBy(() -> masker.mask("제목", missing, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI_INPUT_REQUIRED")
                .hasNoCause();
    }

    @Test
    void doesNotIncludeInputContentInTheResultToString() {
        var result = masker.mask("SYNTHETIC_PRIVATE_TITLE", "SYNTHETIC_PRIVATE_BODY", List.of());

        assertThat(result.toString()).isEqualTo("MaskedInput[content omitted]");
    }

    private void assertInvalid(Collection<DetectedSpan> detections) {
        assertThatThrownBy(() -> masker.mask("제목", "ABCDEF", detections))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT")
                .hasNoCause();
    }

    private static Stream<SensitiveType> independentFieldTypes() {
        return Stream.of(SensitiveType.EMAIL, SensitiveType.PHONE);
    }

    private static Stream<Arguments> overlappingRanges() {
        return Stream.of(
                pair(0, 3, SensitiveType.EMAIL, 0, 3, SensitiveType.PHONE),
                pair(0, 3, SensitiveType.EMAIL, 2, 5, SensitiveType.EMAIL),
                pair(0, 3, SensitiveType.EMAIL, 2, 5, SensitiveType.PHONE),
                pair(0, 6, SensitiveType.EMAIL, 2, 4, SensitiveType.EMAIL),
                pair(0, 6, SensitiveType.EMAIL, 2, 4, SensitiveType.PHONE),
                pair(0, 4, SensitiveType.EMAIL, 0, 2, SensitiveType.PHONE),
                pair(0, 4, SensitiveType.EMAIL, 2, 4, SensitiveType.PHONE));
    }

    private static Arguments pair(int firstStart, int firstEnd, SensitiveType firstType,
            int secondStart, int secondEnd, SensitiveType secondType) {
        return Arguments.of(List.of(
                new DetectedSpan(InputField.BODY, firstStart, firstEnd, firstType),
                new DetectedSpan(InputField.BODY, secondStart, secondEnd, secondType)));
    }

    private static Stream<Arguments> invalidRanges() {
        return Stream.of(
                Arguments.of(-1, 1),
                Arguments.of(0, 0),
                Arguments.of(2, 1),
                Arguments.of(0, -1),
                Arguments.of(0, 7),
                Arguments.of(7, 8),
                Arguments.of(0, Integer.MAX_VALUE));
    }
}
