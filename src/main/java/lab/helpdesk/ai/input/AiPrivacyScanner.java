package lab.helpdesk.ai.input;

import java.time.Duration;

/**
 * Port for a LOCAL detector, not an external generative AI. Implementations must
 * bound execution and response reads, honor the supplied timeout, and never
 * turn an error into a completed empty result or log the input/result body.
 */
@FunctionalInterface
public interface AiPrivacyScanner {
    String scan(String originalTitle, String originalBody, Duration timeout);

    /** Only an adapter that observed a real timeout should create this error. */
    final class ScanTimeoutException extends RuntimeException {
        public ScanTimeoutException() {
            super("PRIVACY_SCAN_TIMEOUT");
        }
    }
}
