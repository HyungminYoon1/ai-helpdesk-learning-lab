package lab.helpdesk.ai.input.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import lab.helpdesk.ai.input.AiPrivacyScanResultValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class SyntheticPrivacyScannerProgramTest {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void emitsOneV1SpanForTheFixedCaseWithoutReturningOriginalText() {
        var output = new ByteArrayOutputStream();
        int code = run(request(), output);
        assertThat(code).isZero();
        String raw = output.toString(StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain(SyntheticDeploymentPrivacyScanner.EMAIL, SyntheticDeploymentPrivacyScanner.BODY);
        var masked = new AiPrivacyScanResultValidator(1_024, 1).validateAndMask(
                SyntheticDeploymentPrivacyScanner.TITLE, SyntheticDeploymentPrivacyScanner.BODY, raw);
        assertThat(masked.title()).isEqualTo(SyntheticDeploymentPrivacyScanner.TITLE);
        assertThat(masked.body()).isEqualTo(SyntheticDeploymentPrivacyScanner.BODY
                .replace(SyntheticDeploymentPrivacyScanner.EMAIL, "[EMAIL_REDACTED]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{}", "{\"title\":5,\"body\":\"x\"}",
            "{\"title\":\"x\",\"body\":\"x\",\"extra\":true}",
            "{\"title\":\"x\",\"title\":\"y\",\"body\":\"x\"}",
            "{\"title\":\"x\",\"body\":\"x\"} {}", "{\"title\":{},\"body\":\"x\"}"})
    void invalidOrOutOfScopeRequestsFailWithoutResult(String request) {
        var output = new ByteArrayOutputStream();
        assertThat(run(request.getBytes(StandardCharsets.UTF_8), output)).isEqualTo(9);
        assertThat(output.size()).isZero();
    }

    @Test
    void doesNotRepairInvalidUtf8OrOversizedInput() {
        for (byte[] input : new byte[][] {new byte[] {(byte) 0xc3, 0x28}, new byte[4_097]}) {
            var output = new ByteArrayOutputStream();
            assertThat(run(input, output)).isEqualTo(9);
            assertThat(output.size()).isZero();
        }
    }

    @Test
    void doesNotTurnAChangedFixedCaseIntoACompletedEmptyScan() {
        var output = new ByteArrayOutputStream();
        String changed = new String(request(), StandardCharsets.UTF_8).replace("a@b.invalid", "other@example.invalid");
        assertThat(run(changed.getBytes(StandardCharsets.UTF_8), output)).isEqualTo(9);
        assertThat(output.size()).isZero();
    }

    private byte[] request() {
        return MAPPER.writeValueAsBytes(Map.of("title", SyntheticDeploymentPrivacyScanner.TITLE,
                "body", SyntheticDeploymentPrivacyScanner.BODY));
    }

    private int run(byte[] bytes, ByteArrayOutputStream output) {
        return SyntheticPrivacyScannerProgram.run(new ByteArrayInputStream(bytes), output);
    }
}
