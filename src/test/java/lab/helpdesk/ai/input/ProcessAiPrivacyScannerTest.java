package lab.helpdesk.ai.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import experiment.helpdesk.privacy.PrivacyScannerProcessFixture;
import experiment.helpdesk.privacy.PrivacyScannerProcessSupport;
import experiment.helpdesk.privacy.PrivacyScannerProcessSupport.Launch;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveFragment;
import lab.helpdesk.ai.input.AiInputPrivacyGuard.SensitiveType;
import lab.helpdesk.ai.input.AiPrivacyScanner.ScanTimeoutException;
import lab.helpdesk.ai.input.AiPrivacyScanResultValidator.InvalidScanResultException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real child JVM and pipe lifecycle tests. Scanner output is controlled, not a NER accuracy claim. */
@Timeout(30)
class ProcessAiPrivacyScannerTest {
    private static final Duration CLEANUP = Duration.ofSeconds(3);
    private static final AiInputPrivacyGuard GUARD = new AiInputPrivacyGuard(List.of(
            new SensitiveFragment("SYNTHETIC_KNOWN_MARKER", SensitiveType.PASSWORD)));
    @TempDir Path temporary;
    private final List<Path> pidFiles = new ArrayList<>();

    @AfterEach
    void releaseOnlyKnownTestProcessesAfterAnAssertionFailure() throws Exception {
        for (Path file : pidFiles) {
            PrivacyScannerProcessSupport.stopIfStillOwned(file);
        }
    }

    @Test
    void usesStdinJsonWithoutChangingUnicodeWhitespaceQuotesOrShellLookingText() throws Exception {
        Launch launch = launch("VALIDATE_INPUT");
        var adapter = scanner(launch, 4_096, 4_096, 4_096);
        var preparer = new AiScannedInputPreparer(adapter,
                new AiPrivacyScanResultValidator(4_096, 16),
                GUARD, Duration.ofSeconds(5));

        var result = preparer.prepare(PrivacyScannerProcessFixture.TITLE,
                PrivacyScannerProcessFixture.BODY, Duration.ofSeconds(10));

        assertThat(result.title().equals(PrivacyScannerProcessFixture.TITLE)).isTrue();
        assertThat(result.body().equals("메일 [EMAIL_REDACTED]; 로그인 성공\n$(not-a-command)")).isTrue();
        assertStopped(launch);
        assertThat(adapter.toString()).isEqualTo("ProcessAiPrivacyScanner[configuration omitted]");
    }

    @Test
    void permitsAnExplicitControlledCompletedEmptyJsonButDoesNotInterpretItAsDetectionProof() throws Exception {
        Launch launch = launch("EMPTY_JSON");
        String response = scanner(launch, 4_096, 4_096, 4_096)
                .scan("문의", "본문", Duration.ofSeconds(5));
        assertThat(response.equals(PrivacyScannerProcessFixture.EMPTY)).isTrue();
        assertStopped(launch);
    }

    @Test
    void acceptsTheExactResponseByteLimitAndRejectsOneByteLess() throws Exception {
        int bytes = PrivacyScannerProcessFixture.EMPTY.getBytes(StandardCharsets.UTF_8).length;
        Launch accepted = launch("EMPTY_JSON");
        assertThat(scanner(accepted, 4_096, bytes, 4_096).scan("a", "b", Duration.ofSeconds(5))
                .equals(PrivacyScannerProcessFixture.EMPTY)).isTrue();
        assertStopped(accepted);
        Launch rejected = launch("EMPTY_JSON");
        assertThatThrownBy(() -> scanner(rejected, 4_096, bytes - 1, 4_096)
                .scan("a", "b", Duration.ofSeconds(5))).isInstanceOf(InvalidScanResultException.class);
        assertStopped(rejected);
    }

