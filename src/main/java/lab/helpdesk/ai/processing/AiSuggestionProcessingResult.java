package lab.helpdesk.ai.processing;

import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;

/** Internal result only. A pending validated output may be retried in DB, not regenerated. */
public record AiSuggestionProcessingResult(
        Outcome outcome, AiJobClaim claim, ContractValidatedOutput pendingStorageOutput) {

    public enum Outcome {
        NO_JOB, STORED, ABSTAINED, NOT_CURRENT, OUTPUT_REPAIR_SCHEDULED,
        FAILED, PROVIDER_OUTCOME_UNKNOWN, STORAGE_PENDING
    }

    @Override
    public String toString() {
        return "AiSuggestionProcessingResult[outcome=" + outcome
                + ", jobId=" + (claim == null ? "none" : claim.jobId()) + ", content omitted]";
    }
}
