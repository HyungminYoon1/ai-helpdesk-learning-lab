package lab.helpdesk.ai.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiInputSpanMasker.InputField;
import lab.helpdesk.ai.input.AiPrivacyScanResultV2Validator.Relation;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator.InvalidScanResultException;

class AiPrivacyScanResultV2ValidatorTest {
    private static final String BODY = "ABCDEFGHIJKL";
    private final AiPrivacyScanResultV2Validator validator = new AiPrivacyScanResultV2Validator(16_384, 16, 128);

    @Test
    void acceptsZeroOrOnePersonWithExplicitEmptyRelations() {
        assertThat(validator.validate("제목", BODY, envelope("", "")).spans()).isEmpty();
        var one = validator.validate("제목", BODY, envelope(people(1), ""));
        assertThat(one.spans()).hasSize(1);
        assertThat(one.personRelations()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Relation.class)
    void preservesTheExplicitRelationIncludingUndetermined(Relation relation) {
        var result = validator.validate("제목", BODY, envelope(people(2), relation(1, 2, relation)));

        assertThat(result.spans()).extracting(span -> span.mentionId()).containsExactly("m1", "m2");
        assertThat(result.personRelations()).hasSize(1);
        assertThat(result.personRelations().getFirst().relation()).isEqualTo(relation);
    }

    @Test
    void neverGuessesSameIdentityFromEqualNamesOrCarriesIdentityAcrossCalls() {
        String original = "김철수와 동명이인 김철수";
        String spans = person("body", 0, 3, "m1") + "," + person("body", 10, 13, "m2");
        var different = validator.validate("문의", original, envelope(spans, relation(1, 2, Relation.DIFFERENT)));
        var anotherCall = validator.validate("문의", original,
                envelope(spans, relation(1, 2, Relation.UNDETERMINED)));

        assertThat(different.personRelations().getFirst().relation()).isEqualTo(Relation.DIFFERENT);
        assertThat(anotherCall.personRelations().getFirst().relation()).isEqualTo(Relation.UNDETERMINED);
        assertThat(anotherCall).isNotSameAs(different);
        assertThat(original).isEqualTo("김철수와 동명이인 김철수");
    }

    @Test
    void acceptsStructurallyConsistentSameWithoutClaimingSourceTruth() {
        // The original explicitly says different people. A structural validator
        // cannot replace model content evaluation or prove this SAME is true.
        var result = validator.validate("문의", "김철수와 동명이인 김철수", envelope(
                person("body", 0, 3, "m1") + "," + person("body", 10, 13, "m2"),
                relation(1, 2, Relation.SAME)));

        assertThat(result.personRelations().getFirst().relation()).isEqualTo(Relation.SAME);
    }

    @Test
    void requiresRelationsInsteadOfFillingMissingPairsWithUndetermined() {
        assertInvalid(envelope(people(2), ""));
        assertInvalid(envelope(people(2), relation(1, 2, Relation.UNDETERMINED))
                .replace(",\"personRelations\":[" + relation(1, 2, Relation.UNDETERMINED) + "]", ""));
    }

    @Test
    void normalizesPairDirectionAndNumericOrderWithoutChangingTheReportedJudgment() {
        String relations = relation(3, 2, Relation.DIFFERENT) + ","
                + relation(3, 1, Relation.UNDETERMINED) + "," + relation(2, 1, Relation.UNDETERMINED);
        var result = validator.validate("제목", BODY, envelope(people(3), relations));

        assertThat(result.personRelations()).extracting(r -> r.left() + ":" + r.right())
                .containsExactly("m1:m2", "m1:m3", "m2:m3");
        assertThat(result.personRelations()).extracting(r -> r.relation())
                .containsExactly(Relation.UNDETERMINED, Relation.UNDETERMINED, Relation.DIFFERENT);
    }

    @ParameterizedTest
    @MethodSource("threePersonTables")
    void checksAllTwentySevenThreePersonTables(Relation ab, Relation ac, Relation bc, boolean valid) {
        String raw = envelope(people(3), relation(1, 2, ab) + "," + relation(1, 3, ac) + ","
                + relation(2, 3, bc));
        if (valid) {
            assertThat(validator.validate("제목", BODY, raw).personRelations()).hasSize(3);
        } else {
            assertInvalid(raw);
        }
    }

    @Test
    void differentIsNotTransitiveAndDoesNotEraseExplicitUncertainty() {
        var result = validator.validate("제목", BODY, envelope(people(3),
                relation(1, 2, Relation.DIFFERENT) + "," + relation(1, 3, Relation.UNDETERMINED)
                        + "," + relation(2, 3, Relation.DIFFERENT)));

        assertThat(result.personRelations().get(1).relation()).isEqualTo(Relation.UNDETERMINED);
    }

    @ParameterizedTest
    @EnumSource(value = Relation.class, names = {"DIFFERENT", "UNDETERMINED"})
    void validatesRelationsBetweenTwoSamePersonComponentsAfterAllUnions(Relation cross) {
        String rows = relation(2, 4, cross) + "," + relation(1, 3, cross) + ","
                + relation(2, 3, cross) + "," + relation(1, 4, cross) + ","
                + relation(4, 3, Relation.SAME) + "," + relation(2, 1, Relation.SAME);
        var result = validator.validate("제목", BODY, envelope(people(4), rows));

        assertThat(result.personRelations()).hasSize(6);
        assertThat(result.personRelations().stream().filter(r -> r.relation() == cross)).hasSize(4);
    }

    @Test
    void rejectsConflictingJudgmentsBetweenTwoSamePersonComponents() {
        String rows = relation(1, 2, Relation.SAME) + "," + relation(3, 4, Relation.SAME) + ","
                + relation(1, 3, Relation.DIFFERENT) + "," + relation(1, 4, Relation.DIFFERENT) + ","
                + relation(2, 3, Relation.DIFFERENT) + "," + relation(2, 4, Relation.UNDETERMINED);
        assertInvalid(envelope(people(4), rows));
    }

    @Test
    void usesCodePointBoundsAndCanonicalTitleThenBodyMentionNumbers() {
        var result = validator.validate("😀갑", "  😀을\n", envelope(
                person("body", 3, 4, "m2") + "," + person("title", 1, 2, "m1"),
                relation(1, 2, Relation.UNDETERMINED)));

        assertThat(result.spans()).extracting(s -> s.field()).containsExactly(InputField.TITLE, InputField.BODY);
        assertThat(result.spans()).extracting(s -> s.startCodePoint()).containsExactly(1, 3);
        assertThat(result.spans()).extracting(s -> s.mentionId()).containsExactly("m1", "m2");
    }

    @Test
    void ordersTwelveMentionsNumericallyRatherThanLexically() {
        var result = validator.validate("제목", BODY, envelope(people(12), allUndetermined(12)));

        assertThat(result.personRelations()).hasSize(66);
        assertThat(result.personRelations().getLast().left()).isEqualTo("m11");
        assertThat(result.personRelations().getLast().right()).isEqualTo("m12");
        assertThat(result.spans()).extracting(s -> s.mentionId())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 12).mapToObj(i -> "m" + i).toList());
    }

    @ParameterizedTest
    @EnumSource(value = SensitiveType.class, names = "PERSON", mode = EnumSource.Mode.EXCLUDE)
    void keepsNonPersonTypesAtTheExistingFourFields(SensitiveType type) {
        var result = validator.validate("제목", BODY, envelope(span("body", "0", "1", type.name()), ""));

        assertThat(result.spans().getFirst().type()).isEqualTo(type);
        assertThat(result.spans().getFirst().mentionId()).isNull();
    }

    @Test
    void deduplicatesOnlyFullyEqualSpansBeforeIndexingAndAcceptsAdjacentSpans() {
        String first = person("body", 0, 1, "m1");
        String second = person("body", 1, 2, "m2");
        var result = validator.validate("제목", BODY, envelope(second + "," + first + "," + first,
                relation(1, 2, Relation.SAME)));

        assertThat(result.spans()).hasSize(2);
        assertThat(result.personRelations()).hasSize(1);
    }

    @Test
    void rejectsCompetingIdsOnOneOccurrenceAndOneIdReusedAcrossOccurrences() {
        assertInvalid(envelope(person("body", 0, 1, "m1") + "," + person("body", 0, 1, "m2"),
                relation(1, 2, Relation.UNDETERMINED)));
        assertInvalid(envelope(person("body", 0, 1, "m1") + "," + person("body", 1, 2, "m1"), ""));
    }

    @Test
    void rejectsOverlapAndConflictingTypesWithoutProducingAPartialResult() {
        assertInvalid(envelope(person("body", 0, 3, "m1") + "," + span("body", "2", "4", "EMAIL"), ""));
        assertInvalid(envelope(person("body", 0, 3, "m1") + "," + span("body", "0", "3", "PHONE"), ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "m0", "m01", "M1", "m2", "1", "user-1", "김철수", "m2147483648"})
    void requiresCanonicalOccurrenceIdsNotUserProvidedIdentity(String mentionId) {
        assertInvalid(envelope(person("body", 0, 1, mentionId), ""));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    @MethodSource("invalidEnvelopes")
    void rejectsMalformedIncompleteOrWrongVersionEnvelopes(String raw) {
        assertInvalid(raw);
    }

    @ParameterizedTest
    @MethodSource("invalidSpans")
    void rejectsEveryInvalidSpanWithoutSilentlyKeepingTheValidFirstOne(String bad) {
        assertInvalid(envelope(span("title", "0", "1", "EMAIL") + "," + bad, ""));
    }

    @ParameterizedTest
    @MethodSource("invalidRelations")
    void rejectsInvalidReferencesKindsAndFields(String bad) {
        assertInvalid(envelope(people(2), bad));
    }

    @Test
    void rejectsDuplicatePairsInEitherDirectionEvenWhenTheirValuesAgree() {
        String first = relation(1, 2, Relation.UNDETERMINED);
        assertInvalid(envelope(people(3), first + "," + first + "," + relation(1, 3, Relation.UNDETERMINED)));
        assertInvalid(envelope(people(3), first + "," + relation(2, 1, Relation.UNDETERMINED)
                + "," + relation(1, 3, Relation.UNDETERMINED)));
    }

    @Test
    void boundsTheRawArrayBeforeDeduplication() {
        var limited = new AiPrivacyScanResultV2Validator(4_096, 1, 0);
        String one = person("body", 0, 1, "m1");

        assertThatThrownBy(() -> limited.validate("제목", BODY, envelope(one + "," + one, "")))
                .isInstanceOf(InvalidScanResultException.class).hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause();
    }

    @Test
    void enforcesPairCapacityBeforeAllocatingPairDataAndAllowsAnExplicitZeroBudget() {
        var zero = new AiPrivacyScanResultV2Validator(4_096, 3, 0);
        assertThat(zero.validate("제목", BODY, envelope(people(1), "")).personRelations()).isEmpty();
        assertThatThrownBy(() -> zero.validate("제목", BODY, envelope(people(2), "")))
                .isInstanceOf(InvalidScanResultException.class).hasNoCause();
        var two = new AiPrivacyScanResultV2Validator(4_096, 3, 2);
        assertThatThrownBy(() -> two.validate("제목", BODY, envelope(people(3), "")))
                .isInstanceOf(InvalidScanResultException.class).hasNoCause();
        var exact = new AiPrivacyScanResultV2Validator(4_096, 2, 1);
        assertThat(exact.validate("제목", BODY, envelope(people(2), allUndetermined(2))).personRelations()).hasSize(1);
        assertThatThrownBy(() -> two.validate("제목", BODY, envelope(people(3), allUndetermined(3))))
                .isInstanceOf(InvalidScanResultException.class).hasNoCause();
    }

    @Test
    void enforcesExactResponseByteBoundaryAndRejectsMultibyteOverflowSafely() {
        String raw = envelope("", "");
        int bytes = raw.getBytes(StandardCharsets.UTF_8).length;
        var exact = new AiPrivacyScanResultV2Validator(bytes, 1, 0);
        assertThat(exact.validate("제목", BODY, raw).spans()).isEmpty();
        assertThatThrownBy(() -> exact.validate("제목", BODY, raw + " "))
                .isInstanceOf(InvalidScanResultException.class).hasNoCause();
        String unicode = raw.replace("completed", "complet\u2003d");
        assertThat(unicode.length()).isEqualTo(raw.length());
        assertThat(unicode.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(bytes);
        assertThatThrownBy(() -> exact.validate("제목", BODY, unicode))
                .isInstanceOf(InvalidScanResultException.class).hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("invalidLimits")
    void requiresExplicitValidLimits(int bytes, int spans, int pairs) {
        assertThatThrownBy(() -> new AiPrivacyScanResultV2Validator(bytes, spans, pairs))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID").hasNoCause();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void keepsTheExistingMissingOriginalInputError(String missing) {
        assertThatThrownBy(() -> validator.validate(missing, BODY, envelope("", "")))
                .hasMessage("AI_INPUT_REQUIRED").hasNoCause();
        assertThatThrownBy(() -> validator.validate("제목", missing, envelope("", "")))
                .hasMessage("AI_INPUT_REQUIRED").hasNoCause();
    }

    @Test
    void keepsV1AndV2SeparateRatherThanAutomaticallyUpgradingEitherContract() {
        String v1 = "{\"completed\":true,\"spans\":[]}";
        var old = new AiPrivacyScanResultValidator(4_096, 16);
        assertThat(old.validateAndMask("제목", BODY, v1).body()).isEqualTo(BODY);
        assertInvalid(v1);
        assertThatThrownBy(() -> old.validateAndMask("제목", BODY, envelope("", "")))
                .isInstanceOf(InvalidScanResultException.class).hasNoCause();
    }

    @Test
    void returnsImmutableMetadataAndRedactedRepresentationsWithoutOriginalText() {
        var result = validator.validate("제목", BODY, envelope(people(2), allUndetermined(2)));
        assertThatThrownBy(() -> result.spans().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.personRelations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.toString()).isEqualTo("ValidatedPrivacyScanV2[content omitted]");
        assertThat(result.spans().getFirst().toString()).isEqualTo("PrivacyScanV2Span[content omitted]");
        assertThat(result.personRelations().getFirst().toString()).isEqualTo("PrivacyScanV2Relation[references omitted]");
    }

    @Test
    void neverLeaksUntrustedJsonInputOrParserCause() {
        String marker = "SYNTHETIC_TEXT_NOT_FOR_LOGS";
        assertThatThrownBy(() -> validator.validate(marker, marker, "{\"schemaVersion\":" + marker))
                .isInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause()
                .satisfies(error -> assertThat(error.toString()).doesNotContain(marker));
    }

    private void assertInvalid(String raw) {
        assertThatThrownBy(() -> validator.validate("제목", BODY, raw))
                .isInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause();
    }

    private static String envelope(String spans, String relations) {
        return "{\"schemaVersion\":2,\"completed\":true,\"spans\":[" + spans
                + "],\"personRelations\":[" + relations + "]}";
    }

    private static String span(String field, String start, String end, String type) {
        return "{\"field\":\"" + field + "\",\"start\":" + start + ",\"end\":" + end
                + ",\"type\":\"" + type + "\"}";
    }

    private static String person(String field, int start, int end, String id) {
        String base = span(field, Integer.toString(start), Integer.toString(end), "PERSON");
        return base.substring(0, base.length() - 1) + ",\"mentionId\":\"" + id + "\"}";
    }

    private static String relation(int left, int right, Relation kind) {
        return "{\"left\":\"m" + left + "\",\"right\":\"m" + right + "\",\"relation\":\"" + kind + "\"}";
    }

    private static String people(int count) {
        return String.join(",", IntStream.range(0, count).mapToObj(i -> person("body", i, i + 1, "m" + (i + 1)))
                .toList());
    }

    private static String allUndetermined(int count) {
        return String.join(",", IntStream.rangeClosed(1, count).boxed().flatMap(left ->
                IntStream.rangeClosed(left + 1, count).mapToObj(right -> relation(left, right, Relation.UNDETERMINED)))
                .toList());
    }

    private static Stream<Arguments> threePersonTables() {
        return Arrays.stream(Relation.values()).flatMap(ab -> Arrays.stream(Relation.values()).flatMap(ac ->
                Arrays.stream(Relation.values()).map(bc -> {
                    List<Relation> table = List.of(ab, ac, bc);
                    long sameCount = table.stream().filter(r -> r == Relation.SAME).count();
                    boolean valid = sameCount == 0 || sameCount == 3 || (sameCount == 1
                            && table.stream().filter(r -> r != Relation.SAME).distinct().count() == 1);
                    return Arguments.of(ab, ac, bc, valid);
                })));
    }

    private static Stream<Arguments> invalidLimits() {
        return Stream.of(Arguments.of(0, 1, 0), Arguments.of(-1, 1, 0), Arguments.of(1, 0, 0),
                Arguments.of(1, -1, 0), Arguments.of(1, 1, -1));
    }

    private static Stream<String> invalidEnvelopes() {
        String empty = envelope("", "");
        return Stream.concat(Stream.of("null", "[]", "true", "1", "\"text\"", "{}", "{", empty + " {}",
                "```json\n" + empty + "\n```", empty.replace("2,", "2,/*comment*/"),
                empty.replace(",\"completed\":true", ""), empty.replace(",\"spans\":[]", ""),
                empty.replace(",\"personRelations\":[]", ""),
                empty.replace("\"completed\":true", "\"completed\":false"),
                empty.replace("\"completed\":true", "\"completed\":\"true\""),
                empty.replace("\"completed\":true", "\"completed\":null"),
                empty.replace("\"completed\":true", "\"completed\":false,\"completed\":true"),
                empty.replace("\"spans\":[]", "\"spans\":{},\"spans\":[]"),
                empty.replace("\"spans\":[]", "\"spans\":null"),
                empty.replace("\"spans\":[]", "\"spans\":[[[[[[]]]]]]"),
                empty.replace("\"personRelations\":[]", "\"personRelations\":{}"),
                empty.replace("\"personRelations\":[]", "\"personRelations\":null"),
                empty.replace("\"personRelations\":[]", "\"personRelations\":[],\"personRelations\":[]"),
                empty.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"rawText\":\"NO\""),
                empty.replace("\"schemaVersion\":2", "\"schemaVersion\":1,\"schemaVersion\":2")),
                Stream.of("1", "3", "2.0", "2e0", "\"2\"", "true", "null", "2147483648")
                        .map(value -> empty.replace("\"schemaVersion\":2", "\"schemaVersion\":" + value)));
    }

    private static Stream<String> invalidSpans() {
        return Stream.concat(Stream.of("null", "[]", "{}", "1", span("body", "0", "1", "PERSON"),
                person("body", 0, 1, "m1").replace("\"m1\"", "null"),
                person("body", 0, 1, "m1").replace("\"m1\"", "1"),
                person("body", 0, 1, "m1").replace("\"m1\"", "\"m1\",\"mentionId\":\"m1\""),
                person("body", 0, 1, "m1").replace("PERSON", "EMAIL"),
                person("body", 0, 1, "m1").replace("\"m1\"", "\"m1\",\"text\":\"NO\""),
                span("body", "0", "1", "EMAIL").replace("\"field\":\"body\",", ""),
                span("body", "0", "1", "EMAIL").replace("\"start\":0,", ""),
                span("body", "0", "1", "EMAIL").replace("\"end\":1,", ""),
                span("body", "0", "1", "EMAIL").replace(",\"type\":\"EMAIL\"", ""),
                span("body", "0", "1", "EMAIL").replace("\"body\"", "null"),
                span("body", "0", "1", "EMAIL").replace("\"EMAIL\"", "null"),
                span("body", "0", "1", "EMAIL").replace("\"start\":0", "\"start\":0,\"start\":0"),
                span("BODY", "0", "1", "EMAIL"), span("message", "0", "1", "EMAIL"),
                span("body", "0", "1", "email"), span("body", "0", "1", "OTHER"),
                span("body", "-1", "1", "EMAIL"), span("body", "0", "0", "EMAIL"),
                span("body", "3", "1", "EMAIL"), span("body", "0", "13", "EMAIL"),
                span("title", "0", "3", "EMAIL")),
                Stream.of("\"0\"", "0.0", "0e0", "true", "null", "2147483648", "-2147483649")
                        .flatMap(number -> Stream.of(span("body", number, "1", "EMAIL"),
                                span("body", "0", number, "EMAIL"))));
    }

    private static Stream<String> invalidRelations() {
        String base = relation(1, 2, Relation.UNDETERMINED);
        return Stream.of("null", "[]", "{}", "1", base.replace("\"left\":\"m1\",", ""),
                base.replace("\"right\":\"m2\",", ""),
                base.replace(",\"relation\":\"UNDETERMINED\"", ""),
                base.replace("\"m1\"", "null"), base.replace("\"m1\"", "1"),
                base.replace("\"m1\"", "\"m0\""), base.replace("\"m1\"", "\"m3\""),
                base.replace("\"m2\"", "\"m1\""), base.replace("\"m1\"", "\"m01\""),
                base.replace("\"UNDETERMINED\"", "\"UNKNOWN\""),
                base.replace("\"UNDETERMINED\"", "\"same\""),
                base.replace("\"UNDETERMINED\"", "null"), base.replace("\"UNDETERMINED\"", "0"),
                base.replace("\"UNDETERMINED\"", "\"UNDETERMINED\",\"reason\":\"NO\""),
                base.replace("\"left\":\"m1\"", "\"left\":\"m1\",\"left\":\"m1\""));
    }
}
