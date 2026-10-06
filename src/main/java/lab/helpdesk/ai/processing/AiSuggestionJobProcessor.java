package lab.helpdesk.ai.processing;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import lab.helpdesk.ai.input.AiInputPrivacyGuard;
import lab.helpdesk.ai.input.AiSuggestionInputRepository;
import lab.helpdesk.ai.input.StoredAiSuggestionInput;
import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobFailureCode;
import lab.helpdesk.ai.job.AiJobStatus;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.provider.AiProviderFailureException;
import lab.helpdesk.ai.provider.AiSuggestionProvider;
import lab.helpdesk.ai.suggestion.AiSuggestionResultService;
import lab.helpdesk.ai.suggestion.AiSuggestionStoredResult;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.InvalidOutputException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * One processing step, without polling or a live Provider Bean. Explicit construction keeps
 * existing PostgreSQL runs from sending paid requests merely because this class was added.
 */
public final class AiSuggestionJobProcessor {

    private final AiSuggestionJobClaimService claims;
    private final AiSuggestionInputRepository inputs;
    private final AiInputPrivacyGuard privacy;
    private final AiSuggestionProvider provider;
    private final AiSuggestionOutputValidator validator;
    private final AiSuggestionResultService results;
    private final Clock clock;

    public AiSuggestionJobProcessor(AiSuggestionJobClaimService claims,
            AiSuggestionInputRepository inputs, AiInputPrivacyGuard privacy,
            AiSuggestionProvider provider, AiSuggestionOutputValidator validator,
            AiSuggestionResultService results, Clock clock) {
        this.claims = Objects.requireNonNull(claims);
        this.inputs = Objects.requireNonNull(inputs);
        this.privacy = Objects.requireNonNull(privacy);
        this.provider = Objects.requireNonNull(provider);
        this.validator = Objects.requireNonNull(validator);
        this.results = Objects.requireNonNull(results);
        this.clock = Objects.requireNonNull(clock);
    }

    public AiSuggestionProcessingResult processNextPending() {
        requireNoOuterTransaction();
        Optional<AiJobClaim> next;
        try {
            next = claims.claimNextPending();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("AI_JOB_CLAIM_FAILED");
        }
        if (next.isEmpty()) {
            return result(Outcome.NO_JOB, null);
        }
        AiJobClaim claim = next.orElseThrow();
        Optional<StoredAiSuggestionInput> stored;
        try {
            stored = inputs.findCurrentInput(claim);
        } catch (DataAccessException exception) {
            // Keep its unknown input/read outcome separate from a Provider request.
            throw new IllegalStateException("AI_INPUT_READ_FAILED");
        } catch (IllegalArgumentException exception) {
            return fail(claim, AiJobFailureCode.ADAPTER_CONFIGURATION_ERROR);
        }
        if (stored.isEmpty()) {
            return result(Outcome.NOT_CURRENT, claim);
        }

        AiInputPrivacyGuard.PreparedInput prepared;
        try {
            prepared = privacy.prepare(stored.orElseThrow().title(), stored.orElseThrow().body());
        } catch (IllegalArgumentException exception) {
            return fail(claim, AiJobFailureCode.ADAPTER_CONFIGURATION_ERROR);
        }
        Duration timeout = Duration.between(clock.instant(), claim.processingDeadlineAt());
        timeout = timeout.compareTo(Duration.ofMillis(claim.policy().requestTimeoutMs())) < 0
                ? timeout : Duration.ofMillis(claim.policy().requestTimeoutMs());
        if (timeout.isNegative() || timeout.isZero()) {
            return fail(claim, AiJobFailureCode.JOB_PROCESSING_DEADLINE_EXCEEDED);
        }
        try {
            if (!inputs.canSendReservedRequest(claim)) {
                return result(Outcome.NOT_CURRENT, claim);
            }
        } catch (DataAccessException exception) {
            throw new IllegalStateException("AI_INPUT_READ_FAILED");
        }

        String rawOutput;
        try {
            // Claim Service has returned through its proxy: reservation Commit is already done.
            rawOutput = provider.generate(prepared, timeout, claim.requestKind());
        } catch (AiProviderFailureException exception) {
            return switch (exception.kind()) {
                case REFUSED -> fail(claim, AiJobFailureCode.PROVIDER_REFUSED);
                case CONFIGURATION -> fail(claim, AiJobFailureCode.ADAPTER_CONFIGURATION_ERROR);
                case INVALID_RESPONSE -> fail(claim, AiJobFailureCode.OUTPUT_INVALID);
                // Return safe Claim metadata so the Worker can persist a separate retry schedule.
                // Only a confirmed rate limit with a usable hint has an approved retry path.
                case TEMPORARY_REJECTION -> new AiSuggestionProcessingResult(
                        Outcome.TEMPORARY_REJECTION, claim, null,
                        exception.reason() == AiProviderFailureException.Reason.RATE_LIMIT
                                ? exception.retryAfter().orElse(null) : null);
                // An unconfirmed request is not ABSTAIN or proof of no Provider execution.
                case OUTCOME_UNKNOWN -> result(Outcome.PROVIDER_OUTCOME_UNKNOWN, claim);
            };
        } catch (RuntimeException exception) {
            throw new IllegalStateException("AI_PROVIDER_ADAPTER_FAILED");
        }

        ContractValidatedOutput output;
        try {
            output = validator.validate(rawOutput);
        } catch (InvalidOutputException exception) {
            if (exception.repairableRequiredFieldMissing()) {
                if (claims.scheduleOutputRepair(claim)) {
                    return result(Outcome.OUTPUT_REPAIR_SCHEDULED, claim);
                }
                // false may mean a stale Attempt, not just exhaustion of the repair limit.
                boolean failed = results.findStoredResult(claim.jobId())
                        .map(storedResult -> storedResult.job().currentAttempt() == claim.attemptNumber()
                                && storedResult.job().status() == AiJobStatus.FAILED)
                        .orElse(false);
                return result(failed ? Outcome.FAILED : Outcome.NOT_CURRENT, claim);
            }
            return fail(claim, AiJobFailureCode.OUTPUT_INVALID);
        }
        return storeValidatedResult(claim, output);
    }

