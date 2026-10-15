package lab.helpdesk.ai.input;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

import lab.helpdesk.ai.input.AiInputPrivacyGuard.PreparedInput;
import lab.helpdesk.ai.input.AiPrivacyScanner.ScanTimeoutException;

/** Local scan -> strict JSON/range validation -> copy masking -> known-fragment guard. */
public final class AiScannedInputPreparer {
    private final AiPrivacyScanner scanner;
    private final AiPrivacyScanResultValidator validator;
    private final AiInputPrivacyGuard guard;
    private final Duration maxScanDuration;
    private final LongSupplier nanoTime;

    public AiScannedInputPreparer(AiPrivacyScanner scanner, AiPrivacyScanResultValidator validator,
            AiInputPrivacyGuard guard, Duration maxScanDuration) {
        this(scanner, validator, guard, maxScanDuration, System::nanoTime);
    }

    // Monotonic-time seam for deterministic late-response tests, not a runtime default.
    AiScannedInputPreparer(AiPrivacyScanner scanner, AiPrivacyScanResultValidator validator,
            AiInputPrivacyGuard guard, Duration maxScanDuration, LongSupplier nanoTime) {
        this.scanner = Objects.requireNonNull(scanner);
        this.validator = Objects.requireNonNull(validator);
        this.guard = Objects.requireNonNull(guard);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        if (!positiveNanos(maxScanDuration)) {
            throw new IllegalArgumentException("AI_PRIVACY_CONFIGURATION_INVALID");
        }
        this.maxScanDuration = maxScanDuration;
    }

    public PreparedInput prepare(String title, String body, Duration remainingJobTime) {
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException("AI_INPUT_REQUIRED");
        }
        if (remainingJobTime == null || remainingJobTime.isZero() || remainingJobTime.isNegative()) {
            throw new IllegalArgumentException("AI_PRIVACY_TIME_BUDGET_INVALID");
        }
        Duration timeout = maxScanDuration.compareTo(remainingJobTime) < 0 ? maxScanDuration : remainingJobTime;
        long started = nanoTime.getAsLong();
        String raw = scanner.scan(title, body, timeout);
        // An adapter still MUST bound a call that never returns. This post-return
        // check only rejects results that arrived after the allowed duration.
        if (nanoTime.getAsLong() - started >= timeout.toNanos()) {
            throw new ScanTimeoutException();
        }
        var masked = validator.validateAndMask(title, body, raw);
        return guard.prepare(masked.title(), masked.body());
    }

    private static boolean positiveNanos(Duration value) {
        if (value == null || value.isZero() || value.isNegative()) return false;
        try {
            return value.toNanos() > 0;
        } catch (ArithmeticException exception) {
            return false;
        }
    }
}
