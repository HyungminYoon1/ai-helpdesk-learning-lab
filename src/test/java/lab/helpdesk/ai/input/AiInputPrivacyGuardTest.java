package lab.helpdesk.ai.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;

import tools.jackson.databind.json.JsonMapper;

class AiInputPrivacyGuardTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final List<String> SYNTHETIC_MARKERS = List.of(
            "<합성_비밀번호>", "<합성_주소>", "<합성_연락처>");
    private static final List<SensitiveFragment> SYNTHETIC_FRAGMENTS = List.of(
            new SensitiveFragment(SYNTHETIC_MARKERS.get(0), SensitiveType.PASSWORD),
            new SensitiveFragment(SYNTHETIC_MARKERS.get(1), SensitiveType.ADDRESS),
            new SensitiveFragment(SYNTHETIC_MARKERS.get(2), SensitiveType.CONTACT));
    private final AiInputPrivacyGuard guard = new AiInputPrivacyGuard(SYNTHETIC_FRAGMENTS);

    @Test
    void preparesATransmissionCopyWithoutChangingTheOriginalInput() {
        String originalTitle = "청구지 변경 <합성_연락처>";
        String originalBody = "청구지 변경 방법 문의. 주소 <합성_주소>, 연락처 <합성_연락처>.";

        var prepared = guard.prepare(originalTitle, originalBody);

        assertThat(prepared.title()).isEqualTo("청구지 변경 [CONTACT_REDACTED]");
        assertThat(prepared.body()).isEqualTo("청구지 변경 방법 문의. 주소 [ADDRESS_REDACTED], 연락처 [CONTACT_REDACTED].");
        assertThat(originalTitle).isEqualTo("청구지 변경 <합성_연락처>");
        assertThat(originalBody).isEqualTo("청구지 변경 방법 문의. 주소 <합성_주소>, 연락처 <합성_연락처>.");
    }

    @Test
    void preservesBusinessFactsAndReplacesRepeatedKnownValues() {
        var prepared = guard.prepare("로그인 문의",
                "로그인 불가. 비밀번호 <합성_비밀번호>, 다시 적은 값 <합성_비밀번호>. 확인 요청.");

        assertThat(prepared.body()).isEqualTo(
                "로그인 불가. 비밀번호 [PASSWORD_REDACTED], 다시 적은 값 [PASSWORD_REDACTED]. 확인 요청.");
    }

    @Test
    void keepsConfirmedContactTypesWithoutExposingTheirValues() {
        var typedGuard = new AiInputPrivacyGuard(List.of(
                new SensitiveFragment("<합성_이메일>", SensitiveType.EMAIL),
                new SensitiveFragment("<합성_휴대폰>", SensitiveType.PHONE),
                new SensitiveFragment("<합성_인스타그램>", SensitiveType.INSTAGRAM_HANDLE)));

        var prepared = typedGuard.prepare("연락 수단 문의",
                "이메일 <합성_이메일>, 전화 <합성_휴대폰>, 계정 <합성_인스타그램>.");

        assertThat(prepared.body()).isEqualTo(
                "이메일 [EMAIL_REDACTED], 전화 [PHONE_REDACTED], 계정 [INSTAGRAM_HANDLE_REDACTED].");
        String serializedBody = json(request(prepared.title(), prepared.body()));
        assertThat(typedGuard.checkSerializedRequest(serializedBody)).isSameAs(serializedBody);
        assertThatThrownBy(() -> typedGuard.checkSerializedRequest(json(request("문의", "<합성_이메일>"))))
                .hasMessage("AI_REQUEST_SENSITIVE_VALUE_PRESENT");
    }

    @Test
    void preservesTheLoginRecoveryFactWhileRemovingAContactValue() {
        var prepared = guard.prepare("로그인 링크 문의",
                "새 링크로 로그인에 성공했습니다. 만료 이유를 알려주세요. 연락처 <합성_연락처>.");

        assertThat(prepared.body()).isEqualTo(
                "새 링크로 로그인에 성공했습니다. 만료 이유를 알려주세요. 연락처 [CONTACT_REDACTED].");
        assertThat(prepared.body()).doesNotContain("[PHONE_REDACTED]", "[EMAIL_REDACTED]");
    }

    @Test
    void usesAGenericLabelForAnUnknownTypeWithoutGuessing() {
        var unknownGuard = new AiInputPrivacyGuard(List.of(
                new SensitiveFragment("<합성_알수없는값>", SensitiveType.UNKNOWN)));

        assertThat(unknownGuard.prepare("문의", "값 <합성_알수없는값>").body())
                .isEqualTo("값 [REDACTED]");
    }

    @Test
    void rejectsConflictingTypeAssignmentsForTheSameValue() {
        assertThatThrownBy(() -> new AiInputPrivacyGuard(List.of(
                new SensitiveFragment("<합성_연락처>", SensitiveType.EMAIL),
                new SensitiveFragment("<합성_연락처>", SensitiveType.PHONE))))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID")
                .hasNoCause();
    }

    @Test
    void acceptsDuplicateFragmentsWithTheSameType() {
        var fragment = new SensitiveFragment("<합성_이메일>", SensitiveType.EMAIL);
        var duplicateGuard = new AiInputPrivacyGuard(List.of(fragment, fragment));

        assertThat(duplicateGuard.prepare("문의", "값 <합성_이메일>").body())
                .isEqualTo("값 [EMAIL_REDACTED]");
    }

    @Test
    void leavesOrdinaryInputUnchanged() {
        var prepared = guard.prepare("로그인 문의", "새 링크로 로그인에 성공했고 만료 이유가 궁금합니다.");
        assertThat(prepared.body()).isEqualTo("새 링크로 로그인에 성공했고 만료 이유가 궁금합니다.");
    }

    @Test
    void checksTheExactSerializedBodyPassedToTheTransportBoundary() {
        var prepared = guard.prepare("청구지 변경", "주소 <합성_주소>, 연락처 <합성_연락처>.");
        String serializedBody = json(request(prepared.title(), prepared.body()));
        List<String> receivedBodies = new ArrayList<>();

        receivedBodies.add(guard.checkSerializedRequest(serializedBody));

        assertThat(receivedBodies).containsExactly(serializedBody);
        assertThat(receivedBodies.getFirst()).isSameAs(serializedBody);
        for (String marker : SYNTHETIC_MARKERS) {
            assertThat(receivedBodies.getFirst()).doesNotContain(marker);
        }
    }

    @Test
    void rejectsAnOriginalBodyReintroducedAfterSuccessfulPreprocessing() {
        String originalBody = "청구지 변경, 연락처 <합성_연락처>.";
        var prepared = guard.prepare("청구지 변경", originalBody);
        assertThat(prepared.body()).doesNotContain("<합성_연락처>");
        String faultyRequest = json(request(prepared.title(), originalBody));
        AtomicInteger transportCalls = new AtomicInteger();

        assertThatThrownBy(() -> {
            guard.checkSerializedRequest(faultyRequest);
            transportCalls.incrementAndGet();
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI_REQUEST_SENSITIVE_VALUE_PRESENT")
                .hasNoCause();
        assertThat(transportCalls.get()).isZero();
    }

    @Test
    void checksInstructionsAndNestedFieldsAsWellAsTheUserBody() {
        assertSensitive(json(Map.of("instructions", "문의의 연락처 <합성_연락처>")));
        assertSensitive(json(Map.of("input", List.of(Map.of("content",
                Map.of("title", "<합성_주소>", "body", "일반 문의"))))));
        assertSensitive(json(Map.of("<합성_주소>", "일반 문의")));
    }

    @Test
    void detectsKnownValuesAfterJsonEscapesAreDecoded() {
        String escapedRequest = json(Map.of("instructions", "<합성_연락처>"))
                .replace("<", "\\" + "u003c").replace(">", "\\" + "u003e");

        assertThat(escapedRequest).doesNotContain("<합성_연락처>");
        assertSensitive(escapedRequest);
    }

    @Test
    void replacesLongerOverlappingFragmentsBeforeShorterOnes() {
        var overlappingGuard = new AiInputPrivacyGuard(List.of(
                new SensitiveFragment("synthetic", SensitiveType.CONTACT),
                new SensitiveFragment("synthetic-long", SensitiveType.ADDRESS)));
        assertThat(overlappingGuard.prepare("문의", "값 synthetic-long").body())
                .isEqualTo("값 [ADDRESS_REDACTED]");
    }

    @Test
    void snapshotsTheConfigurationAndDoesNotExposePreparedTextThroughToString() {
        List<SensitiveFragment> values = new ArrayList<>(SYNTHETIC_FRAGMENTS);
        var configuredGuard = new AiInputPrivacyGuard(values);
        values.clear();

        assertThat(configuredGuard.prepare("문의", "값 <합성_주소>").body()).isEqualTo("값 [ADDRESS_REDACTED]");
        assertThat(configuredGuard.prepare("문의", "unknown-private-content").toString())
                .isEqualTo("PreparedInput[content omitted]");
        assertThat(SYNTHETIC_FRAGMENTS.get(1).toString())
                .isEqualTo("SensitiveFragment[type=ADDRESS, value omitted]");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "[REDACTED]", "EMAIL_REDACTED"})
    void rejectsInvalidKnownValuesWithoutIncludingTheirContents(String value) {
        assertThatThrownBy(() -> new AiInputPrivacyGuard(List.of(
                new SensitiveFragment(value, SensitiveType.UNKNOWN))))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
    }

    @Test
    void rejectsANullFragmentOrMissingType() {
        List<SensitiveFragment> fragments = new ArrayList<>();
        fragments.add(null);

        assertThatThrownBy(() -> new AiInputPrivacyGuard(fragments))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
        assertThatThrownBy(() -> new SensitiveFragment("<합성_연락처>", null))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
    }

    @Test
    void rejectsMissingConfiguration() {
        assertThatThrownBy(() -> new AiInputPrivacyGuard(null))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
        assertThatThrownBy(() -> new AiInputPrivacyGuard(List.of()))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "{", "[]", "{} {}", "{\"x\":1,\"x\":2}"})
    void rejectsInvalidRequestsWithAFixedErrorAndNoOriginalParserCause(String value) {
        assertThatThrownBy(() -> guard.checkSerializedRequest(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI_REQUEST_BODY_INVALID")
                .hasNoCause();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t"})
    void rejectsMissingInputBeforeRequestConstruction(String value) {
        assertThatThrownBy(() -> guard.prepare(value, "본문"))
                .hasMessage("AI_INPUT_REQUIRED");
        assertThatThrownBy(() -> guard.prepare("제목", value))
                .hasMessage("AI_INPUT_REQUIRED");
    }

    private Map<String, Object> request(String title, String body) {
        return Map.of("instructions", "문의 요약", "input", List.of(Map.of(
                "role", "user", "content", json(Map.of("title", title, "body", body)))));
    }

    private void assertSensitive(String request) {
        assertThatThrownBy(() -> guard.checkSerializedRequest(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI_REQUEST_SENSITIVE_VALUE_PRESENT")
                .hasNoCause();
    }

    private String json(Object value) {
        return MAPPER.writeValueAsString(value);
    }
}
