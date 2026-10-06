package lab.helpdesk.ai.processing;

import java.util.Objects;

import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;

/** One bounded tick; no sleeping, whole-tick transaction, or implicit Provider replay. */
public final class AiSuggestionJobWorker {

    private final AiSuggestionJobProcessor processor;
    private final AiSuggestionJobClaimService claims;

    public AiSuggestionJobWorker(AiSuggestionJobProcessor processor, AiSuggestionJobClaimService claims) {
        this.processor = Objects.requireNonNull(processor);
        this.claims = Objects.requireNonNull(claims);
    }

    public AiSuggestionProcessingResult runOnce() {
        AiSuggestionProcessingResult result = processor.processNextPending();
        if (result.outcome() != Outcome.TEMPORARY_REJECTION || result.minimumRetryDelay() == null) {
            // Unknown outcomes, missing hints, and storage retries are not new generation permission.
            return result;
        }
        Outcome outcome = switch (claims.scheduleRateLimitRetry(result.claim(), result.minimumRetryDelay())) {
            case SCHEDULED -> Outcome.TEMPORARY_RETRY_SCHEDULED;
            case FAILED -> Outcome.FAILED;
            case NOT_CURRENT -> Outcome.NOT_CURRENT;
        };
        return new AiSuggestionProcessingResult(outcome, result.claim(), null);
    }
}
