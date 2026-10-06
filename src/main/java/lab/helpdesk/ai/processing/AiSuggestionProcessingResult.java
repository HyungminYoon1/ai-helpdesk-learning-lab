package lab.helpdesk.ai.processing;

import java.time.Duration;

import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;

/** Internal result only. A pending validated output may be retried in DB, not regenerated. */
public record AiSuggestionProcessingResult(
        Outcome outcome, AiJobClaim claim, ContractValidatedOutput pendingStorageOutput,
        Duration minimumRetryDelay) {

    public AiSuggestionProcessingResult(Outcome outcome, AiJobClaim claim,
            ContractValidatedOutput pendingStorageOutput) {
        this(outcome, claim, pendingStorageOutput, null);
    }

    public AiSuggestionProcessingResult {
        if (minimumRetryDelay != null
                && (minimumRetryDelay.isNegative() || outcome != Outcome.TEMPORARY_REJECTION)) {
            throw new IllegalArgumentException("AI_PROCESSING_RETRY_DELAY_INVALID");
        }
    }

    public enum Outcome {
        NO_JOB, STORED, ABSTAINED, NOT_CURRENT, OUTPUT_REPAIR_SCHEDULED,
        FAILED, PROVIDER_OUTCOME_UNKNOWN, STORAGE_PENDING,
        STORAGE_RETRY_WAITING, STORAGE_RETRY_READY, STORAGE_STATE_UNCONFIRMED,
        RECOVERY_STATE_UNCONFIRMED,
        TEMPORARY_REJECTION, TEMPORARY_RETRY_SCHEDULED
    }

    @Override
    public String toString() {
        return "AiSuggestionProcessingResult[outcome=" + outcome
                + ", jobId=" + (claim == null ? "none" : claim.jobId()) + ", content omitted]";
    }
}
