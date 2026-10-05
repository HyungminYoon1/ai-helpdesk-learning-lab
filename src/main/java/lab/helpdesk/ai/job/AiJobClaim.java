package lab.helpdesk.ai.job;

import java.time.Instant;
import java.util.Objects;

// 실제 Provider 실행 횟수가 아니라, Commit된 실행권과 예약을 나타내는 Metadata.
public record AiJobClaim(
        long jobId,
        long inputMessageId,
        int attemptNumber,
        int reservedGenerationCount,
        int reservedOutputRepairCount,
        AiJobRequestKind requestKind,
        AiJobPolicy policy,
        Instant firstStartedAt,
        Instant processingDeadlineAt,
        Instant leaseExpiresAt) {

    public AiJobClaim {
        if (jobId <= 0 || inputMessageId <= 0 || attemptNumber <= 0
                || reservedGenerationCount <= 0 || reservedOutputRepairCount < 0) {
            throw new IllegalArgumentException("invalid job claim metadata");
        }
        Objects.requireNonNull(requestKind);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(firstStartedAt);
        Objects.requireNonNull(processingDeadlineAt);
        Objects.requireNonNull(leaseExpiresAt);
    }
}
