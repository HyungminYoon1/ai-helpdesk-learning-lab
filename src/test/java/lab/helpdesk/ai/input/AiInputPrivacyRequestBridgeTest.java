package lab.helpdesk.ai.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

class AiInputPrivacyRequestBridgeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void preparesOnlyTheUserCopyAndPreservesTheRequestSettings() {
        String original = request("청구지 변경", "주소 <합성_주소>, 연락처 <합성_연락처>.");
        String checked = AiInputPrivacyRequestBridge.prepareForTransmission(original);
        var root = MAPPER.readTree(checked);
        var input = MAPPER.readTree(root.get("input").get(0).get("content").asString());

        assertThat(input.get("body").asString())
                .isEqualTo("주소 [ADDRESS_REDACTED], 연락처 [CONTACT_REDACTED].");
        assertThat(root.get("model").asString()).isEqualTo("gpt-6-luna");
        assertThat(root.get("instructions").asString()).isEqualTo("문의 요약");
        assertThat(original).contains("<합성_주소>", "<합성_연락처>");
        assertThat(checked).doesNotContain("<합성_주소>", "<합성_연락처>");
    }

    @Test
    void serializesUserContentInTheSameOrderRegardlessOfTheOriginalFieldOrder() {
        var request = MAPPER.readTree(request("문의", "로그인 문제"));
        var message = (tools.jackson.databind.node.ObjectNode) request.get("input").get(0);
        String[] inputOrders = {
                "{\"title\":\"문의\",\"body\":\"로그인 문제\"}",
                "{\"body\":\"로그인 문제\",\"title\":\"문의\"}"
        };
        for (String content : inputOrders) {
            message.put("content", content);
            String checked = AiInputPrivacyRequestBridge.prepareForTransmission(MAPPER.writeValueAsString(request));
            assertThat(MAPPER.readTree(checked).get("input").get(0).get("content").asString())
                    .isEqualTo("{\"title\":\"문의\",\"body\":\"로그인 문제\"}");
        }
    }

    @Test
    void keepsTheRecoveryFactWhenAnEmailIsRedacted() {
        String checked = AiInputPrivacyRequestBridge.prepareForTransmission(request("로그인 문의",
                "새 링크로 로그인에 성공했습니다. 만료 이유를 알려주세요. 이메일 <합성_이메일>."));
        var input = MAPPER.readTree(MAPPER.readTree(checked).get("input").get(0).get("content").asString());

        assertThat(input.get("body").asString()).isEqualTo(
                "새 링크로 로그인에 성공했습니다. 만료 이유를 알려주세요. 이메일 [EMAIL_REDACTED].");
    }

    @Test
    void rejectsSensitiveValuesOutsideThePreparedUserContent() {
        var root = MAPPER.readTree(request("문의", "일반 본문"));
        ((tools.jackson.databind.node.ObjectNode) root).put("instructions", "문의 <합성_비밀번호>");

        assertThatThrownBy(() -> AiInputPrivacyRequestBridge.prepareForTransmission(MAPPER.writeValueAsString(root)))
                .hasMessage("AI_REQUEST_SENSITIVE_VALUE_PRESENT")
                .hasNoCause();
    }

    @Test
    void decodesTheJsonEncodedContentBeforeRedactingIt() {
        var root = MAPPER.readTree(request("문의", "연락처 <합성_연락처>"));
        var message = (tools.jackson.databind.node.ObjectNode) root.get("input").get(0);
        message.put("content", message.get("content").asString()
                .replace("<", "\\" + "u003c").replace(">", "\\" + "u003e"));

        String checked = AiInputPrivacyRequestBridge.prepareForTransmission(MAPPER.writeValueAsString(root));
        var input = MAPPER.readTree(MAPPER.readTree(checked).get("input").get(0).get("content").asString());
        assertThat(input.get("body").asString()).isEqualTo("연락처 [CONTACT_REDACTED]");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "{}", "[]", "{\"input\":[]}", "{\"input\":[{\"role\":\"developer\",\"content\":\"{}\"}]}"})
    void rejectsMissingOrUnsupportedRequests(String value) {
        assertThatThrownBy(() -> AiInputPrivacyRequestBridge.prepareForTransmission(value))
                .hasMessage("AI_REQUEST_BODY_INVALID");
    }

    @Test
    void rejectsBlankUserInputBeforeItCanBeTransmitted() {
        assertThatThrownBy(() -> AiInputPrivacyRequestBridge.prepareForTransmission(request("문의", " ")))
                .hasMessage("AI_INPUT_REQUIRED");
    }

    @Test
    void rejectsOversizedRequests() {
        assertThatThrownBy(() -> AiInputPrivacyRequestBridge.prepareForTransmission(request("문의", "가".repeat(6000))))
                .hasMessage("AI_REQUEST_BODY_INVALID");
    }

    private String request(String title, String body) {
        return MAPPER.writeValueAsString(Map.of("model", "gpt-6-luna", "instructions", "문의 요약",
                "input", List.of(Map.of("role", "user", "content",
                        MAPPER.writeValueAsString(Map.of("title", title, "body", body))))));
    }
}
