package lab.helpdesk.ai.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Category;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Decision;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.InvalidOutputException;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Priority;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.Violation;
import tools.jackson.databind.json.JsonMapper;

class AiSuggestionOutputValidatorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final AiSuggestionOutputValidator validator = new AiSuggestionOutputValidator(200);

    @Test
    void acceptsSuggestionWithoutReplacingUnknownValuesOrChangingTheSummary() {
        Map<String, Object> output = suggestion();
        output.put("summary", "  로그인 실패와 설명이 부족한 별도 문제를 문의함  ");
        output.put("categories", List.of("ACCOUNT", "UNDETERMINED"));
        output.put("priority", "UNDETERMINED");

        var result = validator.validate(json(output));

        assertThat(result.decision()).isEqualTo(Decision.SUGGEST);
        assertThat(result.summary()).isEqualTo(output.get("summary"));
        assertThat(result.categories()).containsExactly(Category.ACCOUNT, Category.UNDETERMINED);
        assertThat(result.priority()).isEqualTo(Priority.UNDETERMINED);
        assertThatThrownBy(() -> result.categories().add(Category.OTHER))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(Category.class)
    void acceptsEachAllowedCategory(Category category) {
        Map<String, Object> output = suggestion();
        output.put("categories", List.of(category.name()));
        assertThat(validator.validate(json(output)).categories()).containsExactly(category);
    }

    @ParameterizedTest
    @EnumSource(Priority.class)
    void acceptsEachAllowedPriority(Priority priority) {
        Map<String, Object> output = suggestion();
        output.put("priority", priority.name());
        assertThat(validator.validate(json(output)).priority()).isEqualTo(priority);
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision", "summary", "categories", "priority"})
    void rejectsEachMissingFieldInsteadOfFillingIt(String field) {
        Map<String, Object> output = suggestion();
        output.remove(field);
        assertInvalid(json(output), Violation.FIELD_SET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ticketId", "role", "category"})
    void rejectsExtraFields(String field) {
        Map<String, Object> output = suggestion();
        output.put(field, "synthetic-value");
        assertInvalid(json(output), Violation.FIELD_SET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "1", "true", "\"text\""})
    void rejectsNonObjectRoots(String rawOutput) {
        assertInvalid(rawOutput, Violation.ROOT_SHAPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{", "{} {}", "{} trailing", "{\"summary\":1,\"summary\":2}"})
    void rejectsInvalidJsonTrailingContentAndDuplicateProperties(String rawOutput) {
        assertInvalid(rawOutput, Violation.JSON_SYNTAX);
    }

    @Test
    void rejectsNullInputWithoutRetainingTheOriginalParserError() {
        assertInvalid(null, Violation.JSON_SYNTAX);
        assertInvalid("{\"synthetic-private-marker\":", Violation.JSON_SYNTAX);
    }

    @ParameterizedTest
    @MethodSource("invalidFieldValues")
    void rejectsWrongTypesAndValues(String field, Object value, Violation violation) {
        Map<String, Object> output = suggestion();
        output.put(field, value);
        assertInvalid(json(output), violation);
    }

    static Stream<Arguments> invalidFieldValues() {
        return Stream.of(
                Arguments.of("decision", null, Violation.DECISION),
                Arguments.of("decision", 1, Violation.DECISION),
                Arguments.of("decision", "suggest", Violation.DECISION),
                Arguments.of("decision", "REFUSAL", Violation.DECISION),
                Arguments.of("summary", null, Violation.SUMMARY),
                Arguments.of("summary", 1, Violation.SUMMARY),
                Arguments.of("summary", "", Violation.SUMMARY),
                Arguments.of("summary", " \t\n ", Violation.SUMMARY),
                Arguments.of("summary", "\u00a0\u3000\ufeff", Violation.SUMMARY),
                Arguments.of("summary", "가".repeat(201), Violation.SUMMARY),
                Arguments.of("categories", null, Violation.CATEGORIES),
                Arguments.of("categories", "ACCOUNT", Violation.CATEGORIES),
                Arguments.of("categories", List.of(), Violation.CATEGORIES),
                Arguments.of("categories", List.of("ACCOUNT", "ACCOUNT"), Violation.CATEGORIES),
                Arguments.of("categories", List.of("UNKNOWN"), Violation.CATEGORIES),
                Arguments.of("categories", List.of("account"), Violation.CATEGORIES),
                Arguments.of("categories", List.of(1), Violation.CATEGORIES),
                Arguments.of("categories", java.util.Arrays.asList((Object) null), Violation.CATEGORIES),
                Arguments.of("priority", null, Violation.PRIORITY),
                Arguments.of("priority", 1, Violation.PRIORITY),
                Arguments.of("priority", "URGENT", Violation.PRIORITY),
                Arguments.of("priority", "LOW", Violation.PRIORITY),
                Arguments.of("priority", "normal", Violation.PRIORITY));
    }

    @Test
    void countsUnicodeCodePointsAfterTrimmingButPreservesTheOriginalString() {
        Map<String, Object> output = suggestion();
        String summary = "\u00a0" + "😀".repeat(200) + "\ufeff";
        output.put("summary", summary);
        assertThat(validator.validate(json(output)).summary()).isEqualTo(summary);

        output.put("summary", "😀".repeat(201));
        assertInvalid(json(output), Violation.SUMMARY);
    }

    @Test
    void receivesTheSummaryLimitAsConfigurationInsteadOfFinalizingARuntimeDefault() {
        var limited = new AiSuggestionOutputValidator(2);
        Map<String, Object> output = suggestion();
        output.put("summary", "두글");
        assertThat(limited.validate(json(output)).summary()).isEqualTo("두글");
        output.put("summary", "세글자");
        assertThatThrownBy(() -> limited.validate(json(output)))
                .isInstanceOf(InvalidOutputException.class)
                .hasMessage("AI_OUTPUT_SUMMARY");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidSummaryLimitConfiguration(int limit) {
        assertThatThrownBy(() -> new AiSuggestionOutputValidator(limit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyTheExplicitNullShapeForAbstain() {
        var result = validator.validate(json(abstain()));
        assertThat(result.decision()).isEqualTo(Decision.ABSTAIN);
        assertThat(result.summary()).isNull();
        assertThat(result.categories()).isNull();
        assertThat(result.priority()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"summary", "categories", "priority"})
    void rejectsAbstainWithSuggestionValues(String field) {
        Map<String, Object> output = abstain();
        output.put(field, suggestion().get(field));
        assertInvalid(json(output), Violation.ABSTAIN_SHAPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"summary", "categories", "priority"})
    void requiresFieldsEvenWhenTheirAbstainValuesMustBeNull(String field) {
        Map<String, Object> output = abstain();
        output.remove(field);
        assertInvalid(json(output), Violation.FIELD_SET);
    }

    @Test
    void doesNotClaimToDetectFalseFacts() {
        Map<String, Object> output = suggestion();
        output.put("summary", "원문에 없는 계정 탈취가 발생했다고 주장함");
        assertThat(validator.validate(json(output)).decision()).isEqualTo(Decision.SUGGEST);
    }

    @Test
    void preservesMarkupAsTextAndLeavesRenderingToTheUi() {
        Map<String, Object> output = suggestion();
        output.put("summary", "<strong>긴급</strong>");
        assertThat(validator.validate(json(output)).summary()).isEqualTo("<strong>긴급</strong>");
    }

    private void assertInvalid(String rawOutput, Violation expected) {
        assertThatThrownBy(() -> validator.validate(rawOutput))
                .isInstanceOfSatisfying(InvalidOutputException.class, exception -> {
                    assertThat(exception.violation()).isEqualTo(expected);
                    assertThat(exception.getMessage()).isEqualTo("AI_OUTPUT_" + expected.name());
                    assertThat(exception.getCause()).isNull();
                });
    }

    private static Map<String, Object> suggestion() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("decision", "SUGGEST");
        output.put("summary", "로그인 링크 만료 이유를 문의함");
        output.put("categories", List.of("ACCOUNT"));
        output.put("priority", "NORMAL");
        return output;
    }

    private static Map<String, Object> abstain() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("decision", "ABSTAIN");
        output.put("summary", null);
        output.put("categories", null);
        output.put("priority", null);
        return output;
    }

    private static String json(Map<String, Object> output) {
        return MAPPER.writeValueAsString(output);
    }
}
