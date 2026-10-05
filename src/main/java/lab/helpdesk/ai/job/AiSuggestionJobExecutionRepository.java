package lab.helpdesk.ai.job;

import java.util.Optional;

public interface AiSuggestionJobExecutionRepository {

    Optional<AiJobClaim> claimNextPending();

    Optional<AiJobClaim> claimRecovery(long jobId, int expectedAttempt);

    void recordReservation(AiJobClaim claim);

    int expireProcessingDeadlines();

    boolean scheduleOutputRepair(AiJobClaim claim);

    boolean failForExhaustedRepair(AiJobClaim claim);

    boolean failIfCurrent(AiJobClaim claim, AiJobFailureCode failureCode);
}
