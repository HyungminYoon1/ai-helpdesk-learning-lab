package lab.helpdesk.ai.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator.InvalidScanResultException;

class AiPrivacyScanResultValidatorTest {
    private final AiPrivacyScanResultValidator validator = new AiPrivacyScanResultValidator(4_096, 16);

    @Test
    void acceptsACompletedEmptyResultWithoutChangingEitherOriginal() {
        String title = "  문의 😀  ";
        String body = "로그인 복구 완료\n<strong>문의</strong>\t";

        var result = validator.validateAndMask(title, body, "{\"completed\":true,\"spans\":[]}");

        assertThat(result.title()).isEqualTo(title);
        assertThat(result.body()).isEqualTo(body);
        assertThat(result.toString()).isEqualTo("MaskedInput[content omitted]");
    }

    @Test
    void masksTheExampleAndPreservesTheOriginalAndRestoredLoginFact() {
        String body = "메일 a@b.invalid, 로그인 성공";

        var result = validator.validateAndMask("문의", body,
                completed(span("body", "3", "14", "EMAIL")));

        assertThat(result.body()).isEqualTo("메일 [EMAIL_REDACTED], 로그인 성공");
        assertThat(body).isEqualTo("메일 a@b.invalid, 로그인 성공");
    }

    @Test
    void usesCodePointPositionsInBothFieldsWithoutTrimmingOriginals() {
        var result = validator.validateAndMask("😀메일 끝", "  😀MAIL; 복구 완료\n",
                completed(span("body", "3", "7", "EMAIL") + ","
                        + span("title", "1", "3", "CONTACT")));

        assertThat(result.title()).isEqualTo("😀[CONTACT_REDACTED] 끝");
        assertThat(result.body()).isEqualTo("  😀[EMAIL_REDACTED]; 복구 완료\n");
    }

    @ParameterizedTest
    @EnumSource(SensitiveType.class)
    void acceptsOnlyTheExistingConfirmedTypeLabels(SensitiveType type) {
        var result = validator.validateAndMask("문의", "VALUE",
                completed(span("body", "0", "5", type.name())));

        assertThat(result.body()).isEqualTo(type.placeholder());
    }

