package lab.helpdesk.ai.job;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("postgres")
public class AiSuggestionJobClaimService {

    private final AiSuggestionJobExecutionRepository jobs;

    public AiSuggestionJobClaimService(AiSuggestionJobExecutionRepository jobs) {
        this.jobs = jobs;
    }

    // 호출자에게 반환되기 전에 실행권과 예약 원장의 독립 Transaction을 Commit한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AiJobClaim> claimNextPending() {
        jobs.expireProcessingDeadlines();
        Optional<AiJobClaim> claim = jobs.claimNextPending();
        claim.ifPresent(jobs::recordReservation);
        return claim;
    }

    // 후속 Worker는 가능한 기존 결과 확인을 마친 뒤에만 이 복구 경로를 호출한다.
    // 이 메서드가 Provider 조회나 미실행 확인을 대신하는 것은 아니다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AiJobClaim> claimRecoveryAfterResultCheck(long jobId, int expectedAttempt) {
        jobs.expireProcessingDeadlines();
        Optional<AiJobClaim> claim = jobs.claimRecovery(jobId, expectedAttempt);
        claim.ifPresent(jobs::recordReservation);
        return claim;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean scheduleOutputRepair(AiJobClaim claim) {
        jobs.expireProcessingDeadlines();
        if (jobs.scheduleOutputRepair(claim)) {
            return true;
        }
        jobs.failForExhaustedRepair(claim);
        return false;
    }

    // A retry schedule is not a new reservation. The next Claim commits its own reservation.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiJobRetryScheduleOutcome scheduleRateLimitRetry(AiJobClaim claim, Duration minimumWait) {
        Objects.requireNonNull(claim);
        Objects.requireNonNull(minimumWait);
        if (minimumWait.isNegative()) {
            throw new IllegalArgumentException("AI_RETRY_DELAY_INVALID");
        }
        long minimumWaitMs;
        try {
            minimumWaitMs = minimumWait.toMillis();
            if (!minimumWait.minusMillis(minimumWaitMs).isZero()) {
                minimumWaitMs = Math.addExact(minimumWaitMs, 1);
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("AI_RETRY_DELAY_INVALID");
        }
        jobs.expireProcessingDeadlines();
        if (jobs.scheduleRateLimitRetry(claim, minimumWaitMs)) {
            return AiJobRetryScheduleOutcome.SCHEDULED;
        }
        if (jobs.failForExhaustedGeneration(claim)) {
            return AiJobRetryScheduleOutcome.FAILED;
        }
        return AiJobRetryScheduleOutcome.NOT_CURRENT;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failIfCurrent(AiJobClaim claim, AiJobFailureCode failureCode) {
        jobs.expireProcessingDeadlines();
        return jobs.failIfCurrent(claim, failureCode);
    }
}
