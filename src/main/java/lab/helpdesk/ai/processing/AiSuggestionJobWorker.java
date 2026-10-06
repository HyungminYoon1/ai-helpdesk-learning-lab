package lab.helpdesk.ai.processing;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import lab.helpdesk.ai.job.AiJobClaim;
import lab.helpdesk.ai.job.AiJobFailureCode;
import lab.helpdesk.ai.job.AiSuggestionJobClaimService;
import lab.helpdesk.ai.processing.AiSuggestionProcessingResult.Outcome;
import lab.helpdesk.ai.validation.AiSuggestionOutputValidator.ContractValidatedOutput;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One bounded tick; no sleeping, whole-tick transaction, or implicit Provider replay. */
public final class AiSuggestionJobWorker {

    private final AiSuggestionJobProcessor processor;
    private final AiSuggestionJobClaimService claims;
    private final AiSuggestionWorkerSettings settings;
    private final Clock clock;
    private PendingStorage pendingStorage;

    public AiSuggestionJobWorker(AiSuggestionJobProcessor processor, AiSuggestionJobClaimService claims) {
        this(processor, claims, new AiSuggestionWorkerSettings(1000), Clock.systemUTC());
    }

    public AiSuggestionJobWorker(AiSuggestionJobProcessor processor, AiSuggestionJobClaimService claims,
            AiSuggestionWorkerSettings settings, Clock clock) {
        this.processor = Objects.requireNonNull(processor);
        this.claims = Objects.requireNonNull(claims);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
    }

    // Serialize this Worker's ticks and keep at most one validated output, not an unbounded queue.
    public synchronized AiSuggestionProcessingResult runOnce() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("AI_PROCESSOR_REQUIRES_NO_OUTER_TRANSACTION");
        }
        if (pendingStorage != null) {
            return retryPendingStorage();
        }
        AiSuggestionProcessingResult result = processor.processNextPending();
        if (result.outcome() == Outcome.NO_JOB) {
            result = processor.processNextRecoverable();
        }
        if (result.outcome() == Outcome.STORAGE_PENDING) {
            AiJobClaim claim = Objects.requireNonNull(result.claim());
            ContractValidatedOutput output = Objects.requireNonNull(result.pendingStorageOutput());
            pendingStorage = new PendingStorage(claim, output, 1, nextCheckAt(claim, 1));
            return result;
        }
        if (result.outcome() != Outcome.TEMPORARY_REJECTION || result.minimumRetryDelay() == null) {
            // Missing hints are durably blocked. Unknown recovery must satisfy the separate claim.
            return result;
        }
        Outcome outcome = switch (claims.scheduleRateLimitRetry(result.claim(), result.minimumRetryDelay())) {
            case SCHEDULED -> Outcome.TEMPORARY_RETRY_SCHEDULED;
            case FAILED -> Outcome.FAILED;
            case NOT_CURRENT -> Outcome.NOT_CURRENT;
        };
        return new AiSuggestionProcessingResult(outcome, result.claim(), null);
    }

    private AiSuggestionProcessingResult retryPendingStorage() {
        PendingStorage pending = pendingStorage;
        boolean expired = !clock.instant().isBefore(pending.claim().processingDeadlineAt());
        if (!expired && clock.instant().isBefore(pending.nextCheckAt())) {
            return result(Outcome.STORAGE_RETRY_WAITING, pending.claim());
        }
        AiSuggestionProcessingResult inspected = processor.inspectStorageRetry(pending.claim());
        if (inspected.outcome() == Outcome.STORAGE_STATE_UNCONFIRMED) {
            return deferUnconfirmed(pending);
        }
        if (inspected.outcome() != Outcome.STORAGE_RETRY_READY) {
            pendingStorage = null;
            return inspected;
        }
        // Recheck time after the read; the original deadline is never extended.
        if (!clock.instant().isBefore(pending.claim().processingDeadlineAt())
                || pending.attempts() >= settings.maxStorageAttempts()) {
            AiJobFailureCode code = !clock.instant().isBefore(pending.claim().processingDeadlineAt())
                    ? AiJobFailureCode.JOB_PROCESSING_DEADLINE_EXCEEDED
                    : AiJobFailureCode.RESULT_STORAGE_RETRY_EXHAUSTED;
            AiSuggestionProcessingResult stopped = processor.finishStorageRetry(pending.claim(), code);
            if (stopped.outcome() == Outcome.STORAGE_STATE_UNCONFIRMED) {
                return deferUnconfirmed(pending);
            }
            pendingStorage = null;
            return stopped;
        }
        int attempts = pending.attempts() + 1;
        AiSuggestionProcessingResult stored = processor.storeValidatedResult(pending.claim(), pending.output());
        if (stored.outcome() == Outcome.STORAGE_PENDING) {
            pendingStorage = new PendingStorage(pending.claim(), pending.output(), attempts,
                    nextCheckAt(pending.claim(), attempts));
        } else {
            pendingStorage = null;
        }
        return stored;
    }

    private AiSuggestionProcessingResult deferUnconfirmed(PendingStorage pending) {
        Instant now = clock.instant();
        // Release content at the original deadline without asserting a DB state we could not read.
        pendingStorage = now.isBefore(pending.claim().processingDeadlineAt())
                ? new PendingStorage(pending.claim(), pending.output(), pending.attempts(),
                        delayUntil(pending.claim(), now)) : null;
        return result(Outcome.STORAGE_STATE_UNCONFIRMED, pending.claim());
    }

    private Instant nextCheckAt(AiJobClaim claim, int attempts) {
        Instant now = clock.instant();
        // An exhausted cycle only reconciles/terminates; it will not perform another save.
        return attempts >= settings.maxStorageAttempts() ? now : delayUntil(claim, now);
    }

    private Instant delayUntil(AiJobClaim claim, Instant now) {
        Instant delayed = now.plusMillis(settings.storageRetryDelayMs());
        return delayed.isBefore(claim.processingDeadlineAt()) ? delayed : claim.processingDeadlineAt();
    }

    private AiSuggestionProcessingResult result(Outcome outcome, AiJobClaim claim) {
        return new AiSuggestionProcessingResult(outcome, claim, null);
    }

    private record PendingStorage(AiJobClaim claim, ContractValidatedOutput output,
            int attempts, Instant nextCheckAt) {

        @Override
        public String toString() {
            return "PendingStorage[attempts=" + attempts + ", content omitted]";
        }
    }
}