    @Test
    void deduplicatesOnlyExactMatchesAndAcceptsAdjacentRanges() {
        String first = span("body", "0", "1", "EMAIL");
        String second = span("body", "1", "2", "PHONE");

        var result = validator.validateAndMask("문의", "AB",
                completed(second + "," + first + "," + first));

        assertThat(result.body()).isEqualTo("[EMAIL_REDACTED][PHONE_REDACTED]");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    @MethodSource("invalidEnvelopes")
    void rejectsIncompleteOrMalformedEnvelopesWithoutAPayloadOrCause(String raw) {
        assertInvalid(raw);
    }

    @ParameterizedTest
    @MethodSource("invalidSpans")
    void rejectsTheWholeResultWhenAnySpanViolatesTheContract(String invalidSpan) {
        // A valid first span must not cause a partial result to be returned.
        assertInvalid(completed(span("title", "0", "1", "EMAIL") + "," + invalidSpan));
    }

    @Test
    void rejectsOversizedNonAsciiOutputWithTheFixedError() {
        String raw = "{\"completed\":true,\"spans\":[]}";
        int limit = raw.getBytes(StandardCharsets.UTF_8).length + 1;
        var limited = new AiPrivacyScanResultValidator(limit, 1);
        // U+2003 is three UTF-8 bytes but one Java char; keep it inside a JSON
        // string so a byte bound, not invalid outer JSON whitespace, rejects it.
        String unicode = raw.replace("completed", "complet\u2003d");

        assertThat(unicode.length()).isLessThanOrEqualTo(limit);
        assertThat(unicode.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(limit);
        assertThatThrownBy(() -> limited.validateAndMask("문의", "본문", unicode))
                .isInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause();
    }

    @Test
    void acceptsExactlyTheConfiguredByteAndEntryLimits() {
        String raw = completed(span("body", "0", "1", "CONTACT"));
        var limited = new AiPrivacyScanResultValidator(raw.getBytes(StandardCharsets.UTF_8).length, 1);

        assertThat(limited.validateAndMask("문의", "AB", raw).body())
                .isEqualTo("[CONTACT_REDACTED]B");
        assertThatThrownBy(() -> limited.validateAndMask("문의", "AB", raw + " "))
                .isInstanceOf(InvalidScanResultException.class).hasNoCause();
        String two = completed(span("body", "0", "1", "CONTACT") + ","
                + span("body", "1", "2", "CONTACT"));
        var oneEntryOnly = new AiPrivacyScanResultValidator(4_096, 1);
        assertThatThrownBy(() -> oneEntryOnly.validateAndMask("문의", "AB", two))
                .isInstanceOf(InvalidScanResultException.class).hasNoCause();
    }

    @Test
    void boundsEntriesBeforeDeduplication() {
        String item = span("body", "0", "1", "EMAIL");
        var limited = new AiPrivacyScanResultValidator(4_096, 1);

        assertThatThrownBy(() -> limited.validateAndMask("문의", "AB", completed(item + "," + item)))
                .isInstanceOf(InvalidScanResultException.class).hasNoCause();
    }

    @Test
    void rejectsConflictingTypesAndOverlappingRangesAfterJsonValidation() {
        String first = span("body", "0", "3", "EMAIL");
        assertInvalid(completed(first + "," + span("body", "0", "3", "PHONE")));
        assertInvalid(completed(first + "," + span("body", "2", "5", "EMAIL")));
        assertInvalid(completed(first + "," + span("body", "1", "2", "EMAIL")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsMissingPositiveLimits(int invalid) {
        assertThatThrownBy(() -> new AiPrivacyScanResultValidator(invalid, 1))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID").hasNoCause();
        assertThatThrownBy(() -> new AiPrivacyScanResultValidator(1, invalid))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID").hasNoCause();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void preservesTheExistingMissingInputError(String missing) {
        String raw = "{\"completed\":true,\"spans\":[]}";
        assertThatThrownBy(() -> validator.validateAndMask(missing, "본문", raw))
                .hasMessage("AI_INPUT_REQUIRED").hasNoCause();
        assertThatThrownBy(() -> validator.validateAndMask("제목", missing, raw))
                .hasMessage("AI_INPUT_REQUIRED").hasNoCause();
    }

    @Test
    void doesNotRetainParserMessagesOrUntrustedText() {
        String marker = "SYNTHETIC_SCANNER_TEXT_NOT_FOR_LOGS";
        String raw = "{\"completed\":true,\"spans\":[" + marker + "]}";

        assertThatThrownBy(() -> validator.validateAndMask(marker, marker, raw))
                .isInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT")
                .hasNoCause()
                .satisfies(error -> assertThat(error.toString()).doesNotContain(marker));
    }

    private void assertInvalid(String raw) {
        assertThatThrownBy(() -> validator.validateAndMask("제목", "ABCDEF", raw))
                .isInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause();
    }

    private static String completed(String spans) {
        return "{\"completed\":true,\"spans\":[" + spans + "]}";
    }

    private static String span(String field, String start, String end, String type) {
        return "{\"field\":\"" + field + "\",\"start\":" + start
                + ",\"end\":" + end + ",\"type\":\"" + type + "\"}";
    }

    private static Stream<String> invalidEnvelopes() {
        return Stream.of(
                "null", "[]", "1", "true", "\"value\"", "{}",
                "{\"completed\":true}", "{\"spans\":[]}",
                "{\"completed\":false,\"spans\":[]}",
                "{\"completed\":\"true\",\"spans\":[]}",
                "{\"completed\":1,\"spans\":[]}",
                "{\"completed\":null,\"spans\":[]}",
                "{\"completed\":true,\"spans\":null}",
                "{\"completed\":true,\"spans\":{}}",
                "{\"completed\":true,\"spans\":[],\"rawText\":\"UNTRUSTED\"}",
                "{\"completed\":false,\"completed\":true,\"spans\":[]}",
                "{\"completed\":true,\"spans\":[],\"spans\":[]}",
                "{\"completed\":true,\"spans\":[]} {}",
                "{\"completed\":true,\"spans\":[],}",
                "{\"completed\":true,/*comment*/\"spans\":[]}",
                "```json\n{\"completed\":true,\"spans\":[]}\n```",
                "{\"completed\":true,\"spans\":[[[[[[]]]]]]}",
                "{\"completed\":true,\"spans\":[");
    }

    private static Stream<String> invalidSpans() {
        return Stream.concat(Stream.of(
                "null", "[]", "1", "{}",
                "{\"field\":\"body\",\"start\":0,\"end\":1}",
                "{\"start\":0,\"end\":1,\"type\":\"EMAIL\"}",
                "{\"field\":\"body\",\"end\":1,\"type\":\"EMAIL\"}",
                "{\"field\":\"body\",\"start\":0,\"type\":\"EMAIL\"}",
                "{\"field\":\"body\",\"start\":0,\"end\":1,\"type\":\"EMAIL\",\"text\":\"NO\"}",
                "{\"field\":\"body\",\"start\":0,\"start\":1,\"end\":2,\"type\":\"EMAIL\"}",
                "{\"field\":null,\"start\":0,\"end\":1,\"type\":\"EMAIL\"}",
                "{\"field\":\"body\",\"start\":0,\"end\":1,\"type\":null}",
                span("BODY", "0", "1", "EMAIL"), span("message", "0", "1", "EMAIL"),
                span("body", "0", "1", "email"), span("body", "0", "1", "OTHER"),
                span("body", "-1", "1", "EMAIL"), span("body", "0", "0", "EMAIL"),
                span("body", "2", "1", "EMAIL"), span("body", "0", "7", "EMAIL"),
                span("title", "0", "3", "EMAIL")),
                Stream.of("\"0\"", "0.0", "0e0", "true", "null", "2147483648", "-2147483649")
                        .flatMap(number -> Stream.of(span("body", number, "1", "EMAIL"),
                                span("body", "0", number, "EMAIL"))));
    }
}
