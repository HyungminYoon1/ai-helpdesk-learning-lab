package lab.helpdesk.ai.input;

import java.time.Duration;

/**
 * Test-only fixed completed response for legacy controlled/synthetic fixtures.
 * This is NOT a detector and is never provided by Main configuration.
 */
public final class AiPrivacyScanFixtures {
    private AiPrivacyScanFixtures() { }

    public static AiScannedInputPreparer completedEmptyScan(AiInputPrivacyGuard guard) {
        return new AiScannedInputPreparer((title, body, timeout) -> "{\"completed\":true,\"spans\":[]}",
                new AiPrivacyScanResultValidator(4_096, 16), guard, Duration.ofSeconds(10));
    }
}
