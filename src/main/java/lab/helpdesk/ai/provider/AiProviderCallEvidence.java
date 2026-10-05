package lab.helpdesk.ai.provider;

/** Safe transport metadata only; missing usage must not be interpreted as zero cost. */
public record AiProviderCallEvidence(int httpAttempts, Integer httpStatus, TokenUsage usage,
        boolean expectedTariffConfirmed) {

    public AiProviderCallEvidence(int httpAttempts, Integer httpStatus, TokenUsage usage) {
        this(httpAttempts, httpStatus, usage, false);
    }

    public record TokenUsage(long inputTokens, long outputTokens) {
        public TokenUsage {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("AI_USAGE_INVALID");
            }
        }
    }
}
