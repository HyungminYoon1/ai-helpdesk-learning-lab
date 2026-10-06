package lab.helpdesk.ai.job;

import java.util.Optional;

public interface AiSuggestionJobExecutionRepository {

    Optional<AiJobClaim> claimNextPending();

    Optional<AiJobRecoveryCandidate> findNextRecoveryCandidate();

    Optional<AiJobClaim> claimRecovery(long jobId, int expectedAttempt);

    void recordReservation(AiJobClaim claim);

    boolean recordAttemptResultIfCurrent(AiJobClaim claim, AiAttemptResultCode resultCode);

    int expireProcessingDeadlines();

    boolean scheduleOutputRepair(AiJobClaim claim);

    boolean failForExhaustedRepair(AiJobClaim claim);

    boolean scheduleRateLimitRetry(AiJobClaim claim, long minimumWaitMs);

    boolean failForExhaustedGeneration(AiJobClaim claim);

    boolean failIfCurrent(AiJobClaim claim, AiJobFailureCode failureCode);
}
