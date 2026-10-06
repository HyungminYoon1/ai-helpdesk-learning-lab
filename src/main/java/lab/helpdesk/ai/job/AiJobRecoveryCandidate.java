package lab.helpdesk.ai.job;

/** A read-only candidate is not ownership and does not authorize a Provider call. */
public record AiJobRecoveryCandidate(long jobId, int attemptNumber) {

    public AiJobRecoveryCandidate {
        if (jobId <= 0 || attemptNumber <= 0) {
            throw new IllegalArgumentException("AI_RECOVERY_METADATA_INVALID");
        }
    }
}
