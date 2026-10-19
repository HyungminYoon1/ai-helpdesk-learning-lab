package lab.helpdesk.ai.input.synthetic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SyntheticDeploymentPrivacyScannerTest {
    @Test
    void exactCaseAloneReachesTheDelegateWithTheRemainingBudget() {
        var calls = new AtomicInteger();
        var scanner = new SyntheticDeploymentPrivacyScanner((title, body, timeout) -> {
            calls.incrementAndGet();
            assertThat(title).isEqualTo(SyntheticDeploymentPrivacyScanner.TITLE);
            assertThat(body).isEqualTo(SyntheticDeploymentPrivacyScanner.BODY);
            assertThat(timeout).isEqualTo(Duration.ofMillis(150));
            return "controlled-result";
        });
        assertThat(scanner.scan(SyntheticDeploymentPrivacyScanner.TITLE, SyntheticDeploymentPrivacyScanner.BODY,
                Duration.ofMillis(150))).isEqualTo("controlled-result");
        assertThat(calls).hasValue(1);
    }

    @ParameterizedTest
    @MethodSource("outsideScope")
    void anyChangeIsRejectedBeforeTheDelegateWithoutIncludingInput(String title, String body) {
        var calls = new AtomicInteger();
        var scanner = new SyntheticDeploymentPrivacyScanner((t, b, timeout) -> {
            calls.incrementAndGet();
            return "must-not-run";
        });
        assertThatThrownBy(() -> scanner.scan(title, body, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI_SYNTHETIC_INPUT_NOT_ALLOWED").hasNoCause();
        assertThat(calls).hasValue(0);
        assertThat(scanner.toString()).doesNotContain(SyntheticDeploymentPrivacyScanner.BODY);
    }

    static Stream<Arguments> outsideScope() {
        String title = SyntheticDeploymentPrivacyScanner.TITLE;
        String body = SyntheticDeploymentPrivacyScanner.BODY;
        return Stream.of(Arguments.of(null, body), Arguments.of(title, null), Arguments.of("", body),
                Arguments.of(title + " ", body), Arguments.of(title, body + " "),
                Arguments.of(title, body.replace("a@b.invalid", "other@example.invalid")),
                Arguments.of(title, body.replace("성공했습니다", "실패했습니다")),
                Arguments.of(title, body + "\u200b"));
    }
}