    @Test
    void snapshotsTheTrustedCommandRatherThanKeepingAMutableArgumentList() throws Exception {
        Launch launch = launch("EMPTY_JSON");
        var mutableCommand = new ArrayList<>(launch.command());
        var adapter = new ProcessAiPrivacyScanner(mutableCommand, 4_096, 4_096, 4_096, CLEANUP);
        mutableCommand.clear();
        assertThat(adapter.scan("a", "b", Duration.ofSeconds(5)).equals(PrivacyScannerProcessFixture.EMPTY)).isTrue();
        assertStopped(launch);
    }

    @Test
    void anAlreadyInterruptedCallerDoesNotStartANewProcessOrLoseItsFlag() throws Exception {
        Launch launch = launch("HANG");
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> scanner(launch, 64, 64, 64).scan("a", "b", Duration.ofSeconds(5)))
                    .hasMessage("AI_PRIVACY_PROCESS_INTERRUPTED").hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(Files.exists(launch.pidFile())).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void leavesJsonContractValidationToTheExistingValidator() throws Exception {
        Launch launch = launch("INVALID_JSON");
        var preparer = new AiScannedInputPreparer(scanner(launch, 4_096, 4_096, 4_096),
                new AiPrivacyScanResultValidator(4_096, 16), GUARD,
                Duration.ofSeconds(5));
        assertThatThrownBy(() -> preparer.prepare("문의", "본문", Duration.ofSeconds(10)))
                .isInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause();
        assertStopped(launch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY_OUTPUT", "INVALID_UTF8", "FLOOD_STDOUT"})
    void rejectsAnEmptyMalformedOrOversizedResponseAndReapsTheOwnedProcess(String mode) throws Exception {
        Launch launch = launch(mode);
        assertThatThrownBy(() -> scanner(launch, 4_096, 128, 4_096)
                .scan("문의", "본문", Duration.ofSeconds(5)))
                .isInstanceOf(InvalidScanResultException.class)
                .hasMessage("PRIVACY_SCAN_INVALID_RESULT").hasNoCause().hasNoSuppressedExceptions();
        assertStopped(launch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONZERO", "RAW_STDERR_FAILURE", "FLOOD_STDERR"})
    void neverPublishesStderrOrAdoptsOutputFromANonzeroExit(String mode) throws Exception {
        Launch launch = launch(mode);
        assertThatThrownBy(() -> scanner(launch, 4_096, 4_096, 128)
                .scan("SYNTHETIC_PRIVATE_TITLE", "SYNTHETIC_PRIVATE_BODY", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_PRIVACY_PROCESS_EXECUTION_FAILED").hasNoCause().hasNoSuppressedExceptions();
        assertStopped(launch);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HANG", "NEVER_READ_STDIN", "VALID_BUT_ALIVE"})
    void timeoutBoundsBothPipesAndExitThenConfirmsProcessAndIoTermination(String mode) throws Exception {
        Launch launch = launch(mode);
        long started = System.nanoTime();
        assertThatThrownBy(() -> scanner(launch, 300_000, 4_096, 4_096)
                .scan("문의", "x".repeat(200_000), Duration.ofSeconds(2)))
                .isInstanceOf(ScanTimeoutException.class)
                .hasMessage("PRIVACY_SCAN_TIMEOUT").hasNoCause().hasNoSuppressedExceptions();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(7));
        assertStopped(launch);
    }

    @Test
    void interruptionStopsOnlyThisInvocationAndRestoresTheCallingThreadsInterruptFlag() throws Exception {
        Launch unrelated = launch("HANG");
        var unrelatedBuilder = new ProcessBuilder(unrelated.command());
        unrelatedBuilder.environment().clear();
        Process other = unrelatedBuilder.start();
        Launch owned = launch("HANG");
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().name("privacy-interrupt-test").start(() -> {
            try {
                scanner(owned, 4_096, 4_096, 4_096).scan("문의", "본문", Duration.ofSeconds(20));
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            await(() -> Files.isRegularFile(owned.pidFile()) && Files.isRegularFile(unrelated.pidFile()));
            long ownedPid = readPid(owned);
            var arguments = ProcessHandle.of(ownedPid).orElseThrow().info().arguments().orElse(new String[0]);
            assertThat(Stream.of(arguments).noneMatch(a -> a.contains("문의") || a.contains("본문"))).isTrue();
            caller.interrupt();
            caller.join(Duration.ofSeconds(7));
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("AI_PRIVACY_PROCESS_INTERRUPTED").hasNoCause();
            assertThat(interrupted).isTrue();
            assertStopped(owned);
            assertThat(other.isAlive()).isTrue();
        } finally {
            caller.interrupt();
            caller.join(Duration.ofSeconds(7));
            other.destroyForcibly();
            other.waitFor(Duration.ofSeconds(5));
            other.getInputStream().close();
            other.getErrorStream().close();
            other.getOutputStream().close();
        }
    }

    @Test
    void rejectsUtf8InputByteOverflowBeforeStartingAProcess() throws Exception {
        Launch launch = launch("EMPTY_JSON");
        assertThatThrownBy(() -> scanner(launch, 128, 4_096, 4_096)
                .scan("문의", "한".repeat(50), Duration.ofSeconds(5)))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID").hasNoCause();
        assertThat(Files.exists(launch.pidFile())).isFalse();
    }

    @Test
    void requiresAnAbsoluteExecutableWithoutLeakingStartErrors() {
        assertThatThrownBy(() -> new ProcessAiPrivacyScanner(List.of("java"), 64, 64, 64, CLEANUP))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID").hasNoCause();
        Path missing = temporary.resolve("missing-executable");
        var adapter = new ProcessAiPrivacyScanner(List.of(missing.toString()), 64, 64, 64, CLEANUP);
        assertThatThrownBy(() -> adapter.scan("a", "b", Duration.ofSeconds(2)))
                .hasMessage("AI_PRIVACY_PROCESS_EXECUTION_FAILED").hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    void rejectsInvalidTimeoutsBeforeLaunching(Duration invalid) throws Exception {
        Launch launch = launch("HANG");
        assertThatThrownBy(() -> scanner(launch, 4_096, 4_096, 4_096).scan("a", "b", invalid))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID").hasNoCause();
        assertThat(Files.exists(launch.pidFile())).isFalse();
        assertThatThrownBy(() -> new ProcessAiPrivacyScanner(launch.command(), 64, 64, 64, invalid))
                .hasMessage("AI_PRIVACY_CONFIGURATION_INVALID").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonpositiveByteLimits(int invalid) throws Exception {
        Launch launch = launch("HANG");
        assertThatThrownBy(() -> scanner(launch, invalid, 64, 64)).hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
        assertThatThrownBy(() -> scanner(launch, 64, invalid, 64)).hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
        assertThatThrownBy(() -> scanner(launch, 64, 64, invalid)).hasMessage("AI_PRIVACY_CONFIGURATION_INVALID");
    }

    private ProcessAiPrivacyScanner scanner(Launch launch, int input, int output, int error) {
        return new ProcessAiPrivacyScanner(launch.command(), input, output, error, CLEANUP);
    }

    private Launch launch(String mode) throws Exception {
        Launch launch = PrivacyScannerProcessSupport.prepare(temporary, mode);
        pidFiles.add(launch.pidFile());
        return launch;
    }

    private long readPid(Launch launch) throws Exception {
        return PrivacyScannerProcessSupport.pid(launch.pidFile());
    }

    private void assertStopped(Launch launch) throws Exception {
        assertThat(Files.isRegularFile(launch.pidFile())).isTrue();
        long pid = readPid(launch);
        assertThat(PrivacyScannerProcessSupport.stillOwned(launch.pidFile()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        await(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("helpdesk-privacy-io-" + pid + "-")));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long started = System.nanoTime();
        while (!condition.getAsBoolean() && System.nanoTime() - started < Duration.ofSeconds(5).toNanos()) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static Stream<Duration> invalidDurations() {
        return Stream.of(null, Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE));
    }

}
