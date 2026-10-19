package lab.helpdesk.ai.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiPrivacyScanResultV2Masker.MaskedPersonRelation;
import lab.helpdesk.ai.input.AiPrivacyScanResultV2Validator.Relation;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator.InvalidScanResultException;

class AiPrivacyScanResultV2MaskerTest {
    private final AiPrivacyScanResultV2Validator validator = new AiPrivacyScanResultV2Validator(32_768, 32, 256);
    private final AiPrivacyScanResultV2Masker masker = new AiPrivacyScanResultV2Masker(validator, 16_384);

    @Test
    void preservesUntouchedCharactersWithoutTrimmingOrRewritingTheOriginal() {
        String title = "  로그인 복구 문의 😀  ";
        String body = "\t20명은 17시까지 확인해야 합니다.\n새 링크로 복구했습니다.  ";

        var result = masker.validateAndMask(title, body, envelope("", ""));

        assertThat(result.title()).isEqualTo(title);
        assertThat(result.body()).isEqualTo(body);
        assertThat(result.personRelations()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Relation.class)
    void masksEqualNamesAccordingToTheReportedRelationNotStringEquality(Relation relation) {
        String body = "김철수와 동명이인 김철수";
        var result = masker.validateAndMask("문의", body, envelope(
                person("body", 0, 3, 1) + "," + person("body", 10, 13, 2), pair(1, 2, relation)));

        String second = relation == Relation.SAME ? "[PERSON_1]" : "[PERSON_2]";
        assertThat(result.body()).isEqualTo("[PERSON_1]와 동명이인 " + second);
        if (relation == Relation.SAME) {
            // Structurally consistent but semantically wrong SAME remains a model-evaluation failure.
            assertThat(result.personRelations()).isEmpty();
        } else {
            assertThat(result.personRelations()).containsExactly(
                    new MaskedPersonRelation("[PERSON_1]", "[PERSON_2]", relation));
        }
        assertThat(body).isEqualTo("김철수와 동명이인 김철수");
    }

    @Test
    void sharesAPlaceholderAcrossFieldsAndSpellingVariantsWhenSameWasReported() {
        String title = "😀김철수 담당자";
        String body = "Kim님이 새 링크로 로그인했습니다. 다음 날 김철수가 확인했습니다.";
        int last = body.codePointCount(0, body.indexOf("김철수"));
        String spans = person("body", last, last + 3, 3) + "," + person("title", 1, 4, 1)
                + "," + person("body", 0, 3, 2);

        var result = masker.validateAndMask(title, body, envelope(spans,
                pair(1, 2, Relation.SAME) + "," + pair(1, 3, Relation.SAME) + "," + pair(2, 3, Relation.SAME)));

        assertThat(result.title()).isEqualTo("😀[PERSON_1] 담당자");
        assertThat(result.body()).isEqualTo("[PERSON_1]님이 새 링크로 로그인했습니다. 다음 날 [PERSON_1]가 확인했습니다.");
        assertThat(result.personRelations()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Relation.class, names = {"DIFFERENT", "UNDETERMINED"})
    void compressesTwoSameComponentsWithoutLosingTheirExplicitRelation(Relation between) {
        String spans = person("body", 0, 1, 1) + "," + person("body", 1, 2, 2) + ","
                + person("body", 2, 3, 3) + "," + person("body", 3, 4, 4);
        String relations = pair(1, 2, between) + "," + pair(1, 3, Relation.SAME) + ","
                + pair(1, 4, between) + "," + pair(2, 3, between) + ","
                + pair(2, 4, Relation.SAME) + "," + pair(3, 4, between);

        var result = masker.validateAndMask("문의", "ABCD", envelope(spans, relations));

        assertThat(result.body()).isEqualTo("[PERSON_1][PERSON_2][PERSON_1][PERSON_2]");
        assertThat(result.personRelations()).containsExactly(
                new MaskedPersonRelation("[PERSON_1]", "[PERSON_2]", between));
    }

    @Test
    void numbersComponentsByFirstOccurrenceAndDoesNotMakeDifferentTransitive() {
        String spans = person("body", 0, 1, 1) + "," + person("body", 1, 2, 2) + ","
                + person("body", 2, 3, 3);
        var result = masker.validateAndMask("문의", "ABC", envelope(spans,
                pair(1, 2, Relation.DIFFERENT) + "," + pair(1, 3, Relation.UNDETERMINED) + ","
                        + pair(2, 3, Relation.DIFFERENT)));

        assertThat(result.personRelations()).containsExactly(
                new MaskedPersonRelation("[PERSON_1]", "[PERSON_2]", Relation.DIFFERENT),
                new MaskedPersonRelation("[PERSON_1]", "[PERSON_3]", Relation.UNDETERMINED),
                new MaskedPersonRelation("[PERSON_2]", "[PERSON_3]", Relation.DIFFERENT));
    }

    @Test
    void keepsNumericOrderForMoreThanNineComponents() {
        List<String> spans = new ArrayList<>();
        List<String> relations = new ArrayList<>();
        for (int index = 0; index < 12; index++) spans.add(person("body", index, index + 1, index + 1));
        for (int left = 1; left <= 12; left++) {
            for (int right = left + 1; right <= 12; right++) relations.add(pair(right, left, Relation.DIFFERENT));
        }

        var result = masker.validateAndMask("문의", "ABCDEFGHIJKL",
                envelope(String.join(",", spans), String.join(",", relations)));

        assertThat(result.personRelations()).hasSize(66);
        assertThat(result.personRelations().get(0).right()).isEqualTo("[PERSON_2]");
        assertThat(result.personRelations().get(8).right()).isEqualTo("[PERSON_10]");
        assertThat(result.body()).endsWith("[PERSON_10][PERSON_11][PERSON_12]");
    }

    @ParameterizedTest
    @EnumSource(value = SensitiveType.class, names = "PERSON", mode = EnumSource.Mode.EXCLUDE)
    void preservesExistingNonPersonPlaceholdersAndCodePointPositions(SensitiveType type) {
        String span = "{\"field\":\"body\",\"start\":1,\"end\":4,\"type\":\"" + type + "\"}";
        var result = masker.validateAndMask("문의", "😀합성값님 끝", envelope(span, ""));

        assertThat(result.body()).isEqualTo("😀" + type.placeholder() + "님 끝");
        assertThat(result.personRelations()).isEmpty();
    }

    @Test
    void handlesAdjacentNonPersonAndPersonSpansWithExactDuplicates() {
        String person = person("body", 1, 4, 1);
        String email = "{\"field\":\"body\",\"start\":4,\"end\":6,\"type\":\"EMAIL\"}";

        var result = masker.validateAndMask("문의", "😀김철수메일 끝", envelope(email + "," + person + "," + person, ""));

        assertThat(result.body()).isEqualTo("😀[PERSON_1][EMAIL_REDACTED] 끝");
    }

    @Test
    void usesNoCrossCallIdentityOrNameMapping() {
        String spans = person("body", 0, 3, 1) + "," + person("body", 4, 7, 2);
        var first = masker.validateAndMask("문의", "김철수 김영희", envelope(spans, pair(1, 2, Relation.SAME)));
        var second = masker.validateAndMask("문의", "박민수 이민수", envelope(spans, pair(1, 2, Relation.UNDETERMINED)));
        var again = masker.validateAndMask("문의", "김철수 김영희", envelope(spans, pair(1, 2, Relation.DIFFERENT)));

        assertThat(first.body()).isEqualTo("[PERSON_1] [PERSON_1]");
        assertThat(second.body()).isEqualTo("[PERSON_1] [PERSON_2]");
        assertThat(second.personRelations().getFirst().relation()).isEqualTo(Relation.UNDETERMINED);
        assertThat(again.personRelations().getFirst().relation()).isEqualTo(Relation.DIFFERENT);
    }

    @ParameterizedTest
    @MethodSource("reservedInputs")
    void rejectsSourceLiteralsInTheReservedNamespaceInsteadOfTrustingOrRewritingThem(String title, String body) {
        assertThatThrownBy(() -> masker.validateAndMask(title, body, envelope("", "")))
                .isExactlyInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause();
    }

    static Stream<Arguments> reservedInputs() {
        return Stream.of(
                Arguments.of("[PERSON_1]", "문의"),
                Arguments.of("문의", "[PERSON_1] 담당자"),
                Arguments.of("문의", "[PERSON_999]"),
                Arguments.of("문의", "[PERSON_REDACTED]"),
                Arguments.of("문의", "문자 그대로 [PERSON_ 라고 썼습니다."));
    }

    @Test
    void rejectsReservedLiteralsEvenInsideAReportedSpan() {
        String body = "[PERSON_1]";
        assertThatThrownBy(() -> masker.validateAndMask("문의", body,
                envelope(person("body", 0, body.length(), 1), "")))
                .isExactlyInstanceOf(InvalidScanResultException.class);
    }

    @Test
    void leavesUnreservedSourceTextAsOrdinaryText() {
        var result = masker.validateAndMask("[person_1]", "[PERSON]와 [EMAIL_REDACTED]", envelope("", ""));
        assertThat(result.title()).isEqualTo("[person_1]");
        assertThat(result.body()).isEqualTo("[PERSON]와 [EMAIL_REDACTED]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "가나다", "😀a💡", "é水\t\n", "\uD800a\uDC00"})
    void boundsCombinedUtf8TextAtTheExactByteLimit(String body) {
        int bytes = "제목".getBytes(StandardCharsets.UTF_8).length + body.getBytes(StandardCharsets.UTF_8).length;

        var exact = new AiPrivacyScanResultV2Masker(validator, bytes);
        assertThat(exact.validateAndMask("제목", body, envelope("", "")).body()).isEqualTo(body);

        var shortLimit = new AiPrivacyScanResultV2Masker(validator, bytes - 1);
        assertThatThrownBy(() -> shortLimit.validateAndMask("제목", body, envelope("", "")))
                .isExactlyInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause();
    }

    @Test
    void countsExpandedReplacementBytesButNotTheRemovedOriginal() {
        String raw = envelope(person("body", 0, 1, 1), "");
        int expectedBytes = "t[PERSON_1]".getBytes(StandardCharsets.UTF_8).length;

        assertThat(new AiPrivacyScanResultV2Masker(validator, expectedBytes)
                .validateAndMask("t", "김", raw).body()).isEqualTo("[PERSON_1]");
        assertThatThrownBy(() -> new AiPrivacyScanResultV2Masker(validator, expectedBytes - 1)
                .validateAndMask("t", "김", raw)).isExactlyInstanceOf(InvalidScanResultException.class);

        String longBody = "김".repeat(100);
        assertThat(new AiPrivacyScanResultV2Masker(validator, expectedBytes)
                .validateAndMask("t", longBody, envelope(person("body", 0, 100, 1), "")).body())
                .isEqualTo("[PERSON_1]");
    }

    @Test
    void validatesBothFieldsAndTheCompleteRelationTableBeforeReturningAnyCopy() {
        String validTitle = person("title", 0, 3, 1);
        String outOfRangeBody = person("body", 0, 4, 2);
        assertThatThrownBy(() -> masker.validateAndMask("김철수", "김영희",
                envelope(validTitle + "," + outOfRangeBody, pair(1, 2, Relation.DIFFERENT))))
                .isExactlyInstanceOf(InvalidScanResultException.class).hasNoCause();

        String spans = person("body", 0, 1, 1) + "," + person("body", 1, 2, 2) + ","
                + person("body", 2, 3, 3);
        assertThatThrownBy(() -> masker.validateAndMask("문의", "ABC", envelope(spans,
                pair(1, 2, Relation.SAME) + "," + pair(2, 3, Relation.SAME) + "," + pair(1, 3, Relation.DIFFERENT))))
                .isExactlyInstanceOf(InvalidScanResultException.class).hasNoCause();
    }

    @Test
    void refusesMissingRelationsAndV1InsteadOfFillingOrUpgradingThem() {
        String spans = person("body", 0, 3, 1) + "," + person("body", 4, 7, 2);
        assertThatThrownBy(() -> masker.validateAndMask("문의", "김철수 김영희", envelope(spans, "")))
                .isExactlyInstanceOf(InvalidScanResultException.class);
        assertThatThrownBy(() -> masker.validateAndMask("문의", "원문", "{\"completed\":true,\"spans\":[]}"))
                .isExactlyInstanceOf(InvalidScanResultException.class);
    }

    @Test
    void returnsOnlyMaskedTextAndImmutableGeneratedRelationsWithRedactedStringRepresentations() {
        var result = masker.validateAndMask("김철수", "김영희", envelope(
                person("title", 0, 3, 1) + "," + person("body", 0, 3, 2), pair(1, 2, Relation.UNDETERMINED)));

        assertThat(result.title()).isEqualTo("[PERSON_1]");
        assertThat(result.body()).isEqualTo("[PERSON_2]");
        assertThat(result.toString()).isEqualTo("MaskedPrivacyScanV2[content omitted]");
        assertThat(result.personRelations().getFirst().toString()).isEqualTo("MaskedPrivacyRelationV2[content omitted]");
        assertThatThrownBy(() -> result.personRelations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void requiresAnExplicitPositiveTextByteLimit(int limit) {
        assertThatThrownBy(() -> new AiPrivacyScanResultV2Masker(validator, limit))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
    }

    @Test
    void requiresTheV2Validator() {
        assertThatThrownBy(() -> new AiPrivacyScanResultV2Masker(null, 100))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
    }

    private static String person(String field, int start, int end, int mention) {
        return "{\"field\":\"" + field + "\",\"start\":" + start + ",\"end\":" + end
                + ",\"type\":\"PERSON\",\"mentionId\":\"m" + mention + "\"}";
    }

    private static String pair(int left, int right, Relation relation) {
        return "{\"left\":\"m" + left + "\",\"right\":\"m" + right + "\",\"relation\":\"" + relation + "\"}";
    }

    private static String envelope(String spans, String relations) {
        return "{\"schemaVersion\":2,\"completed\":true,\"spans\":[" + spans + "],\"personRelations\":[" + relations + "]}";
    }
}