    /** Caller keeps the same validated object; this path never calls the Provider. */
    public AiSuggestionProcessingResult storeValidatedResult(AiJobClaim claim, ContractValidatedOutput output) {
        requireNoOuterTransaction();
        Objects.requireNonNull(claim);
        Objects.requireNonNull(output);
        try {
            return result(switch (results.complete(claim, output)) {
                case STORED -> Outcome.STORED;
                case ABSTAINED -> Outcome.ABSTAINED;
                case NOT_CURRENT -> Outcome.NOT_CURRENT;
            }, claim);
        } catch (RuntimeException exception) {
            // Unknown Commit must be reconciled before an explicit retry of this object.
            try {
                Optional<AiSuggestionStoredResult> stored = results.findStoredResult(claim.jobId());
                if (stored.isPresent()) {
                    AiSuggestionStoredResult existing = stored.orElseThrow();
                    if (existing.job().currentAttempt() != claim.attemptNumber()) {
                        return result(Outcome.NOT_CURRENT, claim);
                    }
                    if (existing.job().status() == AiJobStatus.SUCCEEDED && existing.suggestion().isPresent()) {
                        return result(Outcome.STORED, claim);
                    }
                    if (existing.job().status() == AiJobStatus.ABSTAINED && existing.suggestion().isEmpty()) {
                        return result(Outcome.ABSTAINED, claim);
                    }
                    if (existing.job().status() != AiJobStatus.RUNNING) {
                        return result(Outcome.NOT_CURRENT, claim);
                    }
                }
            } catch (RuntimeException ignored) {
                // Do not make the failed read a reason to regenerate or claim that no result exists.
            }
            return new AiSuggestionProcessingResult(Outcome.STORAGE_PENDING, claim, output);
        }
    }

    private AiSuggestionProcessingResult fail(AiJobClaim claim, AiJobFailureCode code) {
        return result(claims.failIfCurrent(claim, code) ? Outcome.FAILED : Outcome.NOT_CURRENT, claim);
    }

    /** A failed read is not proof of an absent result and never authorizes a write. */
    public AiSuggestionProcessingResult inspectStorageRetry(AiJobClaim claim) {
        requireNoOuterTransaction();
        Objects.requireNonNull(claim);
        try {
            Optional<AiSuggestionStoredResult> stored = results.findStoredResult(claim.jobId());
            if (stored.isEmpty()) {
                return result(Outcome.NOT_CURRENT, claim);
            }
            AiSuggestionStoredResult existing = stored.orElseThrow();
            if (existing.job().currentAttempt() != claim.attemptNumber()) {
                return result(Outcome.NOT_CURRENT, claim);
            }
            Outcome outcome = switch (existing.job().status()) {
                case SUCCEEDED -> existing.suggestion().isPresent()
                        ? Outcome.STORED : Outcome.STORAGE_STATE_UNCONFIRMED;
                case ABSTAINED -> existing.suggestion().isEmpty()
                        ? Outcome.ABSTAINED : Outcome.STORAGE_STATE_UNCONFIRMED;
                case FAILED -> existing.suggestion().isEmpty()
                        ? Outcome.FAILED : Outcome.STORAGE_STATE_UNCONFIRMED;
                case PENDING -> Outcome.NOT_CURRENT;
                case RUNNING -> existing.suggestion().isEmpty()
                        ? Outcome.STORAGE_RETRY_READY : Outcome.STORAGE_STATE_UNCONFIRMED;
            };
            return result(outcome, claim);
        } catch (RuntimeException exception) {
            return result(Outcome.STORAGE_STATE_UNCONFIRMED, claim);
        }
    }

    /** Conditional termination cannot overwrite a competing completion or a newer Attempt. */
    public AiSuggestionProcessingResult finishStorageRetry(AiJobClaim claim, AiJobFailureCode code) {
        requireNoOuterTransaction();
        Objects.requireNonNull(claim);
        if (code != AiJobFailureCode.RESULT_STORAGE_RETRY_EXHAUSTED
                && code != AiJobFailureCode.JOB_PROCESSING_DEADLINE_EXCEEDED) {
            throw new IllegalArgumentException("AI_STORAGE_RETRY_STOP_INVALID");
        }
        try {
            if (claims.failIfCurrent(claim, code)) {
                return result(Outcome.FAILED, claim);
            }
        } catch (RuntimeException exception) {
            return result(Outcome.STORAGE_STATE_UNCONFIRMED, claim);
        }
        AiSuggestionProcessingResult inspected = inspectStorageRetry(claim);
        return inspected.outcome() == Outcome.STORAGE_RETRY_READY
                ? result(Outcome.STORAGE_STATE_UNCONFIRMED, claim) : inspected;
    }

    private AiSuggestionProcessingResult result(Outcome outcome, AiJobClaim claim) {
        return new AiSuggestionProcessingResult(outcome, claim, null);
    }

    private static void requireNoOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("AI_PROCESSOR_REQUIRES_NO_OUTER_TRANSACTION");
        }
    }
}
