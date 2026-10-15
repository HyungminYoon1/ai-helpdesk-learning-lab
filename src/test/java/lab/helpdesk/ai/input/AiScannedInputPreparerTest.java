package lab.helpdesk.ai.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiPrivacyScanner.ScanTimeoutException;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator.InvalidScanResultException;

class AiScannedInputPreparerTest {
    private static final String EMPTY = "{\"completed\":true,\"spans\":[]}";
    private static final AiInputPrivacyGuard GUARD = new AiInputPrivacyGuard(List.of(
            new SensitiveFragment("KNOWN", SensitiveType.PASSWORD)));

    @Test
    void scansTheExactOriginalBeforeKnownFragmentReplacementChangesThePositions() {
        String original = "KNOWN a@b.invalid; 로그인 성공";
        var preparer = preparer((title, body, timeout) -> {
            assertThat(title.equals("  문의  ") && body.equals(original)).isTrue();
            return """
                    {"completed":true,"spans":[{"field":"body","start":6,"end":17,"type":"EMAIL"}]}
                    """;
        });

        var result = preparer.prepare("  문의  ", original, Duration.ofSeconds(30));

        assertThat(result.title()).isEqualTo("  문의  ");
        assertThat(result.body()).isEqualTo("[PASSWORD_REDACTED] [EMAIL_REDACTED]; 로그인 성공");
        assertThat(original.equals("KNOWN a@b.invalid; 로그인 성공")).isTrue();
    }

    @Test
    void masksAConfirmedPersonWithoutRemovingImpactDeadlineOrRequiredAction() {
        String original = "홍길동입니다. 직원 20명이 오늘 18시까지 로그인해야 합니다.";
        var preparer = preparer((title, body, timeout) -> """
                {"completed":true,"spans":[{"field":"body","start":0,"end":3,"type":"PERSON"}]}
                """);

        var result = preparer.prepare("로그인 문의", original, Duration.ofSeconds(30));

        assertThat(result.body()).isEqualTo("[PERSON_REDACTED]입니다. 직원 20명이 오늘 18시까지 로그인해야 합니다.");
        assertThat(original.equals("홍길동입니다. 직원 20명이 오늘 18시까지 로그인해야 합니다.")).isTrue();
    }

    @Test
    void limitsTheScanToTheSmallerOfTheConfiguredLimitAndRemainingJobTime() {
        var observed = new AtomicReference<Duration>();
        var preparer = preparer((title, body, timeout) -> { observed.set(timeout); return EMPTY; });

        preparer.prepare("문의", "본문", Duration.ofSeconds(30));
        assertThat(observed).hasValue(Duration.ofSeconds(10));
        preparer.prepare("문의", "본문", Duration.ofSeconds(2));
        assertThat(observed).hasValue(Duration.ofSeconds(2));
    }

    @Test
    void rejectsAResultReturnedAtOrAfterTheTimeBudgetWithoutUsingIt() {
        var nanos = new AtomicLong();
        var preparer = new AiScannedInputPreparer((title, body, timeout) -> {
            nanos.addAndGet(timeout.toNanos());
            return EMPTY;
        }, new AiPrivacyScanResultValidator(4_096, 16), GUARD, Duration.ofSeconds(10), nanos::get);

        assertThatThrownBy(() -> preparer.prepare("문의", "본문", Duration.ofSeconds(30)))
                .isInstanceOf(ScanTimeoutException.class).hasMessage("PRIVACY_SCAN_TIMEOUT").hasNoCause();
    }

    @Test
    void preservesATypedAdapterTimeoutAndNeverFallsBackToTheOriginal() {
        var calls = new AtomicInteger();
        var preparer = preparer((title, body, timeout) -> {
            calls.incrementAndGet();
            throw new ScanTimeoutException();
        });
        assertThatThrownBy(() -> preparer.prepare("문의", "본문", Duration.ofSeconds(30)))
                .isInstanceOf(ScanTimeoutException.class).hasNoCause();
        assertThat(calls).hasValue(1);
    }

    @Test
    void incompleteOutputIsAnInvalidResultNotAnInventedTimeout() {
        var preparer = preparer((title, body, timeout) -> "{\"completed\":false,\"spans\":[]}");
        assertThatThrownBy(() -> preparer.prepare("문의", "본문", Duration.ofSeconds(30)))
                .isInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("invalidScanLimits")
    void requiresAnExplicitPositiveNanosecondRepresentableLimit(Duration invalid) {
        assertThatThrownBy(() -> new AiScannedInputPreparer((a, b, t) -> EMPTY,
                new AiPrivacyScanResultValidator(4_096, 16), GUARD, invalid))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID").hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("expiredJobTimes")
    void rejectsMissingOrExpiredJobTimeBeforeInvokingTheScanner(Duration invalid) {
        var calls = new AtomicInteger();
        var preparer = preparer((a, b, t) -> { calls.incrementAndGet(); return EMPTY; });
        assertThatThrownBy(() -> preparer.prepare("문의", "본문", invalid))
                .hasMessage("AI_PRIVACY_TIME_BUDGET_INVALID").hasNoCause();
        assertThat(calls).hasValue(0);
    }

    private AiScannedInputPreparer preparer(AiPrivacyScanner scanner) {
        return new AiScannedInputPreparer(scanner, new AiPrivacyScanResultValidator(4_096, 16),
                GUARD, Duration.ofSeconds(10));
    }

    private static Stream<Duration> invalidScanLimits() {
        return Stream.of(null, Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE));
    }

    private static Stream<Duration> expiredJobTimes() {
        return Stream.of(null, Duration.ZERO, Duration.ofNanos(-1));
    }
}
